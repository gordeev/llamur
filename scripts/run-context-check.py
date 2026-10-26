#!/usr/bin/env python3
"""One bounded filled-context retrieval through the ordinary app service.

A completed retrieval qualifies only this fixture/profile/point; metadata planning
and mechanical completion never imply interactive speed or arbitrary QA quality.
"""
import argparse
import copy
import datetime
import hashlib
import importlib.util
import json
import pathlib
import re
import subprocess
import sys
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready, read_health
from phone_model import model_sha256
from weight_buffer_checks import check_result

ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('model',choices=['ornith','gemma'])
p.add_argument('context',type=int,choices=[4096,16384,32768])
p.add_argument('--apk',type=pathlib.Path,required=True)
p.add_argument('--tests',type=pathlib.Path,required=True)
p.add_argument('--reuse-installation',action='store_true',help='Require exact installed APK+test hashes instead of reinstalling')
p.add_argument('--transient',action='store_true')
p.add_argument('--compact-swa',action='store_true')
p.add_argument('--quality-gate',type=pathlib.Path)
p.add_argument('--timeout',type=int,default=3600)
a=p.parse_args()
if a.compact_swa:p.error('Compact SWA failed unchanged NLL/top8 gates; see bench/results/compact-swa-host-rejected-20261026. No phone workload started.')
if not 600<=a.timeout<=7200 or any(not x.resolve().is_relative_to(ROOT) for x in (a.apk,a.tests)):
    p.error('Workspace APKs and explicit600..7200s bounded timeout required')
if (a.transient or a.compact_swa) and a.model=='gemma':
    if a.quality_gate is None:p.error('Gemma transient needs a passed same-APK whole-model quality gate')
    gate=json.loads(a.quality_gate.read_text())
    if not gate.get('passed') or gate.get('model')!='gemma' or gate.get('transient_repack')!=a.transient or gate.get('swa_full',True)==a.compact_swa or gate.get('apk_sha256')!=hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest():
        p.error('Gemma transient quality gate does not bind this exact APK/profile')
lease=acquire_phone_lease();initial=require_phone_ready()
model=next(m for m in json.loads((ROOT/'bench/models.lock.json').read_text())['models'] if m['id']==a.model)
fixture=ROOT/'bench/fixtures/generated'/a.model/f'context-{a.context}-three-depth-v1.json'
golden=json.loads(fixture.with_suffix('.golden.json').read_text());source=json.loads(fixture.read_text())
if hashlib.sha256(fixture.read_bytes()).hexdigest()!=golden['request_sha256']:raise RuntimeError('Frozen input differs')
if len(golden['expected']['prompt_ids'])+source['request']['max_tokens']!=a.context:raise RuntimeError('Point is not actually filled to its declared budget')
out=ROOT/'bench/results'/('context-'+a.model+'-'+str(a.context)+'-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
report=dict(passed=False,health_before=initial,model=a.model,context=a.context,
    apk_sha256=hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest(),timeout_seconds=a.timeout,
    scope='One actual near-full prompt and completed three-depth retrieval. Not endurance/speed/default/general-context quality acceptance.')
for name in ['scripts/run-context-check.py','scripts/evaluate-code.py','scripts/weight_buffer_checks.py']:
    (out/pathlib.Path(name).name).write_bytes((ROOT/name).read_bytes())
(out/'golden.json').write_bytes(fixture.with_suffix('.golden.json').read_bytes())
spec=copy.deepcopy(source);spec['config']['transient_repack']=a.transient;spec['config']['swa_full']=not a.compact_swa
request=out/'request.json';request.write_text(json.dumps(spec,ensure_ascii=False,indent=2)+'\n')
try:
    subprocess.run(['adb','-d','shell','am','force-stop','org.motoai.runtime'],capture_output=True,check=True)
    if not a.reuse_installation:
        subprocess.run(['adb','-d','install','-r',str(a.apk)],capture_output=True,check=True,timeout=300)
        subprocess.run(['adb','-d','install','-t','-r',str(a.tests)],capture_output=True,check=True,timeout=300)
    for package,path in [('org.motoai.runtime',a.apk),('org.motoai.runtime.test',a.tests)]:
        installed=subprocess.check_output(['adb','-d','shell','pm','path',package],text=True,timeout=30).strip().removeprefix('package:')
        actual=subprocess.check_output(['adb','-d','shell','sha256sum',installed],text=True,timeout=30).split()[0]
        if actual!=hashlib.file_digest(path.open('rb'),'sha256').hexdigest():raise RuntimeError('Installed app/test APK differs; no model workload started')
    report['reused_verified_installation']=a.reuse_installation
    if model_sha256(model['filename'])!=model['sha256']:raise RuntimeError('Original phone GGUF SHA differs')
    run=subprocess.run([sys.executable,'scripts/run-app-smoke.py',a.model,'--service','--apk',str(a.apk),
        '--request',str(request.relative_to(ROOT)),'--record-device','--timeout',str(a.timeout)],
        cwd=ROOT,capture_output=True,text=True,timeout=a.timeout+240,**lease.child_options())
    (out/'trial.log').write_text(run.stdout+'\n'+run.stderr)
    match=re.search(r'^Results: (bench/results/[^\n]+)$',run.stdout,re.MULTILINE)
    if not match:raise RuntimeError('No model artifact; inspect retained timeout/failure log')
    artifact=ROOT/match[1];report['artifact']=str(artifact.relative_to(ROOT))
    manifest=json.loads((artifact/'manifest.json').read_text())
    if manifest['apk_sha256']!=report['apk_sha256'] or manifest['request_spec']!=spec:raise RuntimeError('Trial identity differs')
    if run.returncode:
        progress=artifact/'last-progress.json'
        if progress.exists():report['last_progress']=json.loads(progress.read_text())
        report['filled_prompt_observed_before_failure']=report.get('last_progress',{}).get('phase')=='decode'
        raise RuntimeError('Context workload failed; no retry/pruning or context reduction')
    data=json.loads((artifact/'result.json').read_text())
    result=check_result(data,golden,'native',source['request'],mmap_prefault=False,transient_repack=a.transient)
    if data['load'].get('swa_full') is not (not a.compact_swa):raise RuntimeError('SWA mode differs')
    if data['load']['context']!=a.context or result['prompt_tokens']!=a.context-512:raise RuntimeError('Context geometry differs')
    module=importlib.util.spec_from_file_location('code_evaluator',ROOT/'scripts/evaluate-code.py');evaluator=importlib.util.module_from_spec(module);module.loader.exec_module(evaluator)
    answer,channel=evaluator.final_answer(data)
    expected=' '.join(golden['expected_identifiers'])
    report.update(prompt_tokens=result['prompt_tokens'],generated_tokens=result['generated_tokens'],finish_reason=result['finish_reason'],
        prefill_us=result['prefill_us'],decode_us=result['decode_us'],ttft_us=result['first_target_token_us'],
        completed_pss_kib=data.get('completed_pss_kib'),process_status=data.get('process_status'),
        quality=dict(answer_channel=channel,exact_ordered_identifiers=answer.strip()==expected,completed=result['finish_reason']=='eog'),
        actual_evaluated_tokens=result['prompt_tokens']+result['generated_tokens'])
    report['passed']=report['quality']['exact_ordered_identifiers'] and report['quality']['completed']
    if not report['passed']:report['error']='Incomplete or incorrect final retrieval answer; preserved all input/thinking/output'
except Exception as error:report['error']=str(error)
finally:
    subprocess.run(['adb','-d','shell','am','force-stop','org.motoai.runtime'],capture_output=True)
    report['health_after']=read_health()
    if report['health_after'].get('boot_id_sha256')!=initial.get('boot_id_sha256'):report['passed']=False
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Results:',out.relative_to(ROOT),flush=True);print('PASS' if report['passed'] else report.get('error','FAIL'),flush=True)
raise SystemExit(0 if report['passed'] else 1)
