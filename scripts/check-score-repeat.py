#!/usr/bin/env python3
"""Check independently loaded APK CPU scoring runs; no host-model execution."""
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
p.add_argument('fixture', choices=['teacher-forcing-code-v1', 'teacher-forcing-ru-v1', 'teacher-forcing-code-mmap-v1'])
args = p.parse_args()
lease = acquire_phone_lease()
require_phone_ready()
fixture = ROOT / 'bench/fixtures/generated' / args.model / (args.fixture + '.json')
spec = json.loads(fixture.read_text())
golden = json.loads(fixture.with_suffix('.golden.json').read_text())
if hashlib.sha256(fixture.read_bytes()).hexdigest() != golden['request_sha256']:
    raise RuntimeError('Fixture changed after inspection')
gates_path = ROOT / 'bench/score-repeat-gates.json'
gates = json.loads(gates_path.read_text())
model = next(m for m in json.loads((ROOT / 'bench/models.lock.json').read_text())['models'] if m['id'] == args.model)
phone_sha = model_sha256(model['filename'])
if phone_sha != model['sha256']: raise RuntimeError('Phone model differs from lock')
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
out = ROOT / 'bench/results' / f'score-repeat-{args.model}-{args.fixture}-{stamp}'
out.mkdir(parents=True)
report = {'model': args.model, 'fixture': args.fixture, 'passed': False, 'runs': [],
          'model_sha256': model['sha256'], 'request_sha256': golden['request_sha256'],
          'gates_sha256': hashlib.sha256(gates_path.read_bytes()).hexdigest(), 'scope': gates['scope']}
results = []
try:
    for trial in range(gates['minimum_runs']):
        command = [sys.executable, 'scripts/run-app-smoke.py', args.model, '--service', '--request',
                   str(fixture.relative_to(ROOT)), '--record-device', '--timeout', '300']
        run = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=540, **lease.child_options())
        (out / f'run-{trial}.log').write_text(run.stdout + '\n' + run.stderr)
        match = re.search(r'^Results: (bench/results/[^\n]+)$', run.stdout, re.MULTILINE)
        if not match: raise RuntimeError('APK runner did not return an artifact')
        artifact = ROOT / match.group(1)
        if run.returncode: raise RuntimeError('APK scoring run failed: ' + match.group(1))
        data = json.loads((artifact / 'result.json').read_text())
        manifest = json.loads((artifact / 'manifest.json').read_text())
        result = data['result']
        if manifest['request_spec'] != spec or manifest['model']['sha256'] != model['sha256']:
            raise RuntimeError('Request/model changed between runs')
        if result.get('mode') != 'teacher_forcing' or result.get('finish_reason') != 'scored':
            raise RuntimeError('Expected corpus scoring, not generated output')
        count = len(spec['request']['score_continuation_ids'])
        if result.get('scored_tokens') != count or result.get('logits_checks') != count or len(result['token_nll']) != count:
            raise RuntimeError('Missing scoring/finite coverage')
        if result['trace']['prompt_ids'] != golden['expected']['prompt_ids'] or result['trace']['generated_ids'] != spec['request']['score_continuation_ids']:
            raise RuntimeError('Changed token trajectory')
        if not all(math.isfinite(x) for x in result['token_nll']): raise RuntimeError('Nonfinite NLL')
        report['runs'].append({'artifact': match.group(1), 'apk_sha256': manifest['apk_sha256'],
                               'pid': data['pid'], 'arm64_fpcr': result.get('arm64_fpcr'), 'mean_nll': result['mean_nll']})
        results.append(result)
        print(f'{args.model}/{args.fixture} run{trial+1}: {count} scored tokens', flush=True)
    if len({run['apk_sha256'] for run in report['runs']}) != 1: raise RuntimeError('APK changed between runs')
    if len({run['pid'] for run in report['runs']}) != len(results): raise RuntimeError('Runs did not use independent processes')
    a, b = results
    errors = [abs(x-y) for x,y in zip(a['token_nll'], b['token_nll'])]
    top_a = dict(zip(a['first_logits_top8']['ids'], a['first_logits_top8']['values']))
    top_b = dict(zip(b['first_logits_top8']['ids'], b['first_logits_top8']['values']))
    report.update(mean_absolute_token_nll_error=sum(errors)/len(errors), maximum_absolute_token_nll_error=max(errors),
                  first_top8_set_matches=top_a.keys()==top_b.keys(),
                  first_top8_absolute_error=max((abs(top_a[k]-top_b[k]) for k in top_a.keys() & top_b.keys()), default=None))
    report['passed'] = (report['mean_absolute_token_nll_error'] <= gates['mean_absolute_token_nll_error_max'] and
                        report['maximum_absolute_token_nll_error'] <= gates['maximum_absolute_token_nll_error_max'] and
                        report['first_top8_set_matches'] and report['first_top8_absolute_error'] is not None and
                        report['first_top8_absolute_error'] <= gates['first_top8_absolute_error_max'])
except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
    report['error'] = str(error)
finally:
    (out / 'repeatability.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2), flush=True)
print('Results:', out.relative_to(ROOT))
raise SystemExit(0 if report['passed'] else 1)
