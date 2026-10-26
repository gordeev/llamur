#!/usr/bin/env python3
"""Serial fresh-process read/mmap scoring trials on one APK and canonical model."""
import argparse
import datetime
import hashlib
import json
import math
import pathlib
import re
import subprocess
import sys
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready
from phone_model import model_sha256, model_source

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('model', choices=['gemma', 'ornith'])
p.add_argument('--modes', nargs='+', choices=['read', 'mmap'], default=['mmap', 'read', 'read', 'mmap'])
p.add_argument('--corpus', choices=['code', 'ru'], default='code')
args = p.parse_args()
lease = acquire_phone_lease()
require_phone_ready()
model = next(m for m in json.loads((ROOT / 'bench/models.lock.json').read_text())['models'] if m['id'] == args.model)
if model_sha256(model['filename']) != model['sha256']: raise RuntimeError('Phone model changed')
source = model_source(model['filename'])
fixture = ROOT / 'bench/fixtures/generated' / args.model / f'teacher-forcing-{args.corpus}-v1.json'
spec = json.loads(fixture.read_text()); golden = json.loads(fixture.with_suffix('.golden.json').read_text())
if hashlib.sha256(fixture.read_bytes()).hexdigest() != golden['request_sha256']: raise RuntimeError('Changed frozen fixture')
apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
apk_sha = hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
out = ROOT / 'bench/results' / f'load-matrix-{args.model}-{args.corpus}-{stamp}'; out.mkdir(parents=True)
report = {'passed': False, 'scope': 'Serial fresh-process load/scoring on one APK. No generated task quality or speed claim; ambient Android memory/cache state is uncontrolled.',
          'apk_sha256': apk_sha, 'model_sha256': model['sha256'], 'model_source': source,
          'base_request_sha256': golden['request_sha256'], 'runs': []}
baseline = None
for index, mode in enumerate(args.modes):
    request = dict(spec, config=dict(spec['config'], load_mode=mode))
    path = out / f'request-{index}-{mode}.json'; path.write_text(json.dumps(request, indent=2) + '\n')
    command = [sys.executable, 'scripts/run-app-smoke.py', args.model, '--service', '--request', str(path.relative_to(ROOT)), '--record-device', '--timeout', '300']
    entry = {'mode': mode, 'passed': False}
    try:
        run = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=540, **lease.child_options())
        (out / f'run-{index}.log').write_text(run.stdout + '\n' + run.stderr)
        match = re.search(r'^Results: (bench/results/[^\n]+)$', run.stdout, re.MULTILINE)
        if not match: raise RuntimeError('No APK artifact, inspect run log')
        artifact = ROOT / match.group(1); entry['artifact'] = match.group(1)
        if run.returncode: raise RuntimeError('APK run failed; raw failure/exit evidence retained')
        data = json.loads((artifact / 'result.json').read_text()); result = data['result']
        manifest = json.loads((artifact / 'manifest.json').read_text())
        if manifest['apk_sha256'] != apk_sha or manifest['request_spec'] != request: raise RuntimeError('APK/request changed')
        count = len(request['request']['score_continuation_ids'])
        if result.get('mode') != 'teacher_forcing' or result.get('finish_reason') != 'scored' or result.get('logits_checks') != count:
            raise RuntimeError('Missing finite scoring coverage')
        if (result['trace']['prompt_ids'] != golden['expected']['prompt_ids'] or
            result['trace']['generated_ids'] != request['request']['score_continuation_ids'] or
            len(result['token_nll']) != count or not all(math.isfinite(x) for x in result['token_nll'])):
            raise RuntimeError('Changed trajectory/nonfinite score')
        if baseline is None: baseline = result
        errors = [abs(a-b) for a,b in zip(baseline['token_nll'], result['token_nll'])]
        left = dict(zip(baseline['first_logits_top8']['ids'], baseline['first_logits_top8']['values']))
        right = dict(zip(result['first_logits_top8']['ids'], result['first_logits_top8']['values']))
        same_top = left.keys() == right.keys()
        peak_top = max((abs(left[k]-right[k]) for k in left.keys() & right.keys()), default=math.inf)
        entry.update(pid=data['pid'], load_us=data['load']['load_us'], loaded_pss_kib=data['loaded_pss_kib'],
                     memory_before_load=data.get('memory_before_load'), process_status=data.get('process_status'),
                     buffer_allocations=data['load'].get('buffer_allocations'),
                     mean_nll=result['mean_nll'], mean_nll_error=sum(errors)/len(errors), max_nll_error=max(errors), top8_error=peak_top)
        entry['passed'] = entry['mean_nll_error'] <= 1e-6 and entry['max_nll_error'] <= 1e-5 and same_top and peak_top <= 1e-5
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        entry['error'] = str(error)
    report['runs'].append(entry)
    (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'{args.model}/{args.corpus} {mode}: {"PASS" if entry["passed"] else entry.get("error", "numeric mismatch")}', flush=True)
report['passed'] = all(entry['passed'] for entry in report['runs']) and len({entry.get('pid') for entry in report['runs']}) == len(args.modes)
(out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
print('Results:', out.relative_to(ROOT))
raise SystemExit(0 if report['passed'] else 1)
