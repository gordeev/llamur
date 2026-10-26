#!/usr/bin/env python3
"""Complete a public coding task under one CPU profile; compare quality only."""
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
from weight_buffer_checks import check_result, compare_results
from decode_checks import check_scheduling

ROOT=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--profiles',type=pathlib.Path,required=True)
parser.add_argument('--profile',required=True)
parser.add_argument('--reference-check',type=pathlib.Path,help='Passed same-APK4-worker completed-task check')
parser.add_argument('--attention-gate',type=pathlib.Path,help='Before-install exact4-baseline/F64 attention gate, required to bridge changed kernels')
args=parser.parse_args()
lease=acquire_phone_lease();health=require_phone_ready()
profiles=json.loads(args.profiles.read_text());profile=next(p for p in profiles['candidates'] if p['id']==args.profile)
fixture=ROOT/'bench/fixtures/generated/ornith/binary-search-quality-4096-mmap-v1.json'
golden=json.loads(fixture.with_suffix('.golden.json').read_text());source=json.loads(fixture.read_text())
if hashlib.sha256(fixture.read_bytes()).hexdigest()!=golden['request_sha256']:raise RuntimeError('Frozen task changed')
model=next(m for m in json.loads((ROOT/'bench/models.lock.json').read_text())['models'] if m['id']=='ornith')
if model_sha256(model['filename'])!=model['sha256']:raise RuntimeError('Phone weights SHA differs')
apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk';apk_sha=hashlib.file_digest(apk.open('rb'),'sha256').hexdigest()
identity_path=ROOT/'bench/m3-decode-kernel-identity-20261025.json';identity=json.loads(identity_path.read_text())
attention_gate=None
if args.reference_check:
    reference_quality_path=args.reference_check.resolve()
    if not reference_quality_path.is_relative_to(ROOT/'bench/results'):raise RuntimeError('Reference check must be a local raw result')
    quality=json.loads(reference_quality_path.read_text())
    if not quality.get('passed') or quality['apk_sha256']!=apk_sha or quality['model_sha256']!=model['sha256'] or quality['profile']['threads']!=4:
        raise RuntimeError('No passed same-APK4-worker reference')
    reference=(ROOT/quality['artifact']).resolve();expected_reference_apk=apk_sha
else:
    reference_quality_path=ROOT/'bench/results/weight-buffers-ornith-binary-search-quality-4096-mmap-v1-20261025T194131Z/report.json'
    quality=json.loads(reference_quality_path.read_text())
    if not quality['passed'] or not quality['pairs'][0]['trials']['cpu_repack']['quality']['quality_passed']:
        raise RuntimeError('Unaccepted completed reference task')
    reference=(ROOT/quality['pairs'][0]['trials']['cpu_repack']['artifact']).resolve();expected_reference_apk=quality['apk_sha256']
    if args.attention_gate:
        attention_gate=json.loads(args.attention_gate.read_text())
        if (profile['threads']!=4 or not attention_gate.get('passed') or not attention_gate.get('baseline4_preserved') or
            not attention_gate.get('decode_threads_identical') or not attention_gate.get('independent_reference_passed') or
            attention_gate['candidate_apk_sha256']!=apk_sha or attention_gate['installed_reference_apk_sha256']!=identity['apk_sha256'] or
            identity['prior_apk_sha256']!=expected_reference_apk or not identity['kernel_libraries_unchanged']):
            raise RuntimeError('New kernels lack independent exact4-baseline/F64 gate and unchanged-kernel bridge')
    elif identity['apk_sha256']!=apk_sha or not identity['kernel_libraries_unchanged'] or identity['prior_apk_sha256']!=expected_reference_apk:
        raise RuntimeError('No same-kernel quality-reference identity')
if not reference.is_relative_to(ROOT/'bench/results') or (reference/'invalidated.json').exists():raise RuntimeError('Invalid reference artifact')
ref_manifest=json.loads((reference/'manifest.json').read_text());ref_data=json.loads((reference/'result.json').read_text())
if ref_manifest['apk_sha256']!=expected_reference_apk or ref_manifest['model']['sha256']!=model['sha256']:raise RuntimeError('Reference model/APK differs')
if ref_manifest['request_spec']['request']!=source['request']:raise RuntimeError('Reference task input differs')
def invariant(config):
    config=copy.deepcopy(config)
    for key in ('threads','threads_batch','cpu_affinity','transient_repack'):config.pop(key,None)
    config.setdefault('weight_buffers','native');config.setdefault('mmap_prefault',True)
    return config
if invariant(ref_manifest['request_spec']['config'])!=invariant(source['config']):raise RuntimeError('Reference has non-scheduling geometry differences')
ref_result=check_result(ref_data,golden,'native',source['request'],transient_repack=ref_manifest['request_spec']['config'].get('transient_repack',False))
out=ROOT/'bench/results'/('decode-task-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir(parents=True)
report=dict(passed=False,scope='Completed task and exact trajectory vs prior accepted same-kernel artifact; quality only, never reuse reference timings',
            apk_sha256=apk_sha,model_sha256=model['sha256'],profile=profile,reference_artifact=str(reference.relative_to(ROOT)),initial_health=health,
            reference_files_sha256={name:hashlib.sha256((reference/name).read_bytes()).hexdigest() for name in ('manifest.json','result.json')},
            reference_quality_report_sha256=hashlib.sha256(reference_quality_path.read_bytes()).hexdigest(),kernel_identity_sha256=hashlib.sha256(identity_path.read_bytes()).hexdigest())
if args.attention_gate:
    report['attention_gate_sha256']=hashlib.sha256(args.attention_gate.read_bytes()).hexdigest()
    (out/'attention-gate.json').write_bytes(args.attention_gate.read_bytes())
for path in [fixture,fixture.with_suffix('.golden.json'),identity_path,reference_quality_path]:
    (out/("reference-quality-report.json" if path==reference_quality_path else path.name)).write_bytes(path.read_bytes())
for name in ('manifest.json','result.json'):(out/('reference-'+name)).write_bytes((reference/name).read_bytes())
for name in ('run-decode-task-check.py','decode_checks.py','weight_buffer_checks.py','evaluate-code.py'):(out/name).write_bytes((ROOT/'scripts'/name).read_bytes())
spec=copy.deepcopy(source);spec['config'].update({key:value for key,value in profile.items() if key!='id'});spec['config'].update(weight_buffers='native',transient_repack=False)
request=out/'request.json';request.write_text(json.dumps(spec,indent=2)+'\n')
try:
    run=subprocess.run([sys.executable,'scripts/run-app-smoke.py','ornith','--service','--request',str(request.relative_to(ROOT)),
                        '--record-device','--timeout','1200'],cwd=ROOT,capture_output=True,text=True,timeout=1440,**lease.child_options())
    (out/'trial.log').write_text(run.stdout+'\n'+run.stderr)
    match=re.search(r'^Results: (bench/results/[^\n]+)$',run.stdout,re.MULTILINE)
    if not match:raise RuntimeError('No raw task result')
    artifact=ROOT/match[1];report['artifact']=str(artifact.relative_to(ROOT))
    if run.returncode:raise RuntimeError('Task failed; inspect raw result')
    manifest=json.loads((artifact/'manifest.json').read_text())
    if manifest['apk_sha256']!=apk_sha or manifest['request_spec']!=spec or (artifact/'invalidated.json').exists():raise RuntimeError('Task APK/input differs')
    data=json.loads((artifact/'result.json').read_text());result=check_result(data,golden,'native',spec['request'],transient_repack=False)
    check_scheduling(data,spec['config'])
    for key in ('context','batch','micro_batch','load_mode'):
        if data['load'][key]!=spec['config'][key]:raise RuntimeError('Task load geometry differs')
    if (args.attention_gate or args.reference_check) and data['load'].get('attention_f32_kv_chunks')!=4:raise RuntimeError('Canonical F32 attention policy not confirmed')
    report['comparison']=compare_results(ref_result,result,json.loads((ROOT/'bench/logit-gates.json').read_text()),require_ids=False)
    module_spec=importlib.util.spec_from_file_location('evaluator',ROOT/'scripts/evaluate-code.py');evaluator=importlib.util.module_from_spec(module_spec);module_spec.loader.exec_module(evaluator)
    report['quality']=evaluator.assess_report(data,function='lower_bound')
    report['generated_tokens']=result['generated_tokens'];report['finish_reason']=result['finish_reason']
    report['passed']=report['comparison']['numeric_passed'] and report['comparison']['generated_ids_match'] and report['quality']['quality_passed'] and result['finish_reason']==ref_result['finish_reason']=='eog'
    if not report['passed']:report['error']='Completed task failed exact trajectory, numeric, finish or functional gate'
except (OSError,ValueError,KeyError,RuntimeError,subprocess.SubprocessError,KeyboardInterrupt) as error:report['error']=str(error) or type(error).__name__
finally:
    report['final_health']=read_health();(out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2),flush=True);print('Results:',out.relative_to(ROOT),flush=True)
raise SystemExit(0 if report['passed'] else 1)
