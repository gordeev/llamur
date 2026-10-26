#!/usr/bin/env python3
"""Frozen APK product acceptance; explicitly target one emulator or the leased handset."""
import argparse,datetime,hashlib,json,pathlib,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk',type=pathlib.Path,required=True);p.add_argument('--tests',type=pathlib.Path,required=True)
p.add_argument('--reuse-installation',action='store_true',help='Verify installed identity without replacing the package (reboot grant checks)')
p.add_argument('--scope',choices=['product','product-background','downloads','reference','storage','saf-ui','saf-resume','saf-copy-delete'],default='product');p.add_argument('--serial',default='emulator-5580');p.add_argument('--phone',action='store_true');a=p.parse_args()
lease=None;health=None
if a.phone:
 from phone_lease import acquire_phone_lease
 from phone_health import require_phone_ready
 lease=acquire_phone_lease();health=require_phone_ready();target=['-d']
else:
 import re
 if not re.fullmatch(r'emulator-\d+',a.serial):p.error('An explicit emulator serial is required')
 target=['-s',a.serial]
for source in [a.apk,a.tests]:
 if not source.resolve().is_relative_to(ROOT):p.error('Frozen workspace APKs required')
out=ROOT/'bench/results'/(a.scope+'-'+('phone' if a.phone else 'emulator')+'-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
report=dict(passed=False,apk_sha256=hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest(),test_apk_sha256=hashlib.file_digest(a.tests.open('rb'),'sha256').hexdigest(),health_before=health,scope='Public tiny fixture product mechanics; no real-model capacity, speed, HF auth or Aider coding acceptance')
def adb(*args,timeout=60):return subprocess.run(['adb',*target,*args],capture_output=True,text=True,timeout=timeout,check=True)
try:
 if not a.phone:
  if adb('shell','getprop','ro.boot.qemu').stdout.strip()!='1':raise RuntimeError('Not an emulator')
  report['page_size']=int(adb('shell','getconf','PAGE_SIZE').stdout.strip())
 adb('shell','am','force-stop','org.motoai.runtime')
 if not a.reuse_installation:
  adb('install','-r',str(a.apk),timeout=300);adb('install','-t','-r',str(a.tests),timeout=300)
 test_path=adb('shell','pm','path','org.motoai.runtime.test').stdout.strip().removeprefix('package:')
 if adb('shell','sha256sum',test_path).stdout.split()[0]!=report['test_apk_sha256']:raise RuntimeError('Installed test APK differs')
 installed=adb('shell','pm','path','org.motoai.runtime').stdout.strip().removeprefix('package:')
 if adb('shell','sha256sum',installed).stdout.split()[0]!=report['apk_sha256']:raise RuntimeError('Installed APK differs')
 fixture=next(m for m in json.loads((ROOT/'bench/models.lock.json').read_text())['models'] if m['id']=='smoke')
 if adb('exec-out','run-as','org.motoai.runtime','sha256sum','files/models/'+fixture['filename']).stdout.split()[0]!=fixture['sha256']:raise RuntimeError('Stage the exact public tiny fixture first')
 adb('shell','run-as','org.motoai.runtime','rm','-f','files/runtime-'+a.scope+'-tests.json')
 result=adb('shell','am','instrument','-w','-r','-e','scope',a.scope,'org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation',timeout=240)
 (out/'instrumentation.txt').write_text(result.stdout+result.stderr)
 raw=adb('exec-out','run-as','org.motoai.runtime','cat','files/runtime-'+a.scope+'-tests.json').stdout
 (out/'app-report.json').write_text(raw)
 if 'INSTRUMENTATION_CODE: -1' not in result.stdout and not raw.lstrip().startswith('{'):raise RuntimeError('Instrumentation process crashed before writing a product report; inspect retained instrumentation/crash logs')
 app=json.loads(raw);report['app']=app
 report['passed']=app.get('passed') is True and 'INSTRUMENTATION_CODE: -1' in result.stdout
 if not report['passed']:report['error']=app.get('error','Instrumentation failed')
except Exception as error:report['error']=str(error)
finally:
 if a.phone:
  from phone_health import read_health
  report['health_after']=read_health()
 (out/'run-product-tests.py').write_bytes(pathlib.Path(__file__).read_bytes())
 for name in ['ProductChecks.kt','ReferenceChecks.kt','SafChecks.kt','RuntimeInstrumentation.kt']:
  (out/name).write_bytes((ROOT/'app/src/androidTest/java/org/motoai/runtime'/name).read_bytes())
 (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print('Results:',out.relative_to(ROOT),flush=True);print('PASS' if report['passed'] else report.get('error','FAIL'),flush=True)
raise SystemExit(0 if report['passed'] else 1)
