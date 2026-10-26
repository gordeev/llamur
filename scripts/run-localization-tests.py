#!/usr/bin/env python3
"""RU/EN UI acceptance on an explicit public emulator; restores locale and font settings."""
import argparse,datetime,hashlib,json,pathlib,re,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=pathlib.Path,required=True);p.add_argument('--tests',type=pathlib.Path,required=True);p.add_argument('--serial',default='emulator-5580');p.add_argument('--font-scale',choices=['1.0','1.3'],default='1.0');a=p.parse_args()
assert re.fullmatch('emulator-[0-9]+',a.serial)
for source in [a.apk,a.tests]:assert source.resolve().is_relative_to(ROOT)
target=['adb','-s',a.serial]
def adb(*args,timeout=60):return subprocess.check_output([*target,*args],text=True,timeout=timeout)
def package(apk):
 data=subprocess.check_output([str(ROOT/'.tools/android-sdk/build-tools/36.1.0/aapt'),'dump','badging',str(apk)],text=True)
 return re.search(r"package: name='([^']+)'",data)[1]
app=package(a.apk);tests=package(a.tests);assert app=='org.llamur.app.debug' and tests==app+'.test';assert adb('shell','getprop','ro.boot.qemu').strip()=='1'
out=ROOT/'bench/results'/('localization-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir();sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
r=dict(passed=False,apk_sha256=sha(a.apk),test_apk_sha256=sha(a.tests),font_scale=a.font_scale);locale=None;font=None
names=['ru-actions-light','en-actions-dark','ru-storage-light','ru-expiry-light','ru-language-light','en-settings-light','en-storage-light','en-storage-dark','en-language-dark','ru-settings-light','ru-models-light']
names += ['layout-'+theme+'-'+name for theme in ['light','dark'] for name in ['home','chat','context','context-bottom','settings','settings-bottom','storage','theme','language','about','models','model-storage','hf','hf-bottom','profile','profile-bottom','api','api-bottom','providers','provider-edit','provider-edit-bottom','model-actions']]
try:
 adb('shell','am','force-stop',app);adb('install','-r',str(a.apk),timeout=180);adb('install','-r','-t',str(a.tests),timeout=180)
 for pkg,digest in [(app,r['apk_sha256']),(tests,r['test_apk_sha256'])]:
  installed=adb('shell','pm','path',pkg).strip().removeprefix('package:');assert adb('shell','sha256sum',installed).split()[0]==digest
 locale=re.search(r'\[(.*?)\]',adb('shell','cmd','locale','get-app-locales',app,'--user','0'))[1];font=adb('shell','settings','get','system','font_scale').strip()
 adb('shell','cmd','locale','set-app-locales',app,'--user','0','--locales','ru');adb('shell','settings','put','system','font_scale',a.font_scale)
 adb('shell','run-as',app,'rm','-f','files/runtime-localization.json',*['files/localization-'+n+'.png' for n in names])
 log=adb('shell','am','instrument','-w','-r','-e','scope','localization',tests+'/org.motoai.runtime.RuntimeInstrumentation',timeout=240);(out/'instrumentation.txt').write_text(log)
 r['app']=json.loads(adb('exec-out','run-as',app,'cat','files/runtime-localization.json'));assert r['app'].get('passed') and 'INSTRUMENTATION_CODE: -1' in log,r['app'].get('error',log[-1000:]);r['passed']=True
finally:
 for name in names:
  png=subprocess.run([*target,'exec-out','run-as',app,'cat','files/localization-'+name+'.png'],capture_output=True)
  if png.returncode==0:(out/(name+'.png')).write_bytes(png.stdout)
 (out/'report.json').write_text(json.dumps(r,indent=2)+'\n');(out/'LocalizationChecks.kt').write_bytes((ROOT/'app/src/androidTest/java/org/motoai/runtime/LocalizationChecks.kt').read_bytes())
 adb('shell','am','force-stop',app)
 if locale is not None:adb('shell','cmd','locale','set-app-locales',app,'--user','0',*(['--locales',locale] if locale else []))
 if font is not None:
  if font=='null':adb('shell','settings','delete','system','font_scale')
  else:adb('shell','settings','put','system','font_scale',font)
 print(out)
