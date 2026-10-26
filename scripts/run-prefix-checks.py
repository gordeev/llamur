#!/usr/bin/env python3
"""Bounded public model prefix-state acceptance through the ordinary APK service."""
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
from phone_model import model_sha256

ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk',type=pathlib.Path,default=ROOT/'build/gpu-app/outputs/apk/debug/app-debug.apk')
p.add_argument('--tests',type=pathlib.Path,default=ROOT/'build/gpu-app/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
p.add_argument('--compact-swa',action='store_true',help='Candidate cache loads use compact Gemma SWA; baseline keeps full cache')
p.add_argument('--transient',action='store_true',help='Candidate cache loads also enable bounded lossless CPU prefill packing; baseline stays unpacked')
p.add_argument('--model',choices=['ornith','gemma'],default='ornith')
p.add_argument('--fixture',type=pathlib.Path,default=ROOT/'bench/results/prefix-cache-host-runtime-20261026/fixture.json')
a=p.parse_args()
if a.compact_swa:p.error('Compact SWA failed unchanged NLL/top8 gates; see bench/results/compact-swa-host-rejected-20261026. No phone workload started.')
model=next(x for x in json.loads((ROOT/'bench/models.lock.json').read_text())['models'] if x['id']==a.model)
if any(not path.resolve().is_relative_to(ROOT) for path in (a.apk,a.tests,a.fixture)):p.error('Workspace artifacts only')
lease=acquire_phone_lease();health=require_phone_ready()
id='prefix-'+uuid.uuid4().hex
out=ROOT/'bench/results'/('prefix-service-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
report=dict(passed=False,run_id=id,health_before=health,
            apk_sha256=hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest(),
            test_apk_sha256=hashlib.file_digest(a.tests.open('rb'),'sha256').hexdigest(),
            fixture_sha256=hashlib.file_digest(a.fixture.open('rb'),'sha256').hexdigest(),
            model=a.model,transient_repack=a.transient,swa_full=not a.compact_swa,model_sha256=model['sha256'],
            scope='Public prefix teacher-forcing service correctness/cancellation only. No speed/context/default promotion.')
for source in ['scripts/run-prefix-checks.py','app/src/androidTest/java/org/motoai/runtime/PrefixChecks.kt']:
    (out/pathlib.Path(source).name).write_bytes((ROOT/source).read_bytes())
(out/'fixture.json').write_bytes(a.fixture.read_bytes())
def adb(*items,timeout=30,check=True):
    return subprocess.run(['adb','-d',*items],capture_output=True,text=True,timeout=timeout,check=check)
try:
    spec=json.loads(a.fixture.read_text());request=spec['request']
    if request.get('chat',True) or len(request.get('score_continuation_ids',[]))<16 or len(request.get('raw_prompt',''))<1024:
        raise RuntimeError('Expected the explicit bounded public teacher-forcing fixture')
    adb('shell','am','force-stop','org.motoai.runtime')
    adb('install','-r',str(a.apk),timeout=300);adb('install','-t','-r',str(a.tests),timeout=300)
    installed=adb('shell','pm','path','org.motoai.runtime').stdout.strip().removeprefix('package:')
    if adb('shell','sha256sum',installed).stdout.split()[0]!=report['apk_sha256']:raise RuntimeError('Installed APK differs')
    if model_sha256(model['filename'])!=model['sha256']:raise RuntimeError('Original phone model SHA differs')
    staging='/data/local/tmp/'+id+'.json'
    adb('push',str(a.fixture),staging)
    adb('shell','run-as','org.motoai.runtime','mkdir','-p','files/bench')
    adb('shell','run-as','org.motoai.runtime','cp',staging,'files/bench/'+id+'.json')
    adb('shell','rm','-f',staging)
    completed=adb('shell','am','instrument','-w','-r','-e','scope','prefix','-e','requestFile',id+'.json','-e','runId',id,'-e','model',model['filename'],'-e','transient',str(a.transient).lower(),'-e','swaFull',str(not a.compact_swa).lower(),
                  'org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation',timeout=2400)
    (out/'instrumentation.txt').write_text(completed.stdout+'\n'+completed.stderr)
    raw=adb('exec-out','run-as','org.motoai.runtime','cat','files/'+id+'-report.json',check=False)
    (out/'app-report.json').write_text(raw.stdout)
    data=json.loads(raw.stdout);report['app']=data
    report['passed']=data.get('passed') is True and 'INSTRUMENTATION_CODE: -1' in completed.stdout
    rows=adb('exec-out','run-as','org.motoai.runtime','cat','files/'+id+'.jsonl',check=False)
    (out/'observations.jsonl').write_text(rows.stdout)
    if report['passed']:
        parsed=[json.loads(line) for line in rows.stdout.splitlines()]
        if len([row for row in parsed if row.get('name')])!=7:raise RuntimeError('Incomplete public prefix trials')
        report['actual_reused_tokens']=[row['snapshot']['result']['reused_prompt_tokens'] for row in parsed
                                        if row.get('name') in ('exact_prefix_hit','restore_cancel_recovery')]
        if len(report['actual_reused_tokens'])!=2 or min(report['actual_reused_tokens'])<512:raise RuntimeError('No verified real reuse')
except Exception as error:report['passed']=False;report['error']=str(error)
finally:
    try:
        partial=adb('exec-out','run-as','org.motoai.runtime','cat','files/'+id+'.jsonl',check=False)
        if partial.stdout:(out/'observations.jsonl').write_text(partial.stdout)
        (out/'exit-info.txt').write_text(adb('shell','dumpsys','activity','exit-info','org.motoai.runtime',check=False).stdout)
        adb('shell','am','force-stop','org.motoai.runtime')
    except Exception as error:report['cleanup_error']=str(error);report['passed']=False
    if not report['passed']:
        try:
            fallback=subprocess.run([sys.executable,'scripts/run-runtime-tests.py','--apk',str(a.apk),'--tests',str(a.tests),'--require-ephemeral'],
                                    cwd=ROOT,capture_output=True,text=True,timeout=720,**lease.child_options())
            (out/'cpu-recovery.log').write_text(fallback.stdout+'\n'+fallback.stderr)
            report['cpu_recovery_passed']=fallback.returncode==0
        except Exception as error:report['cpu_recovery_error']=str(error)
    report['health_after']=read_health()
    if report['health_after'].get('boot_id_sha256')!=health.get('boot_id_sha256'):report['passed']=False
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Results:',out.relative_to(ROOT),flush=True);print('PASS' if report['passed'] else 'FAIL',flush=True)
raise SystemExit(0 if report['passed'] else 1)
