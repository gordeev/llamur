#!/usr/bin/env python3
"""Same-APK CPU/OpenCL locked-model teacher-forcing gate; no speed promotion."""
import argparse
import copy
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import sys
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready, read_health
from phone_model import model_sha256
from weight_buffer_checks import check_result, compare_results
from gpu_checks import check_gpu_result

ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk',type=pathlib.Path,required=True)
p.add_argument('--tests',type=pathlib.Path,required=True)
p.add_argument('--fixture',default='teacher-forcing-code-v1')
p.add_argument('--layers',type=int,nargs='+',default=[8,16,24])
a=p.parse_args()
if any(not x.resolve().is_relative_to(ROOT) for x in (a.apk,a.tests)) or any(n not in (4,8,16,24,128) for n in a.layers):p.error('Workspace APKs and bounded declared layer choices required')
lease=acquire_phone_lease();health=require_phone_ready()
out=ROOT/'bench/results'/('gpu-model-screen-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
fixture=ROOT/'bench/fixtures/generated/ornith'/(a.fixture+'.json');golden=json.loads(fixture.with_suffix('.golden.json').read_text())
raw=fixture.read_bytes()
if hashlib.sha256(raw).hexdigest()!=golden['request_sha256']:raise RuntimeError('Frozen fixture differs')
source=json.loads(raw)
if not source['request'].get('score_continuation_ids'):raise RuntimeError('Teacher-forcing fixture required')
report=dict(passed=False,health_before=health,apk_sha256=hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest(),trials=[],
            scope='Same original Ornith/same-APK CPU vs OpenCL teacher-forcing screening. Not speed/completed-task/filled-context acceptance.')
for path in ['scripts/run-gpu-model-screen.py','scripts/gpu_checks.py','scripts/weight_buffer_checks.py','bench/logit-gates.json']:
    (out/pathlib.Path(path).name).write_bytes((ROOT/path).read_bytes())
(out/'fixture.json').write_bytes(raw);(out/'golden.json').write_bytes(fixture.with_suffix('.golden.json').read_bytes())
def run(label,config):
    spec=copy.deepcopy(source);spec['config']=config
    request=out/(label+'.json');request.write_text(json.dumps(spec,indent=2)+'\n')
    completed=subprocess.run([sys.executable,'scripts/run-app-smoke.py','ornith','--service','--apk',str(a.apk),
        '--request',str(request.relative_to(ROOT)),'--record-device','--timeout','900'],cwd=ROOT,capture_output=True,text=True,timeout=1080,**lease.child_options())
    (out/(label+'.log')).write_text(completed.stdout+'\n'+completed.stderr)
    match=re.search(r'^Results: (bench/results/[^\n]+)$',completed.stdout,re.MULTILINE)
    if not match:raise RuntimeError('No raw model artifact: '+label)
    artifact=ROOT/match[1];data=json.loads((artifact/'result.json').read_text());manifest=json.loads((artifact/'manifest.json').read_text())
    row=dict(name=label,artifact=str(artifact.relative_to(ROOT)),passed=False);report['trials'].append(row)
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    if completed.returncode or manifest['apk_sha256']!=report['apk_sha256'] or manifest['request_spec']!=spec:
        raise RuntimeError('Model trial failed or identity differs: '+label)
    return row,data
try:
    if model_sha256('Ornith-1.5-9B-Q4_K_M.gguf')!=golden['model_sha256']:raise RuntimeError('Original phone model SHA differs')
    subprocess.run(['adb','-d','shell','am','force-stop','org.motoai.runtime'],check=True,capture_output=True)
    subprocess.run(['adb','-d','install','-r',str(a.apk)],check=True,capture_output=True,timeout=90)
    subprocess.run(['adb','-d','install','-t','-r',str(a.tests)],check=True,capture_output=True,timeout=90)
    base=dict(backend='cpu',gpu_layers=0,context=4096,threads=4,batch=512,micro_batch=128,load_mode='mmap',mmap_prefault=False,weight_buffers='native',transient_repack=False)
    row,data=run('cpu',base);reference=check_result(data,golden,'native',source['request'],mmap_prefault=False,transient_repack=False);row['passed']=True
    gates=json.loads((ROOT/'bench/logit-gates.json').read_text())
    for n in a.layers:
        config=dict(base,backend='opencl',gpu_layers=n)
        row,data=run('opencl-'+str(n),config)
        candidate=check_gpu_result(data,golden,config,source['request'])
        row['comparison']=compare_results(reference,candidate,gates,require_ids=True)
        row['passed']=row['comparison']['numeric_passed']
        if not row['passed']:raise RuntimeError('Unchanged numeric gate failed: '+row['name'])
    report['passed']=True
except Exception as error:report['error']=str(error)
finally:
    subprocess.run(['adb','-d','shell','am','force-stop','org.motoai.runtime'],capture_output=True)
    if not report['passed']:
        try:
            recovery=subprocess.run([sys.executable,'scripts/run-runtime-tests.py','--apk',str(a.apk),'--tests',str(a.tests),'--require-ephemeral'],
                cwd=ROOT,capture_output=True,text=True,timeout=720,**lease.child_options())
            (out/'cpu-recovery.log').write_text(recovery.stdout+'\n'+recovery.stderr)
            report['cpu_recovery_passed']=recovery.returncode==0
        except Exception as error:report['cpu_recovery_error']=str(error)
    report['health_after']=read_health()
    if report['health_after'].get('boot_id_sha256')!=health.get('boot_id_sha256'):report['passed']=False
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Results:',out.relative_to(ROOT),flush=True);print('PASS' if report['passed'] else 'FAIL',flush=True)
raise SystemExit(0 if report['passed'] else 1)
