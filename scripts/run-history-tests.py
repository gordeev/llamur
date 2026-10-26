#!/usr/bin/env python3
"""Verify explicit encrypted history against unique public fixtures on the16KiB emulator."""
import argparse,datetime,hashlib,json,pathlib,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1];p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=pathlib.Path,required=True);p.add_argument('--tests',type=pathlib.Path,required=True);a=p.parse_args()
assert all(f.resolve().is_relative_to(ROOT) for f in [a.apk,a.tests]);out=ROOT/'bench/results'/('history-emulator-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir();sha=lambda f:hashlib.file_digest(f.open('rb'),'sha256').hexdigest();r=dict(passed=False,apk_sha256=sha(a.apk),test_apk_sha256=sha(a.tests))
def adb(*args):return subprocess.run(['adb','-s','emulator-5580',*args],capture_output=True,text=True,timeout=300,check=True).stdout
try:
 assert adb('shell','getconf','PAGE_SIZE').strip()=='16384';adb('shell','am','force-stop','org.motoai.runtime');adb('install','-r',str(a.apk));adb('install','-t','-r',str(a.tests))
 for pkg,h in [('org.motoai.runtime',r['apk_sha256']),('org.motoai.runtime.test',r['test_apk_sha256'])]:
  path=adb('shell','pm','path',pkg).strip().removeprefix('package:');assert adb('shell','sha256sum',path).split()[0]==h
 adb('shell','run-as','org.motoai.runtime','rm','-f','files/runtime-history-tests.json');log=adb('shell','am','instrument','-w','-r','-e','scope','history','org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation');(out/'instrumentation.txt').write_text(log)
 raw=adb('exec-out','run-as','org.motoai.runtime','cat','files/runtime-history-tests.json');(out/'app-report.json').write_text(raw);r['app']=json.loads(raw);assert 'INSTRUMENTATION_CODE: -1' in log and r['app'].get('passed') is True,r['app'].get('error','History instrumentation failed');r['passed']=True
except Exception as e:r['error']=str(e)
finally:
 adb('shell','am','force-stop','org.motoai.runtime');(out/'controller.py').write_bytes(pathlib.Path(__file__).read_bytes());(out/'report.json').write_text(json.dumps(r,indent=2)+'\n');print(out.relative_to(ROOT));print('PASS' if r['passed'] else r['error'])
raise SystemExit(not r['passed'])
