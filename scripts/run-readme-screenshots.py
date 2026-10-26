#!/usr/bin/env python3
"""Capture actual English app UI on the public emulator with scripted, non-private demo data."""
import argparse,datetime,hashlib,json,pathlib,re,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=pathlib.Path,required=True);p.add_argument('--tests',type=pathlib.Path,required=True);p.add_argument('--serial',default='emulator-5580');a=p.parse_args()
assert re.fullmatch('emulator-[0-9]+',a.serial)
app='org.llamur.app.debug';test=app+'.test';target=['adb','-s',a.serial]
def adb(*args,timeout=60):return subprocess.check_output([*target,*args],text=True,timeout=timeout)
assert adb('shell','getprop','ro.boot.qemu').strip()=='1'
for apk,package in [(a.apk,app),(a.tests,test)]:
 assert apk.resolve().is_relative_to(ROOT)
 badging=subprocess.check_output([str(ROOT/'.tools/android-sdk/build-tools/36.1.0/aapt'),'dump','badging',str(apk)],text=True)
 assert re.search(r"package: name='([^']+)'",badging)[1]==package
out=ROOT/'bench/results'/('readme-ui-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
locale=None
report=dict(passed=False,apk_sha256=hashlib.sha256(a.apk.read_bytes()).hexdigest(),tests_sha256=hashlib.sha256(a.tests.read_bytes()).hexdigest(),scope='Real English Android UI, public scripted loopback conversations, no paid/model inference claim.')
try:
 adb('shell','am','force-stop',app);adb('install','-r',str(a.apk),timeout=180);adb('install','-t','-r',str(a.tests),timeout=180)
 locale=re.search(r'\[(.*?)\]',adb('shell','cmd','locale','get-app-locales',app,'--user','0'))[1]
 adb('shell','cmd','locale','set-app-locales',app,'--user','0','--locales','en')
 for theme,name in [('2','dark'),('1','light')]:
  adb('shell','am','force-stop',app)
  adb('shell','run-as',app,'rm','-f','files/runtime-readme.json',*['files/readme-'+n+'.png' for n in ['home','chat','settings','about','storage','hugging-face']])
  log=adb('shell','am','instrument','-w','-r','-e','scope','readme-screenshots','-e','theme',theme,test+'/org.motoai.runtime.RuntimeInstrumentation',timeout=180);(out/(name+'-instrumentation.txt')).write_text(log)
  result=json.loads(adb('exec-out','run-as',app,'cat','files/runtime-readme.json'));report[name]=result;assert result.get('passed') and 'INSTRUMENTATION_CODE: -1' in log,result
  for screen in ['home','chat','settings','about','storage','hugging-face']:
   data=subprocess.check_output([*target,'exec-out','run-as',app,'cat','files/readme-'+screen+'.png']);assert data.startswith(b'\x89PNG');(out/(screen+'-'+name+'.png')).write_bytes(data)
 report['passed']=True
finally:
 adb('shell','am','force-stop',app)
 if locale is not None:adb('shell','cmd','locale','set-app-locales',app,'--user','0',*(['--locales',locale] if locale else []))
 (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(out)
