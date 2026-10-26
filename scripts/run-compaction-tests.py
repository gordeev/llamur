#!/usr/bin/env python3
"""Frozen APK acceptance for context compaction in an isolated emulator debug package."""
import argparse,datetime,hashlib,json,pathlib,re,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk',type=pathlib.Path,required=True);p.add_argument('--tests',type=pathlib.Path,required=True)
p.add_argument('--serial',default='emulator-5580');a=p.parse_args()
assert re.fullmatch(r'emulator-\d+',a.serial)
for file in (a.apk,a.tests):assert file.resolve().is_relative_to(ROOT)
target=['adb','-s',a.serial]
def adb(*args,timeout=60):return subprocess.check_output([*target,*args],text=True,timeout=timeout)
def package(apk):
    data=subprocess.check_output([str(ROOT/'.tools/android-sdk/build-tools/36.1.0/aapt'),'dump','badging',str(apk)],text=True)
    return re.search(r"package: name='([^']+)'",data)[1]
app=package(a.apk);tests=package(a.tests)
assert app=='org.llamur.app.debug' and tests==app+'.test'
assert adb('shell','getprop','ro.boot.qemu').strip()=='1'
out=ROOT/'bench/results'/('compaction-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
report=dict(passed=False,package=app,apk_sha256=hashlib.sha256(a.apk.read_bytes()).hexdigest(),tests_sha256=hashlib.sha256(a.tests.read_bytes()).hexdigest())
original_locale=None
try:
    adb('shell','am','force-stop',app);adb('install','-r',str(a.apk),timeout=180);adb('install','-r','-t',str(a.tests),timeout=180)
    for apk,pkg,key in ((a.apk,app,'apk_sha256'),(a.tests,tests,'tests_sha256')):
        installed=adb('shell','pm','path',pkg).strip().removeprefix('package:');assert adb('shell','sha256sum',installed).split()[0]==report[key]
    fixture=next(m for m in json.loads((ROOT/'bench/models.lock.json').read_text())['models'] if m['id']=='smoke')
    source=ROOT/'models'/fixture['filename'];assert hashlib.sha256(source.read_bytes()).hexdigest()==fixture['sha256']
    staged='/data/local/tmp/llamur-compaction-'+fixture['filename'];adb('push',str(source),staged);adb('shell','run-as',app,'mkdir','-p','files/models');adb('shell','run-as',app,'cp',staged,'files/models/'+fixture['filename']);adb('shell','rm',staged)
    assert adb('shell','run-as',app,'sha256sum','files/models/'+fixture['filename']).split()[0]==fixture['sha256']
    original_locale=re.search(r'\[(.*?)\]',adb('shell','cmd','locale','get-app-locales',app,'--user','0'))[1]
    adb('shell','cmd','locale','set-app-locales',app,'--user','0','--locales','ru')
    adb('shell','run-as',app,'rm','-f','files/runtime-compaction.json',*['files/compaction-'+n+'.png' for n in ['preview','active','settings']])
    log=adb('shell','am','instrument','-w','-r','-e','scope','compaction',tests+'/org.motoai.runtime.RuntimeInstrumentation',timeout=300)
    (out/'instrumentation.txt').write_text(log);report['app']=json.loads(adb('exec-out','run-as',app,'cat','files/runtime-compaction.json'))
    assert report['app'].get('passed') and 'INSTRUMENTATION_CODE: -1' in log,report['app'].get('error',log[-1000:]);report['passed']=True
finally:
    if original_locale is not None:adb('shell','cmd','locale','set-app-locales',app,'--user','0',*(['--locales',original_locale] if original_locale else []))
    for name in ['preview','active','settings']:
        r=subprocess.run([*target,'exec-out','run-as',app,'cat','files/compaction-'+name+'.png'],capture_output=True)
        if r.returncode==0:(out/(name+'.png')).write_bytes(r.stdout)
    for name in ['CompactionChecks.kt','RuntimeInstrumentation.kt']:(out/name).write_bytes((ROOT/'app/src/androidTest/java/org/motoai/runtime'/name).read_bytes())
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');adb('shell','am','force-stop',app);print(out)
