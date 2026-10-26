#!/usr/bin/env python3
"""Leased handset product background/notification mechanics; own tiny public fixture."""
import argparse,datetime,json,pathlib,re,subprocess
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready,read_health
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=pathlib.Path,required=True);p.add_argument('--tests',type=pathlib.Path,required=True);a=p.parse_args()
lease=acquire_phone_lease();health=require_phone_ready();out=ROOT/'bench/results'/('background-phone-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
r=dict(passed=False,health_before=health,scope='Actual handset screen-off tiny public-fixture product generation, notification permission grant/deny and real SystemUI tap. Not large-model speed/capacity or deep-Doze/energy certification.')
def adb(*items):return subprocess.run(['adb','-d',*items],capture_output=True,text=True,timeout=30,check=True)
original=None
try:
 permissions=adb('shell','dumpsys','package','org.motoai.runtime').stdout
 match=re.search(r'android\.permission\.POST_NOTIFICATIONS: granted=(true|false)',permissions)
 if not match:raise RuntimeError('Unknown original notification permission')
 original=match[1]=='true';r['notifications_granted_before']=original
 for enabled in [True,False]:
  label='granted' if enabled else 'denied';adb('shell','am','force-stop','org.motoai.runtime')
  adb('shell','pm','grant' if enabled else 'revoke','org.motoai.runtime','android.permission.POST_NOTIFICATIONS')
  result=subprocess.run(['python3',str(ROOT/'scripts/run-product-tests.py'),'--phone','--reuse-installation','--scope','product-background','--apk',str(a.apk),'--tests',str(a.tests)],capture_output=True,text=True,timeout=300,**lease.child_options())
  (out/(label+'.txt')).write_text(result.stdout+result.stderr)
  if result.returncode:raise RuntimeError(label+': '+result.stdout+result.stderr)
  r[label]=result.stdout.split('Results: ')[1].splitlines()[0]
 r['passed']=True
except Exception as error:r['error']=str(error)
finally:
 try:
  adb('shell','input','keyevent','KEYCODE_WAKEUP')
  if original is not None:adb('shell','pm','grant' if original else 'revoke','org.motoai.runtime','android.permission.POST_NOTIFICATIONS')
 except Exception as error:r['restore_error']=str(error);r['passed']=False
 r['health_after']=read_health();(out/'controller.py').write_bytes(pathlib.Path(__file__).read_bytes());(out/'report.json').write_text(json.dumps(r,indent=2)+'\n');print('Results:',out.relative_to(ROOT));print('PASS' if r['passed'] else r['error'])
raise SystemExit(0 if r['passed'] else 1)
