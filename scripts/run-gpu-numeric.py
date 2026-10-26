#!/usr/bin/env python3
"""App-UID GPU arithmetic/storage diagnostics, never automatic speed promotion."""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import sys
import subprocess
import uuid
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready, read_health

ROOT=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk',type=pathlib.Path,default=ROOT/'build/gpu-app/outputs/apk/debug/app-debug.apk')
parser.add_argument('--tests',type=pathlib.Path,default=ROOT/'build/gpu-app/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
parser.add_argument('--backend',choices=['vulkan','opencl'],required=True)
args=parser.parse_args()
if any(not path.resolve().is_relative_to(ROOT) for path in (args.apk,args.tests)): parser.error('Expected workspace artifacts')
lease=acquire_phone_lease();health=require_phone_ready()
id='gpu-'+uuid.uuid4().hex
out=ROOT/'bench/results'/('gpu-numeric-'+args.backend+'-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
report=dict(passed=False,backend=args.backend,health_before=health,
            compute_attempted=False,android_user_id=0,
            apk_sha256=hashlib.file_digest(args.apk.open('rb'),'sha256').hexdigest(),
            test_apk_sha256=hashlib.file_digest(args.tests.open('rb'),'sha256').hexdigest(),
            scope='Actual app UID and APK GPU arithmetic vs fixed original-F32-activation F64 limits, weight-byte roundtrip; not model/thermal/speed acceptance')
for source in ['scripts/run-gpu-numeric.py','app/src/main/cpp/backend-checks-jni.cpp','native-runtime/tests/numeric-cases.cpp']:
    (out/pathlib.Path(source).name).write_bytes((ROOT/source).read_bytes())
gates=ROOT/'bench/numeric-gates.json';(out/'numeric-gates.json').write_bytes(gates.read_bytes())
def adb(*args,timeout=30,check=True):
    return subprocess.run(['adb','-d',*args],capture_output=True,text=True,timeout=timeout,check=check)
try:
    stamp=adb('shell', "date '+%m-%d %H:%M:%S.000'").stdout.strip()
    # PackageManager otherwise aggregates owner/work-profile UIDs as a CSV.
    # All project fixtures and run-as operations belong to the owner user0.
    uid_text=adb('shell','cmd','package','list','packages','--user','0','-U','org.motoai.runtime').stdout
    uid_match=re.search(r'^package:org\.motoai\.runtime uid:(\d+)$',uid_text,re.MULTILINE)
    if not uid_match: raise RuntimeError('Cannot isolate app UID logs')
    report['log_uid']=int(uid_match[1]);report['log_since_device_wall']=stamp
    adb('shell','am','force-stop','org.motoai.runtime')
    adb('install','-r',str(args.apk),timeout=90)
    adb('install','-t','-r',str(args.tests),timeout=90)
    installed=adb('shell','pm','path','org.motoai.runtime').stdout.strip().removeprefix('package:')
    if adb('shell','sha256sum',installed).stdout.split()[0]!=report['apk_sha256']: raise RuntimeError('Installed identity differs')
    staging='/data/local/tmp/'+id+'-gates.json'
    adb('push',str(gates),staging)
    adb('shell','run-as','org.motoai.runtime','mkdir','-p','files/bench')
    adb('shell','run-as','org.motoai.runtime','cp',staging,'files/bench/'+id+'-gates.json')
    adb('shell','rm','-f',staging)
    report['compute_attempted']=True
    print('Running app-UID '+args.backend+' numeric/storage diagnostics',flush=True)
    completed=adb('shell','am','instrument','-w','-r','-e','scope','gpu-numeric','-e','backend',args.backend,
                  '-e','gatesFile',id+'-gates.json','-e','runId',id,
                  'org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation',timeout=420)
    (out/'instrumentation.txt').write_text(completed.stdout+'\n'+completed.stderr)
    raw=adb('exec-out','run-as','org.motoai.runtime','cat','files/'+id+'-report.json',check=False)
    (out/'app-report.json').write_text(raw.stdout)
    data=json.loads(raw.stdout);report['app']=data
    numeric=data.get('numeric',{});cases=numeric.get('cases',[])
    # Unsupported/forbidden diagnostics never pass; record the smaller Ornith
    # dtype screen separately instead of pretending Gemma's BF16 was exercised.
    report['dtype_screens']={}
    for name in ('f32','f16','bf16','q4_K','q5_K','q6_K'):
        selected=[case for case in cases if case['type']==name]
        report['dtype_screens'][name]=dict(cases=len(selected),passed=len(selected)==9 and all(case['status']=='pass' for case in selected))
    report['ornith_dtype_screen_passed']=all(report['dtype_screens'][name]['passed'] for name in ('f32','q4_K','q6_K'))
    report['passed']=data.get('passed') is True and len(cases)==54 and all(value['passed'] for value in report['dtype_screens'].values())
except Exception as error: report['error']=str(error)
finally:
    for name,command in [('exit-info',['dumpsys','activity','exit-info','org.motoai.runtime']),
                         ('cleanup',['am','force-stop','org.motoai.runtime'])]:
        try:
            data=adb('shell',*command,check=False);(out/(name+'.txt')).write_text(data.stdout+'\n'+data.stderr)
        except Exception as error: report[name+'_error']=str(error)
    if report.get('log_uid') is not None:
        try:
            logs=adb('logcat','-d','--uid='+str(report['log_uid']),'-T',report['log_since_device_wall'],timeout=30,check=False)
            (out/'app-uid-logcat.txt').write_text(logs.stdout+'\n'+logs.stderr)
        except Exception as error: report['log_error']=str(error)
    # A GPU failure must leave a usable explicit CPU route in a fresh process.
    try:
        installed=adb('shell','pm','path','org.motoai.runtime').stdout.strip().removeprefix('package:')
        if not installed or adb('shell','sha256sum',installed,check=False).stdout.split()[:1]!=[report['apk_sha256']]:
            adb('install','-r',str(args.apk),timeout=90)
        print('Checking explicit CPU recovery',flush=True)
        fallback=subprocess.run([sys.executable,'scripts/run-runtime-tests.py','--apk',str(args.apk),'--tests',str(args.tests),
                                 '--require-ephemeral'],cwd=ROOT,capture_output=True,text=True,timeout=720,**lease.child_options())
        (out/'cpu-recovery.log').write_text(fallback.stdout+'\n'+fallback.stderr)
        match=re.search(r'^Results: (bench/results/[^\n]+)$',fallback.stdout,re.MULTILINE)
        report['cpu_recovery_passed']=fallback.returncode==0 and bool(match)
        if match: report['cpu_recovery_artifact']=match[1]
        if not report['cpu_recovery_passed']: report['passed']=False
    except Exception as error:
        report['cpu_recovery_passed']=False;report['cpu_recovery_error']=str(error);report['passed']=False
    report['health_after']=read_health()
    if report['health_after'].get('boot_id_sha256')!=health.get('boot_id_sha256'): report['passed']=False
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Results:',out.relative_to(ROOT),flush=True)
    print('PASS' if report['passed'] else 'NOT QUALIFIED',flush=True)
raise SystemExit(0 if report['passed'] else 1)
