#!/usr/bin/env python3
"""Real DocumentsUI persistent grant across an emulator reboot, frozen APKs only."""
import argparse,datetime,json,pathlib,subprocess,time
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=pathlib.Path,required=True);p.add_argument('--tests',type=pathlib.Path,required=True);a=p.parse_args()
args=['python3',str(ROOT/'scripts/run-product-tests.py'),'--apk',str(a.apk),'--tests',str(a.tests)]
out=ROOT/'bench/results'/('saf-reboot-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
r=dict(passed=False,scope='Public tiny GGUF; real DocumentsUI grant, settled persistence then emulator reboot, identical installed packages, mmap output identity and source-preserving disconnect')
def adb(*items):return subprocess.run(['adb','-s','emulator-5580',*items],capture_output=True,text=True,timeout=30,check=True)
def stage(name,reuse=False):
 result=subprocess.run(args+['--scope',name]+(['--reuse-installation'] if reuse else []),capture_output=True,text=True,timeout=240)
 (out/(name+'.txt')).write_text(result.stdout+result.stderr)
 if result.returncode:raise RuntimeError(result.stdout+result.stderr)
 folder=ROOT/result.stdout.split('Results: ')[1].splitlines()[0];r[name]=str(folder.relative_to(ROOT));return folder
try:
 if adb('shell','getprop','ro.boot.qemu').stdout.strip()!='1':raise RuntimeError('Not an emulator')
 stage('saf-ui')
 # Android batches URI-grant persistence; an immediate low-level adb reboot is
 # not an ordinary user shutdown. Allow its asynchronous disk write to settle.
 time.sleep(15)
 (out/'uri-grants-before.txt').write_text(adb('shell','dumpsys','uri_grants').stdout)
 r['boot_id_before']=adb('shell','cat','/proc/sys/kernel/random/boot_id').stdout.strip()
 adb('reboot');end=time.monotonic()+150
 while time.monotonic()<end:
  try:
   boot=adb('shell','cat','/proc/sys/kernel/random/boot_id').stdout.strip()
   if boot and boot!=r['boot_id_before'] and adb('shell','getprop','sys.boot_completed').stdout.strip()=='1' and adb('shell','pm','path','org.motoai.runtime').stdout.startswith('package:'):
    r['boot_id_after']=boot;break
  except subprocess.CalledProcessError:pass
  time.sleep(2)
 else:raise RuntimeError('Emulator boot deadline')
 adb('shell','input','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard')
 (out/'uri-grants-after.txt').write_text(adb('shell','dumpsys','uri_grants').stdout)
 stage('saf-resume',True);r['passed']=True
except Exception as error:r['error']=str(error)
finally:
 (out/'controller.py').write_bytes(pathlib.Path(__file__).read_bytes());(out/'report.json').write_text(json.dumps(r,indent=2)+'\n');print('Results:',out.relative_to(ROOT));print('PASS' if r['passed'] else r['error'])
raise SystemExit(0 if r['passed'] else 1)
