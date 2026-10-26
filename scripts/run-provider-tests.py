#!/usr/bin/env python3
"""Frozen APK provider fixtures; no paid provider calls or GGUF inference."""
import argparse,datetime,hashlib,json,pathlib,re,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=pathlib.Path,required=True);p.add_argument('--tests',type=pathlib.Path,required=True);p.add_argument('--phone',action='store_true');p.add_argument('--background',action='store_true');p.add_argument('--theme',choices=['1','2']);p.add_argument('--serial',default='emulator-5580');p.add_argument('--notifications',choices=['keep','grant','deny'],default='keep');p.add_argument('--font-scale',choices=['1.0','1.3']);a=p.parse_args()
if a.phone and a.font_scale:p.error('Font override is limited to the public emulator')
if a.phone and a.notifications!='keep':p.error('Explicit notification override is limited to the public emulator')
for f in [a.apk,a.tests]:
 if not f.resolve().is_relative_to(ROOT):p.error('Workspace frozen APK required')
def apk_package(path):
 data=subprocess.check_output([str(ROOT/'.tools/android-sdk/build-tools/36.1.0/aapt'),'dump','badging',str(path)],text=True)
 return re.search(r"package: name='([^']+)'",data)[1]
app_package=apk_package(a.apk);test_package=apk_package(a.tests)
if a.phone and not app_package.endswith('.debug'):p.error('Phone fixtures require a separate debug applicationId')
if test_package!=app_package+'.test':p.error('Test package does not match target APK')
lease=None;health=None;original_notifications=None;original_font=None;original_locale=None
if a.phone:
 from phone_lease import acquire_phone_lease
 from phone_health import require_phone_ready
 lease=acquire_phone_lease();health=require_phone_ready();target=['-d']
else:
 if not re.fullmatch('emulator-[0-9]+',a.serial):p.error('Explicit emulator required')
 target=['-s',a.serial]
out=ROOT/'bench/results'/('providers-'+('phone' if a.phone else 'emulator')+'-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
sha=lambda f:hashlib.file_digest(f.open('rb'),'sha256').hexdigest()
r=dict(passed=False,apk_sha256=sha(a.apk),test_apk_sha256=sha(a.tests),health_before=health,scope='Public loopback protocol/credential/service fixtures. No real cloud account chat acceptance, model weights, paid requests or speed claim.')
def adb(*v,timeout=60):return subprocess.run(['adb',*target,*v],capture_output=True,text=True,check=True,timeout=timeout).stdout
try:
 if not a.phone:
  assert adb('shell','getprop','ro.boot.qemu').strip()=='1';r['page_size']=int(adb('shell','getconf','PAGE_SIZE').strip());assert r['page_size']==16384
 adb('shell','am','force-stop',app_package);adb('install','-r',str(a.apk),timeout=300);adb('install','-t','-r',str(a.tests),timeout=300)
 for pkg,expected in [(app_package,r['apk_sha256']),(test_package,r['test_apk_sha256'])]:
  path=adb('shell','pm','path',pkg).strip().removeprefix('package:');assert adb('shell','sha256sum',path).split()[0]==expected
 if not a.phone and app_package.endswith('.debug'):
  original_locale=re.search(r'\[(.*?)\]',adb('shell','cmd','locale','get-app-locales',app_package,'--user','0'))[1]
  adb('shell','cmd','locale','set-app-locales',app_package,'--user','0','--locales','ru')
 if a.font_scale:
  original_font=adb('shell','settings','get','system','font_scale').strip();r['font_scale_before']=original_font;r['font_scale_requested']=a.font_scale;adb('shell','settings','put','system','font_scale',a.font_scale)
 if a.notifications!='keep':
  permission=re.search(r'android\.permission\.POST_NOTIFICATIONS: granted=(true|false)',adb('shell','dumpsys','package',app_package));assert permission
  original_notifications=permission[1]=='true';r['notifications_before']=original_notifications
  adb('shell','pm','grant' if a.notifications=='grant' else 'revoke',app_package,'android.permission.POST_NOTIFICATIONS')
  r['notifications_requested']=a.notifications
 adb('shell','run-as',app_package,'rm','-f','files/runtime-providers-tests.json')
 extras=[]
 if a.background:extras+=['-e','providerBackground','true']
 if a.theme:extras+=['-e','providerTheme',a.theme]
 log=adb('shell','am','instrument','-w','-r','-e','scope','providers',*extras,test_package+'/org.motoai.runtime.RuntimeInstrumentation',timeout=420);(out/'instrumentation.txt').write_text(log)
 raw=adb('exec-out','run-as',app_package,'cat','files/runtime-providers-tests.json');(out/'app-report.json').write_text(raw);assert raw.lstrip().startswith('{'),'Instrumentation crashed before writing a report; inspect instrumentation.txt and crash log';r['app']=json.loads(raw)
 assert 'INSTRUMENTATION_CODE: -1' in log and r['app'].get('passed') is True,r['app'].get('error','Provider instrumentation failed')
 r['passed']=True
 if not a.phone:
  for name in ['empty','drawer','answer','settings','api']:
   png=subprocess.run(['adb',*target,'exec-out','run-as',app_package,'cat','files/provider-ui-'+name+'.png'],capture_output=True,check=True).stdout
   (out/(name+'.png')).write_bytes(png)
except Exception as e:
 r['error']=str(e)
 if not a.phone:
  try:
   png=subprocess.run(['adb',*target,'exec-out','run-as',app_package,'cat','files/provider-ui-failure.png'],capture_output=True,check=True).stdout;(out/'failure.png').write_bytes(png)
   diagnostic=adb('exec-out','run-as',app_package,'cat','files/provider-notification-diagnostic.json');(out/'notification-diagnostic.json').write_text(diagnostic)
  except Exception:pass
finally:
 if original_locale is not None:
  try:adb('shell','cmd','locale','set-app-locales',app_package,'--user','0',*(['--locales',original_locale] if original_locale else []))
  except Exception:r['locale_restore_failed']=True
 if original_font is not None:
  try:
   if original_font=='null':adb('shell','settings','delete','system','font_scale')
   else:adb('shell','settings','put','system','font_scale',original_font)
   r['font_scale_restored']=True
  except Exception as e:r['font_restore_error']=str(e);r['passed']=False
 if original_notifications is not None:
  try:adb('shell','pm','grant' if original_notifications else 'revoke',app_package,'android.permission.POST_NOTIFICATIONS');r['notifications_restored']=True
  except Exception as e:r['permission_restore_error']=str(e);r['passed']=False
 try:adb('shell','am','force-stop',app_package)
 except Exception as e:r['cleanup_error']=str(e);r['passed']=False
 if a.phone:
  from phone_health import read_health
  r['health_after']=read_health()
 (out/'controller.py').write_bytes(pathlib.Path(__file__).read_bytes());(out/'report.json').write_text(json.dumps(r,indent=2)+'\n');print('Results:',out.relative_to(ROOT),flush=True);print('PASS' if r['passed'] else r.get('error','FAIL'),flush=True)
raise SystemExit(not r['passed'])
