#!/usr/bin/env python3
"""Preview actual app-UID no_alloc CPU/GPU buffers before any large-model load."""
import argparse
import datetime
import hashlib
import json
import pathlib
import subprocess
import sys
import uuid
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready, read_health

ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk',type=pathlib.Path,required=True)
p.add_argument('--tests',type=pathlib.Path,required=True)
p.add_argument('--backend',choices=['cpu','opencl','vulkan'],default='opencl')
p.add_argument('--compact-swa',action='store_true')
p.add_argument('--model',default='Ornith-1.5-9B-Q4_K_M.gguf')
a=p.parse_args()
if a.compact_swa:p.error('Compact SWA failed unchanged NLL/top8 gates; see bench/results/compact-swa-host-rejected-20261026. No phone workload started.')
if any(not path.resolve().is_relative_to(ROOT) for path in (a.apk,a.tests)):p.error('Workspace APKs only')
if a.model not in ('all','Ornith-1.5-9B-Q4_K_M.gguf','gemma-4-E4B-it-Q4_K_M.gguf'):p.error('Locked research models only')
lease=acquire_phone_lease();health=require_phone_ready();id='plan-'+uuid.uuid4().hex
out=ROOT/'bench/results'/('gpu-plan-'+a.backend+'-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
report=dict(passed=False,backend=a.backend,model=a.model,swa_full=not a.compact_swa,health_before=health,
            apk_sha256=hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest(),
            test_apk_sha256=hashlib.file_digest(a.tests.open('rb'),'sha256').hexdigest(),
            scope='Actual app-UID no_alloc buffer selection only; no large weight allocation, inference or speed acceptance')
(out/'run-gpu-plan.py').write_bytes(pathlib.Path(__file__).read_bytes())
def adb(*items,timeout=30,check=True):
    return subprocess.run(['adb','-d',*items],capture_output=True,text=True,timeout=timeout,check=check)
try:
    adb('shell','am','force-stop','org.motoai.runtime')
    adb('install','-r',str(a.apk),timeout=90);adb('install','-t','-r',str(a.tests),timeout=90)
    installed=adb('shell','pm','path','org.motoai.runtime').stdout.strip().removeprefix('package:')
    if adb('shell','sha256sum',installed).stdout.split()[0]!=report['apk_sha256']:raise RuntimeError('Installed identity differs')
    models = ['Ornith-1.5-9B-Q4_K_M.gguf','gemma-4-E4B-it-Q4_K_M.gguf'] if a.model=='all' else [a.model]
    report['model_plans']={};report['passed']=True
    for index,model in enumerate(models):
        run_id=id+'-'+str(index)
        completed=adb('shell','am','instrument','-w','-r','-e','scope','gpu-plan','-e','backend',a.backend,
                      '-e','model',model,'-e','swaFull',str(not a.compact_swa).lower(),'-e','runId',run_id,'org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation',timeout=240)
        (out/(model+'-instrumentation.txt')).write_text(completed.stdout+'\n'+completed.stderr)
        raw=adb('exec-out','run-as','org.motoai.runtime','cat','files/'+run_id+'-report.json',check=False)
        (out/(model+'-app-report.json')).write_text(raw.stdout)
        data=json.loads(raw.stdout);report['model_plans'][model]=data
        report['passed'] &= data.get('passed') is True and 'INSTRUMENTATION_CODE: -1' in completed.stdout
        if not report['passed']:raise RuntimeError('Model memory plan failed: '+model)
        print(model,'metadata plan PASS',flush=True)

except Exception as error:report['passed']=False;report['error']=str(error)
finally:
    try:
        (out/'exit-info.txt').write_text(adb('shell','dumpsys','activity','exit-info','org.motoai.runtime',check=False).stdout)
        adb('shell','am','force-stop','org.motoai.runtime')
        recovery=subprocess.run([sys.executable,'scripts/run-runtime-tests.py','--apk',str(a.apk),'--tests',str(a.tests),'--require-ephemeral'],
                                cwd=ROOT,capture_output=True,text=True,timeout=720,**lease.child_options())
        (out/'cpu-recovery.log').write_text(recovery.stdout+'\n'+recovery.stderr)
        report['cpu_recovery_passed']=recovery.returncode==0
        if not report['cpu_recovery_passed']:report['passed']=False
    except Exception as error:report['cleanup_error']=str(error);report['passed']=False
    report['health_after']=read_health()
    if report['health_after'].get('boot_id_sha256')!=health.get('boot_id_sha256'):report['passed']=False
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print('Results:',out.relative_to(ROOT),flush=True)
    print('PASS' if report['passed'] else 'FAIL',flush=True)
raise SystemExit(0 if report['passed'] else 1)
