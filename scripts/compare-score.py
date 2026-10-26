#!/usr/bin/env python3
"""Serial host CPU / APK teacher-forcing comparison on owned public fixtures."""
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready
from phone_model import model_sha256
import argparse
import datetime
import hashlib
import json
import math
import pathlib
import re
import shlex
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('model', choices=['gemma', 'ornith'])
p.add_argument('fixture', choices=['teacher-forcing-code-v1', 'teacher-forcing-ru-v1', 'teacher-forcing-code-probe-v1', 'teacher-forcing-bos-probe-v1'])
p.add_argument('--host-runner', default='build/core-host/runtime-run', help='Explicit reference build; captured in the manifest')
p.add_argument('--app-artifact', help='Reuse an immutable prior APK score to isolate a reference-build change')
p.add_argument('--context', type=int, help='Separate diagnostic context, preserves the immutable base fixture')
p.add_argument('--load-mode', choices=['read', 'mmap'], help='Separate declared storage profile on both CPUs')
args = p.parse_args()
lease = acquire_phone_lease() if not args.app_artifact else None
if lease is not None: require_phone_ready()
fixture = ROOT / 'bench/fixtures/generated' / args.model / (args.fixture + '.json')
golden = json.loads(fixture.with_suffix('.golden.json').read_text())
if hashlib.sha256(fixture.read_bytes()).hexdigest() != golden['request_sha256']:
    raise RuntimeError('Fixture changed after vocabulary inspection')
spec = json.loads(fixture.read_text())
ids = spec['request']['score_continuation_ids']
gates_path = ROOT / 'bench/logit-gates.json'
gates = json.loads(gates_path.read_text())
model = next(m for m in json.loads((ROOT / 'bench/models.lock.json').read_text())['models'] if m['id'] == args.model)
local_model = ROOT / 'models' / model['filename']
if hashlib.file_digest(local_model.open('rb'), 'sha256').hexdigest() != model['sha256']:
    raise RuntimeError('Local model SHA mismatch')
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
out = ROOT / 'bench/results' / f'score-{args.model}-{args.fixture}-{stamp}'
out.mkdir(parents=True)
source_request_sha = golden['request_sha256']
if args.context is not None or args.load_mode is not None:
    if args.context is not None:
        if not 128 <= args.context <= 262144: p.error('Invalid diagnostic context')
        spec['config']['context'] = args.context
    if args.load_mode is not None: spec['config']['load_mode'] = args.load_mode
    fixture = out / 'request.json'
    fixture.write_text(json.dumps(spec, indent=2) + '\n')
report = {'model': args.model, 'fixture': args.fixture, 'passed': False,
          'model_sha256': model['sha256'], 'request_sha256': hashlib.sha256(fixture.read_bytes()).hexdigest(),
          'source_request_sha256': source_request_sha, 'context': spec['config']['context'], 'load_mode': spec['config'].get('load_mode', 'auto'),
          'gates_sha256': hashlib.sha256(gates_path.read_bytes()).hexdigest(),
          'scope': 'Small owned teacher-forcing corpus; cross-ISA CPU reference, not whole-model quality or performance acceptance.'}
runner = (ROOT / args.host_runner).resolve()
report['host_runner'] = str(runner.relative_to(ROOT))
report['host_runner_sha256'] = hashlib.file_digest(runner.open('rb'), 'sha256').hexdigest()
report['host_libraries_sha256'] = {str(path.relative_to(ROOT)): hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()
                                     for path in runner.parent.rglob('*.dylib') if not path.is_symlink()}
cache = runner.parent / 'CMakeCache.txt'
if cache.exists():
    report['host_build_flags'] = [line for line in cache.read_text().splitlines() if re.match(r'^(GGML_(NATIVE|ACCELERATE|CPU_ARM_ARCH|CPU_KLEIDIAI|OPENMP|LLAMAFILE|BLAS|METAL)|CMAKE_BUILD_TYPE):', line)]
try:
    print('Host CPU reference:', args.model, args.fixture, flush=True)
    host = subprocess.run([str(runner), str(local_model), str(fixture)],
                          capture_output=True, text=True, timeout=300)
    (out / 'host.stderr.txt').write_text(host.stderr)
    (out / 'host.json').write_text(host.stdout)
    if host.returncode: raise RuntimeError('Host scoring failed; inspect saved report')
    reference = json.loads(host.stdout)
    if args.app_artifact:
        artifact = ROOT / args.app_artifact
        report['app_artifact_reused'] = True
    else:
        phone_sha = model_sha256(model['filename'])
        if phone_sha != model['sha256']: raise RuntimeError('Phone model SHA mismatch')
        print('APK CPU candidate:', args.model, args.fixture, flush=True)
        app = subprocess.run([sys.executable, 'scripts/run-app-smoke.py', args.model, '--service', '--request',
                              str(fixture.relative_to(ROOT)), '--record-device', '--timeout', '300'],
                             cwd=ROOT, capture_output=True, text=True, timeout=540, **lease.child_options())
        (out / 'app.log').write_text(app.stdout + '\n' + app.stderr)
        match = re.search(r'^Results: (bench/results/[^\n]+)$', app.stdout, re.MULTILINE)
        if not match: raise RuntimeError('APK runner returned no artifact')
        artifact = ROOT / match.group(1)
        if app.returncode: raise RuntimeError('APK scoring failed; inspect raw artifact')
    report['app_artifact'] = str(artifact.relative_to(ROOT))
    if (artifact / 'invalidated.json').exists(): raise RuntimeError('APK artifact was invalidated')
    app_manifest = json.loads((artifact / 'manifest.json').read_text())
    if app_manifest['model']['sha256'] != model['sha256'] or app_manifest['request_spec'] != spec:
        raise RuntimeError('Reused APK model/request differs from the fixed reference')
    candidate = json.loads((artifact / 'result.json').read_text())
    report['apk_sha256'] = json.loads((artifact / 'manifest.json').read_text())['apk_sha256']
    a, b = reference['result'], candidate['result']
    report['host_runtime_variant'] = reference.get('load', {}).get('runtime_variant')
    report['app_runtime_variant'] = candidate.get('load', {}).get('runtime_variant')
    if (report['host_runtime_variant'] is not None and report['app_runtime_variant'] is not None and
        report['host_runtime_variant'] != report['app_runtime_variant']): raise RuntimeError('Different host/APK math variants')
    report['host_arm64_fpcr'] = a.get('arm64_fpcr')
    report['app_arm64_fpcr'] = b.get('arm64_fpcr')
    if spec['config'].get('tensor_probe'):
        probes_a = {node['name']: node for node in a.get('node_probes', [])}
        probes_b = {node['name']: node for node in b.get('node_probes', [])}
        report['node_comparison'] = []
        for name, node in probes_a.items():
            other = probes_b.get(name)
            row = {'name': name, 'host_status': node['status'], 'app_status': other['status'] if other else 'missing'}
            if other and node['status'] == other['status'] == 'captured' and node['shape'] == other['shape']:
                x, y = node['head'] + node['tail'], other['head'] + other['tail']
                row.update(shape=node['shape'], host_prompt_end=node.get('prompt_end'), app_prompt_end=other.get('prompt_end'), fingerprint_matches=node['fingerprint_fnv1a64'] == other['fingerprint_fnv1a64'],
                           sampled_max_absolute_error=max(abs(u-v) for u,v in zip(x,y)),
                           host_l2=node['l2'], app_l2=other['l2'], finite=node['finite'] and other['finite'])
            report['node_comparison'].append(row)
        if not report['node_comparison']: raise RuntimeError('No requested probe nodes captured')

    for result in (a, b):
        if result.get('mode') != 'teacher_forcing' or result.get('finish_reason') != 'scored':
            raise RuntimeError('Ordinary generation was mistaken for corpus scoring')
        if result['trace']['prompt_ids'] != golden['expected']['prompt_ids'] or result['trace']['generated_ids'] != ids:
            raise RuntimeError('Teacher-forcing trajectory differs')
        if result.get('scored_tokens') != len(ids) or result.get('logits_checks') != len(ids):
            raise RuntimeError('Missing scoring/finite coverage')
        if len(result['token_nll']) != len(ids) or not all(math.isfinite(x) for x in result['token_nll']):
            raise RuntimeError('Missing/nonfinite NLL')
    differences = [abs(x - y) for x, y in zip(a['token_nll'], b['token_nll'])]
    top_a = dict(zip(a['first_logits_top8']['ids'], a['first_logits_top8']['values']))
    top_b = dict(zip(b['first_logits_top8']['ids'], b['first_logits_top8']['values']))
    shared = top_a.keys() & top_b.keys()
    report.update(scored_tokens=len(ids), host_mean_nll=a['mean_nll'], app_mean_nll=b['mean_nll'],
                  mean_absolute_token_nll_error=sum(differences) / len(differences),
                  maximum_absolute_token_nll_error=max(differences),
                  first_top8_set_matches=top_a.keys() == top_b.keys(),
                  first_top8_max_shared_logit_absolute_error=max((abs(top_a[k] - top_b[k]) for k in shared), default=None))
    report['passed'] = (report['mean_absolute_token_nll_error'] <= gates['mean_absolute_token_nll_error_max'] and
                        report['maximum_absolute_token_nll_error'] <= gates['maximum_absolute_token_nll_error_max'] and
                        (not gates['first_top8_set_must_match'] or report['first_top8_set_matches']) and
                        report['first_top8_max_shared_logit_absolute_error'] is not None and
                        report['first_top8_max_shared_logit_absolute_error'] <= gates['first_top8_max_shared_logit_absolute_error'])
except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
    report['error'] = str(error)
finally:
    (out / 'comparison.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2), flush=True)
print('Results:', out.relative_to(ROOT), flush=True)
raise SystemExit(0 if report['passed'] else 1)
