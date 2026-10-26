#!/usr/bin/env python3
"""Install the reviewed non-debug provider candidate, remove own tests, leave phone idle."""
import argparse,datetime,hashlib,json,pathlib,re,subprocess
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready,read_health
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=pathlib.Path,required=True);a=p.parse_args()
if not a.apk.resolve().is_relative_to(ROOT):p.error('Workspace APK required')
lease=acquire_phone_lease();before=require_phone_ready();out=ROOT/'bench/results'/('provider-release-phone-idle-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
r=dict(passed=False,health_before=before,apk_sha256=hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest(),scope='Actual non-debug0.3 installed under PhoneLease, own test package removed, fresh empty UI/idle. No model generation, private screen capture or new cloud/native quality claim. Original model files untouched.')
def adb(*args):return subprocess.run(['adb','-d',*args],capture_output=True,text=True,check=True,timeout=180).stdout
try:
 adb('shell','am','force-stop','org.motoai.runtime');adb('install','-r',str(a.apk));path=adb('shell','pm','path','org.motoai.runtime').strip().removeprefix('package:');assert adb('shell','sha256sum',path).split()[0]==r['apk_sha256']
 package=adb('shell','dumpsys','package','org.motoai.runtime');assert not any('DEBUGGABLE' in f.split() for f in re.findall(r'flags=\[([^\]]*)\]',package));assert 'versionName=0.3.0-dev' in package
 if adb('shell','pm','list','packages','org.motoai.runtime.test').strip():adb('uninstall','org.motoai.runtime.test')
 assert not adb('shell','pm','list','packages','org.motoai.runtime.test').strip()
 adb('shell','am','start','-n','org.motoai.runtime/.ChatActivity')
 power=adb('shell','dumpsys','power');held=[line.strip() for line in power.splitlines() if 'MotoAI:' in line and 'WAKE_LOCK' in line];assert not held,held
 notifications=adb('shell','dumpsys','notification');own=sum('NotificationRecord(' in l and 'pkg=org.motoai.runtime ' in l for l in notifications.splitlines());assert own==0
 services=adb('shell','dumpsys','activity','services','org.motoai.runtime');assert 'isForeground=true' not in services
 r.update(passed=True,non_debug_release=True,test_package_removed=True,own_held_wake_locks=0,own_active_notifications=own,own_foreground_services=False)
except Exception as e:r['error']=str(e)
finally:
 r['health_after']=read_health();(out/'controller.py').write_bytes(pathlib.Path(__file__).read_bytes());(out/'report.json').write_text(json.dumps(r,indent=2)+'\n');print(out.relative_to(ROOT));print('PASS' if r['passed'] else r['error'])
raise SystemExit(not r['passed'])
