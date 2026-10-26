#!/usr/bin/env python3
"""Serial APK correctness/performance series; raw trials plus JSONL/CSV summary."""
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready, parse_battery, comparable_power
from phone_model import model_sha256
import argparse
import csv
import datetime
import hashlib
import importlib.util
import json
import pathlib
import re
import random
import shlex
import statistics
import subprocess
import sys
from zoneinfo import ZoneInfo

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('model', choices=['gemma', 'ornith'])
p.add_argument('fixture')
p.add_argument('--repeat', type=int, default=5)
p.add_argument('--warmup', type=int, default=1)
p.add_argument('--timeout', type=int, default=600)
p.add_argument('--cutoff', help='Optional session stop boundary, HH:MM Europe/Belgrade')
args = p.parse_args()
lease = acquire_phone_lease()
require_phone_ready()
def cutoff_reached():
    if not args.cutoff: return False
    if not re.fullmatch(r'(?:[01][0-9]|2[0-3]):[0-5][0-9]', args.cutoff): p.error('Invalid cutoff HH:MM')
    now = datetime.datetime.now(ZoneInfo('Europe/Belgrade'))
    return (now.hour, now.minute) >= tuple(map(int, args.cutoff.split(':')))
if cutoff_reached():
    print('Cutoff reached: no new series started.')
    raise SystemExit(0)
active_apk_sha = hashlib.file_digest((ROOT / 'app/build/outputs/apk/debug/app-debug.apk').open('rb'), 'sha256').hexdigest()
for excluded in json.loads((ROOT / 'bench/excluded-profiles.json').read_text())['profiles']:
    if active_apk_sha == excluded['apk_sha256']: raise RuntimeError(excluded['reason'])
if not 1 <= args.repeat <= 30 or not 0 <= args.warmup <= 3: p.error('Invalid repeat/warmup')
fixture = ROOT / 'bench/fixtures/generated' / args.model / (args.fixture + '.json')
golden = json.loads(fixture.with_suffix('.golden.json').read_text())
if hashlib.sha256(fixture.read_bytes()).hexdigest() != golden['request_sha256']: raise RuntimeError('Fixture changed after inspection')
quality_target = None
if golden['kind'] == 'quality':
    if args.fixture in ('add-quality', 'add-quality-mmap-v1'): quality_target = {'function': 'add'}
    elif args.fixture in ('binary-search-quality', 'binary-search-quality-2048', 'binary-search-quality-4096', 'binary-search-quality-4096-mmap-v1'):
        quality_target = {'function': 'lower_bound'}
    else: raise RuntimeError('Quality fixture has no declared evaluator')
elif golden['kind'] == 'context_quality':
    if not golden.get('expected_identifier'): raise RuntimeError('Retrieval fixture lacks an expected answer')
    quality_target = {'identifier': golden['expected_identifier']}
if quality_target is not None:
    evaluator_path = ROOT / 'scripts/evaluate-code.py'
    evaluator_spec = importlib.util.spec_from_file_location('code_evaluator', evaluator_path)
    evaluator = importlib.util.module_from_spec(evaluator_spec)
    evaluator_spec.loader.exec_module(evaluator)
started = datetime.datetime.now(datetime.timezone.utc)
series = ROOT / 'bench/results' / f'series-{args.model}-{args.fixture}-{started:%Y%m%dT%H%M%SZ}'
series.mkdir(parents=True)
manifest = {'started_utc': started.isoformat(), 'model': args.model, 'fixture': args.fixture,
            'request_sha256': golden['request_sha256'], 'kind': golden['kind'], 'arguments': vars(args),
            'protocol': 'bench/PROTOCOL.md', 'scope': 'fresh process; storage cache unknown; CPU product APK'}
golden_bytes = fixture.with_suffix('.golden.json').read_bytes()
manifest['golden_sha256'] = hashlib.sha256(golden_bytes).hexdigest()
(series / 'fixture.golden.json').write_bytes(golden_bytes)
source_snapshot = series / 'controller-sources'
source_snapshot.mkdir()
manifest['controller_sources_sha256'] = {}
for name in ['benchmark.py', 'phone_health.py', 'phone_model.py', 'phone_lease.py', 'evaluate-code.py']:
    contents = (ROOT / 'scripts' / name).read_bytes()
    (source_snapshot / name).write_bytes(contents)
    manifest['controller_sources_sha256'][name] = hashlib.sha256(contents).hexdigest()
if quality_target is not None:
    manifest['quality_target'] = quality_target
    manifest['evaluator_sha256'] = hashlib.file_digest(evaluator_path.open('rb'), 'sha256').hexdigest()
model_lock = next(m for m in json.loads((ROOT / 'bench/models.lock.json').read_text())['models'] if m['id'] == args.model)
phone_sha = model_sha256(model_lock['filename'])
if phone_sha != model_lock['sha256']: raise RuntimeError('Phone model SHA-256 mismatch')
manifest['model_phone_sha256'] = phone_sha
(series / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
records = []
reference_ids = None
failure = None
paused = False
for trial in range(args.warmup + args.repeat):
    if cutoff_reached():
        paused = True
        print('Cutoff reached: no new trial started.', flush=True)
        break
    command = [sys.executable, 'scripts/run-app-smoke.py', args.model, '--service', '--request', str(fixture.relative_to(ROOT)),
               '--record-device', '--timeout', str(args.timeout)]
    if golden['kind'] == 'expected_rejection': command += ['--expect-error', golden['expected']['kind']]
    run = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=args.timeout + 240, **lease.child_options())
    (series / f'trial-{trial}.log').write_text(run.stdout + '\n' + run.stderr)
    match = re.search(r'^Results: (bench/results/[^\n]+)$', run.stdout, re.MULTILINE)
    record = {'trial': trial, 'warmup': trial < args.warmup, 'passed': False}
    try:
        if not match: raise RuntimeError('Runner did not return a result artifact')
        artifact = ROOT / match.group(1)
        record['artifact'] = str(artifact.relative_to(ROOT))
        record['apk_sha256'] = json.loads((artifact / 'manifest.json').read_text())['apk_sha256']
        if not (artifact / 'result.json').is_file():
            failure_path = artifact / 'failure.json'
            cause = json.loads(failure_path.read_text()) if failure_path.is_file() else {}
            if cause: raise RuntimeError(f"{cause.get('kind')}: {cause.get('message')}")
            raise RuntimeError('No final report; inspect runner log and device diagnostics')
        report = json.loads((artifact / 'result.json').read_text())
        if run.returncode != 0: raise RuntimeError('APK trial failed; inspect raw report/log')
        if golden['kind'] == 'expected_rejection':
            if golden['expected']['message'] not in report.get('message', ''): raise RuntimeError('Wrong expected error message')
        else:
            if report['load'].get('all_extra_buffers_disabled') is not True: raise RuntimeError('Unapproved weight buffer policy')
            result = report['result']
            trace = result.get('trace', {})
            if trace.get('rendered_prompt') != golden['expected']['rendered_prompt'] or trace.get('prompt_ids') != golden['expected']['prompt_ids']:
                raise RuntimeError('Host/app template or prompt-token mismatch')
            ids = trace.get('generated_ids')
            if not ids or len(ids) != result['generated_tokens']: raise RuntimeError('Missing/inconsistent output token trace')
            if golden.get('required_output_tokens') is not None and len(ids) != golden['required_output_tokens']:
                raise RuntimeError('Declared filled-context output was not completed; early EOS is not a filled-boundary pass')
            if reference_ids is None: reference_ids = ids
            elif ids != reference_ids: raise RuntimeError('Repeated output token trajectory differs from the series reference')
            if json.loads(fixture.read_text())['request'].get('check_logits') and result.get('logits_checks', 0) < result['generated_tokens']:
                raise RuntimeError('Logit finite checks missing')
            if golden['kind'] == 'teacher_forcing' and result.get('mode') != 'teacher_forcing':
                raise RuntimeError('Corpus score was mistaken for generation')
            perf = result['backend_perf']
            record.update(prompt_tokens=result['prompt_tokens'], generated_tokens=result['generated_tokens'], finish_reason=result['finish_reason'],
                          load_ms=report['load']['load_us'] / 1000, ttft_ms=result['first_target_token_us'] / 1000,
                          prefill_ms=result['prefill_us'] / 1000, decode_ms=result['decode_us'] / 1000,
                          prefill_tok_s=result['prompt_tokens'] * 1e6 / result['prefill_us'] if result['prefill_us'] else None,
                          backend_decode_tok_s=perf['decode_evals'] * 1000 / perf['decode_ms'] if perf['decode_ms'] else None,
                          completed_pss_kib=report.get('completed_pss_kib'), thermal_status=report.get('thermal_status'),
                          process_status=report.get('process_status'))
            record['data_collection_passed'] = True
            if quality_target is not None:
                assessment = evaluator.assess_report(report, **quality_target)
                (artifact / 'quality.json').write_text(json.dumps(assessment, indent=2) + '\n')
                record['quality'] = assessment
                if not assessment['quality_passed']:
                    raise RuntimeError('Quality gate failed: ' + json.dumps(assessment))
        before = json.loads((artifact / 'device-before.json').read_text())['observations']
        after = json.loads((artifact / 'device-after.json').read_text())['observations']
        def battery_temperature(snapshot):
            match = re.search(r'\btemperature:\s*(-?\d+)', snapshot['battery']['stdout'])
            return int(match.group(1)) / 10 if match else None
        record['battery_start_c'] = battery_temperature(before)
        record['battery_end_c'] = battery_temperature(after)
        power_before = parse_battery(before['battery']['stdout'])
        power_after = parse_battery(after['battery']['stdout'])
        record['battery_start_percent'] = power_before['percent']
        record['battery_end_percent'] = power_after['percent']
        record['performance_power_policy'] = 'minimum20-percent; unchanged charging state and AC/USB source'
        record['performance_comparable_power'] = comparable_power(power_before, power_after)
        pressure = record.get('thermal_status')
        record['performance_comparable_thermal'] = None if pressure is None else pressure < 2
        if pressure is not None and pressure >= 2 and golden['kind'] == 'performance':
            raise RuntimeError('Moderate or higher thermal pressure; series is not a comparable short baseline')
        if not record['performance_comparable_power'] and golden['kind'] == 'performance':
            raise RuntimeError('Low/unavailable battery or changed power state; series is not a comparable powered baseline')
        record['passed'] = True
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        record['error'] = str(error); failure = str(error)
    records.append(record)
    with (series / 'trials.jsonl').open('a') as stream: stream.write(json.dumps(record) + '\n')
    print(f'{args.model}/{args.fixture} {trial + 1}/{args.warmup + args.repeat}: {"PASS" if record["passed"] else failure}', flush=True)
    if failure: break
measured = [r for r in records if not r['warmup'] and r['passed']]
summary = dict(manifest, completed_trials=len(measured), requested_trials=args.repeat, paused_at_cutoff=paused,
               accepted=failure is None and len(measured) == args.repeat, failure=failure)
for metric in ['load_ms', 'ttft_ms', 'prefill_tok_s', 'backend_decode_tok_s', 'completed_pss_kib']:
    values = [r[metric] for r in measured if r.get(metric) is not None and
              (metric == 'completed_pss_kib' or (r.get('performance_comparable_power') is True and r.get('performance_comparable_thermal') is True))]
    if values:
        stats = dict(values=values, median=statistics.median(values), minimum=min(values), maximum=max(values))
        if len(values) >= 5:
            rng = random.Random(20261024)
            bootstrap = sorted(statistics.median(rng.choices(values, k=len(values))) for _ in range(10000))
            stats['bootstrap_median_ci95'] = [bootstrap[249], bootstrap[9749]]
        summary[metric] = stats
(series / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
if records:
    columns = sorted({key for r in records for key in r})
    with (series / 'trials.csv').open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=columns); writer.writeheader(); writer.writerows(records)
print(f'Series: {series.relative_to(ROOT)}', flush=True)
raise SystemExit(1 if failure else 0)
