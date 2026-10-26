#!/usr/bin/env python3
"""Independent16KiB emulator checks; every ADB call targets a verified emulator."""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import subprocess

ROOT=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',default='emulator-5580')
parser.add_argument('--apk',type=pathlib.Path,default=ROOT/'build/gpu-app/outputs/apk/debug/app-debug.apk')
parser.add_argument('--background-only',action='store_true')
parser.add_argument('--with-product-background',action='store_true')
parser.add_argument('--tests',type=pathlib.Path,default=ROOT/'build/gpu-app/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
args=parser.parse_args()
if not re.fullmatch(r'emulator-\d+',args.serial): parser.error('Only an explicit emulator serial is allowed')
out=ROOT/'bench/results'/('emulator-16k-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
report=dict(passed=False,scope='ARM64 emulator16KiB CPU/tiny-fixture lifecycle only; not real9B/Gemma capacity, GPU or handset speed acceptance',
            apk_sha256=hashlib.file_digest(args.apk.open('rb'),'sha256').hexdigest(),
            test_apk_sha256=hashlib.file_digest(args.tests.open('rb'),'sha256').hexdigest())
def adb(*items,timeout=30,check=True):
    return subprocess.run(['adb','-s',args.serial,*items],capture_output=True,text=True,timeout=timeout,check=check)
try:
    if adb('shell','getprop','ro.boot.qemu').stdout.strip()!='1': raise RuntimeError('Target is not an emulator; no app/phone state changed')
    if adb('shell','getprop','sys.boot_completed').stdout.strip()!='1': raise RuntimeError('Emulator is still booting')
    if adb('shell','getconf','PAGE_SIZE').stdout.strip()!='16384': raise RuntimeError('Emulator does not use16KiB pages')
    report['system_page_size']=16384
    report['abi']=adb('shell','getprop','ro.product.cpu.abi').stdout.strip()
    if report['abi']!='arm64-v8a': raise RuntimeError('Expected ARM64 CPU fallback')
    adb('install','-r',str(args.apk),timeout=300)
    adb('install','-t','-r',str(args.tests),timeout=300)
    installed=adb('shell','pm','path','org.motoai.runtime').stdout.strip().removeprefix('package:')
    if adb('shell','sha256sum',installed).stdout.split()[0]!=report['apk_sha256']: raise RuntimeError('Installed identity differs')
    permissions=adb('shell','dumpsys','package','org.motoai.runtime').stdout
    match=re.search(r'android\.permission\.POST_NOTIFICATIONS: granted=(true|false)',permissions)
    if not match: raise RuntimeError('Cannot establish notification permission precondition')
    report['notifications_granted_before']=match[1]=='true'
    adb('shell','pm','grant','org.motoai.runtime','android.permission.POST_NOTIFICATIONS')
    fixture=next(item for item in json.loads((ROOT/'bench/models.lock.json').read_text())['models'] if item['id']=='smoke')
    source=ROOT/'models'/fixture['filename']
    if hashlib.file_digest(source.open('rb'),'sha256').hexdigest()!=fixture['sha256']: raise RuntimeError('Local public framework fixture SHA differs')
    staging='/data/local/tmp/motoai-framework-tiny.gguf'
    adb('push',str(source),staging)
    adb('shell','run-as','org.motoai.runtime','mkdir','-p','files/models')
    adb('shell','run-as','org.motoai.runtime','cp',staging,'files/models/stories260K.gguf.part')
    if adb('shell','run-as','org.motoai.runtime','sha256sum','files/models/stories260K.gguf.part').stdout.split()[0]!=fixture['sha256']:
        raise RuntimeError('Emulator framework fixture SHA differs')
    adb('shell','run-as','org.motoai.runtime','mv','files/models/stories260K.gguf.part','files/models/stories260K.gguf')
    adb('shell','rm','-f',staging)
    report['fixture_sha256']=fixture['sha256']
    adb('shell','am','force-stop','org.motoai.runtime')
    if not args.background_only:
        adb('shell','run-as','org.motoai.runtime','rm','-f','files/runtime-tests.json')
        completed=adb('shell','am','instrument','-w','-r','org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation',timeout=600)
        (out/'instrumentation.txt').write_text(completed.stdout+'\n'+completed.stderr)
        data=json.loads(adb('exec-out','run-as','org.motoai.runtime','cat','files/runtime-tests.json').stdout)
        report['runtime']=data
        report['passed']='INSTRUMENTATION_CODE: -1' in completed.stdout and data.get('passed') is True and data.get('cycles')==30 and data.get('ephemeral',{}).get('passed') is True and data.get('service',{}).get('passed') is True
        initialization=data.get('initialization',{})
        report['passed'] &= initialization.get('page_size')==16384 and initialization.get('backend_registries')==['CPU']
        if not report['passed']: raise RuntimeError('CPU framework precondition failed')
    else:
        report['passed']=True
        report['scope']='Independent16KiB emulator background-only lifecycle check; no full-framework/real-model/speed acceptance'
    report['background']={}
    for permission in ('granted','denied'):
        adb('shell','am','force-stop','org.motoai.runtime')
        adb('shell','pm','grant' if permission=='granted' else 'revoke','org.motoai.runtime','android.permission.POST_NOTIFICATIONS')
        adb('shell','run-as','org.motoai.runtime','rm','-f','files/runtime-background-tests.json','files/background-failure.png','files/background-failure-ui.txt')
        completed=adb('shell','am','instrument','-w','-r','-e','scope','background',
                      'org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation',timeout=120)
        (out/('background-'+permission+'.txt')).write_text(completed.stdout+'\n'+completed.stderr)
        data=json.loads(adb('exec-out','run-as','org.motoai.runtime','cat','files/runtime-background-tests.json').stdout)
        report['background'][permission]=data
        for diagnostic in ('background-failure.png','background-failure-ui.txt'):
            captured=subprocess.run(['adb','-s',args.serial,'exec-out','run-as','org.motoai.runtime','cat','files/'+diagnostic],capture_output=True)
            if captured.returncode==0: (out/(permission+'-'+diagnostic)).write_bytes(captured.stdout)
        if 'INSTRUMENTATION_CODE: -1' not in completed.stdout or data.get('passed') is not True:
            report['passed']=False
            raise RuntimeError('Background lifecycle failed with notifications '+permission)
    if args.with_product_background:
        report['product_background']={}
        for permission in ['granted','denied']:
            adb('shell','am','force-stop','org.motoai.runtime')
            adb('shell','pm','grant' if permission=='granted' else 'revoke','org.motoai.runtime','android.permission.POST_NOTIFICATIONS')
            adb('shell','run-as','org.motoai.runtime','rm','-f','files/runtime-product-background-tests.json')
            completed=adb('shell','am','instrument','-w','-r','-e','scope','product-background',
                          'org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation',timeout=120)
            (out/('product-background-'+permission+'.txt')).write_text(completed.stdout+'\n'+completed.stderr)
            data=json.loads(adb('exec-out','run-as','org.motoai.runtime','cat','files/runtime-product-background-tests.json').stdout)
            report['product_background'][permission]=data
            if 'INSTRUMENTATION_CODE: -1' not in completed.stdout or data.get('passed') is not True:
                report['passed']=False
                raise RuntimeError('Product background lifecycle failed with notifications '+permission)
except Exception as error: report['error']=str(error)
finally:
    try:
        (out/'package.txt').write_text(adb('shell','dumpsys','package','org.motoai.runtime',check=False).stdout)
        (out/'exit-info.txt').write_text(adb('shell','dumpsys','activity','exit-info','org.motoai.runtime',check=False).stdout)
        adb('shell','am','force-stop','org.motoai.runtime',check=False)
        if 'notifications_granted_before' in report:
            adb('shell','pm','grant' if report['notifications_granted_before'] else 'revoke','org.motoai.runtime','android.permission.POST_NOTIFICATIONS')
    except Exception as error: report['cleanup_error']=str(error);report['passed']=False
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Results:',out.relative_to(ROOT),flush=True)
    print('PASS' if report['passed'] else report.get('error','FAIL'))
raise SystemExit(0 if report['passed'] else 1)
