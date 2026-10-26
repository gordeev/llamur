#!/usr/bin/env python3
"""Provider/files/settings regression on an explicit emulator and a separate debug app."""
import argparse,datetime,hashlib,json,pathlib,re,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk',type=pathlib.Path,required=True)
p.add_argument('--tests',type=pathlib.Path,required=True)
p.add_argument('--serial',default='emulator-5580')
p.add_argument('--live-catalogue',action='store_true')
a=p.parse_args()
assert re.fullmatch(r'emulator-\d+',a.serial)
target=['adb','-s',a.serial]
def adb(*args,timeout=60):return subprocess.check_output([*target,*args],text=True,timeout=timeout)
def package(apk):
    data=subprocess.check_output([str(ROOT/'.tools/android-sdk/build-tools/36.1.0/aapt'),'dump','badging',str(apk)],text=True)
    return re.search(r"package: name='([^']+)'",data)[1]
app=package(a.apk);tests=package(a.tests)
assert app=='org.llamur.app.debug' and tests==app+'.test'
assert adb('shell','getprop','ro.boot.qemu').strip()=='1'
out=ROOT/'bench/results'/('provider-updates-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
report=dict(passed=False,package=app,apk_sha256=hashlib.sha256(a.apk.read_bytes()).hexdigest(),tests_sha256=hashlib.sha256(a.tests.read_bytes()).hexdigest(),live_catalogue=a.live_catalogue)
original_locale=None
try:
    adb('shell','am','force-stop',app)
    adb('install','-r',str(a.apk),timeout=180);adb('install','-r','-t',str(a.tests),timeout=180)
    original_locale=re.search(r'\[(.*?)\]',adb('shell','cmd','locale','get-app-locales',app,'--user','0'))[1]
    adb('shell','cmd','locale','set-app-locales',app,'--user','0','--locales','ru')
    adb('shell','run-as',app,'rm','-f','files/runtime-provider-updates.json',*['files/provider-update-'+n+'.png' for n in ['settings','models','hf','providers','provider-edit','files','file-picker','catalogue','http-warning','http-editor','model-actions']])
    log=adb('shell','am','instrument','-w','-r','-e','scope','provider-updates','-e','liveCatalogue',str(a.live_catalogue).lower(),tests+'/org.motoai.runtime.RuntimeInstrumentation',timeout=240)
    (out/'instrumentation.txt').write_text(log)
    app_report=json.loads(adb('exec-out','run-as',app,'cat','files/runtime-provider-updates.json'))
    report['app']=app_report
    assert app_report.get('passed') and 'INSTRUMENTATION_CODE: -1' in log,app_report.get('error',log[-1000:])
    report['passed']=True
finally:
    if original_locale is not None:adb('shell','cmd','locale','set-app-locales',app,'--user','0',*(['--locales',original_locale] if original_locale else []))
    for name in ['settings','models','hf','providers','provider-edit','files','file-picker','catalogue','http-warning','http-editor','model-actions']:
        result=subprocess.run([*target,'exec-out','run-as',app,'cat','files/provider-update-'+name+'.png'],capture_output=True)
        if result.returncode==0:(out/(name+'.png')).write_bytes(result.stdout)
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    adb('shell','am','force-stop',app)
    print(out)
