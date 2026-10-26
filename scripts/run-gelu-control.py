#!/usr/bin/env python3
"""Host-only exact Android GELU-table control; unchanged original comparison gates."""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--comparison', default='bench/results/score-gemma-teacher-forcing-code-probe-v1-20261024T195743Z/comparison.json')
p.add_argument('--app-artifact', help='Explicit scored or generated APK artifact; no new phone workload')
p.add_argument('--host-runner', default='build/reference-host/runtime-run')
p.add_argument('--table', default='bench/fixtures/reference/android-gelu-f16-v1.json')
p.add_argument('--timeout', type=int, default=300)
args = p.parse_args()
comparison = ROOT / args.comparison
reference = json.loads(comparison.read_text()) if not args.app_artifact else None
artifact = ROOT / (args.app_artifact or reference['app_artifact'])
if (artifact / 'invalidated.json').exists(): raise RuntimeError('Invalidated phone reference')
if (artifact / 'failure.json').exists(): raise RuntimeError('Failed phone artifact is not a reference')
manifest = json.loads((artifact / 'manifest.json').read_text())
phone_report = json.loads((artifact / 'result.json').read_text())
candidate = phone_report['result']
table_path = ROOT / args.table
if args.table == 'bench/fixtures/reference/android-gelu-f16-v1.json':
    lock = json.loads((ROOT / 'bench/cpu-reference.lock.json').read_text())
    if hashlib.sha256(table_path.read_bytes()).hexdigest() != lock['activation_table_sha256']:
        raise RuntimeError('Frozen reference table changed')
table = json.loads(table_path.read_text())
if (table['arm64_fpcr'] != 0 or len(table['gelu_f16']) != 65536 or
    any(type(value) is not int or not 0 <= value <= 65535 for value in table['gelu_f16'])): raise RuntimeError('Invalid activation reference')
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
out = ROOT / 'bench/results' / ('host-gelu-control-' + manifest['model']['id'] + '-' + stamp)
out.mkdir(parents=True)
(out / 'gelu-table.h').write_text('static constexpr uint16_t measured_gelu[65536] = {\n' +
    ','.join(map(str, table['gelu_f16'])) + '\n};\n')
request_path = out / 'request.json'
request_path.write_text(json.dumps(manifest['request_spec'], indent=2) + '\n')
library = out / 'gelu-control.dylib'
source = ROOT / 'native-runtime/tools/gelu-control.cpp'
subprocess.run(['clang++', '-std=c++17', '-O2', '-dynamiclib', '-Wall', '-Wextra', '-Werror',
                '-I', str(out), str(source), '-o', str(library)], check=True)
runner = ROOT / args.host_runner
model = ROOT / 'models' / manifest['model']['filename']
if hashlib.file_digest(model.open('rb'), 'sha256').hexdigest() != manifest['model']['sha256']:
    raise RuntimeError('Changed model')
run = subprocess.run([str(runner), str(model), str(request_path)],
                     env=dict(os.environ, DYLD_INSERT_LIBRARIES=str(library)), capture_output=True, text=True, timeout=args.timeout)
(out / 'host.json').write_text(run.stdout); (out / 'host.stderr.txt').write_text(run.stderr)
gates_path = ROOT / 'bench/logit-gates.json'
gates = json.loads(gates_path.read_text())
report = {'scope': 'Host-only causal control: actual complete65536-entry Android CPU GELU table installed before inference; original model/request, no trig substitution, original scoring gates unchanged. Not an APK optimization or whole-corpus pass.',
          'passed': False, 'model': manifest['model']['id'], 'fixture': pathlib.Path(manifest['arguments']['request']).stem,
          'phone_artifact': str(artifact.relative_to(ROOT)), 'phone_apk_sha256': manifest['apk_sha256'],
          'comparison': str(comparison.relative_to(ROOT)) if reference else None, 'returncode': run.returncode,
          'model_sha256': manifest['model']['sha256'],
          'inputs_sha256': {str(path.relative_to(ROOT)): hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()
                            for path in [pathlib.Path(__file__), source, table_path, request_path, runner, library, gates_path]},
          'host_libraries_sha256': {str(path.relative_to(ROOT)): hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()
                                   for path in runner.parent.rglob('*.dylib') if not path.is_symlink()}}
try:
    if run.returncode == 0:
        host_report = json.loads(run.stdout)
        result = host_report['result']
        report['host_runtime_variant'] = host_report['load'].get('runtime_variant')
        report['phone_runtime_variant'] = phone_report['load'].get('runtime_variant')
        if report['host_runtime_variant'] != report['phone_runtime_variant']: raise RuntimeError('Different CPU math variants')
        if result['trace']['prompt_ids'] != candidate['trace']['prompt_ids'] or result['trace']['generated_ids'] != candidate['trace']['generated_ids']:
            raise RuntimeError('Changed controlled token trajectory')
        scoring = candidate.get('mode') == 'teacher_forcing'
        if (result.get('mode') == 'teacher_forcing') != scoring: raise RuntimeError('Changed evaluation mode')
        differences = [abs(a-b) for a,b in zip(result.get('token_nll', []), candidate.get('token_nll', []))]
        if scoring and (not differences or len(result['token_nll']) != len(candidate['token_nll'])): raise RuntimeError('Changed score length')
        a = dict(zip(result['first_logits_top8']['ids'], result['first_logits_top8']['values']))
        b = dict(zip(candidate['first_logits_top8']['ids'], candidate['first_logits_top8']['values']))
        report.update(mode='teacher_forcing' if scoring else 'generation', generated_ids_equal=True,
                      generated_tokens=result['generated_tokens'], finish_reason=result['finish_reason'],
                      scored_tokens=len(differences), mean_absolute_token_nll_error=sum(differences)/len(differences) if differences else None,
                      maximum_absolute_token_nll_error=max(differences) if differences else None, top8_set_matches=a.keys()==b.keys(),
                      top8_max_shared_logit_error=max((abs(a[k]-b[k]) for k in a.keys()&b.keys()), default=None),
                      table_changed_entries=int(re.search(r'MOTOAI_GELU_CONTROL changed=(\d+)', run.stderr).group(1)),
                      table_final_match='MOTOAI_GELU_CONTROL final_match=1' in run.stderr,
                      logits_checks=result['logits_checks'], host_mean_nll=result.get('mean_nll'), phone_mean_nll=candidate.get('mean_nll'))
        report['passed'] = (report['table_final_match'] and result['finish_reason'] == candidate['finish_reason'] and
                            result['logits_checks'] >= result['generated_tokens'] and
                            (not scoring or (report['mean_absolute_token_nll_error'] <= gates['mean_absolute_token_nll_error_max'] and
                                             report['maximum_absolute_token_nll_error'] <= gates['maximum_absolute_token_nll_error_max'])) and
                            report['top8_set_matches'] and report['top8_max_shared_logit_error'] is not None and
                            report['top8_max_shared_logit_error'] <= gates['first_top8_max_shared_logit_absolute_error'])
except (OSError, ValueError, KeyError, RuntimeError) as error:
    report['error'] = str(error)
(out / 'control.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2)); print('Results:', out.relative_to(ROOT))
raise SystemExit(0 if report['passed'] else 1)
