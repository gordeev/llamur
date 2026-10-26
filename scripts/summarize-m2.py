#!/usr/bin/env python3
"""Save a durable, bounded M2 ledger without treating collection as milestone acceptance."""
import hashlib
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
sha = hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()
rows = []
failed_series = []
for path in sorted((ROOT / 'bench/results').glob('series-*/summary.json')):
    try:
        summary = json.loads(path.read_text())
        records = [json.loads(line) for line in (path.parent / 'trials.jsonl').read_text().splitlines()]
    except (OSError, ValueError): continue
    trials = [r for r in records if r.get('passed') and not r.get('warmup') and r.get('apk_sha256') == sha]
    if not trials:
        if summary.get('diagnostic_artifact') or summary.get('failure_kind') == 'timeout': failed_series.append({'model': summary['model'], 'fixture': summary['fixture'], 'series': str(path.parent.relative_to(ROOT)), 'failure_kind': summary.get('failure_kind'), 'failure': summary.get('failure'), 'diagnostic_artifact': summary.get('diagnostic_artifact')})
        continue
    last = trials[-1]
    result_path = ROOT / last['artifact'] / 'result.json'
    report = json.loads(result_path.read_text())
    row = dict(model=summary['model'], fixture=summary['fixture'], kind=summary['kind'],
               series=str(path.parent.relative_to(ROOT)), data_collection_passed=summary['accepted'],
               measured_trials=len(trials), request_sha256=summary['request_sha256'],
               artifact=last['artifact'], prompt_tokens=report.get('result', {}).get('prompt_tokens'),
               generated_tokens=report.get('result', {}).get('generated_tokens'),
               finish_reason=report.get('result', {}).get('finish_reason'),
               thermal_status=report.get('thermal_status'), process_status=report.get('process_status'),
               weight_buffer_policy=report.get('load', {}).get('weight_buffer_policy'))
    quality = result_path.parent / 'quality.json'
    if quality.is_file(): row['quality'] = json.loads(quality.read_text())
    if summary['kind'] == 'expected_rejection': row['expected_error_observed'] = report.get('error_kind')
    rows.append(row)
expected = [(model, item['id']) for model in ['gemma', 'ornith']
            for item in json.loads((ROOT / 'bench/fixtures/generated' / model / 'index.json').read_text())]
observed = {(row['model'], row['fixture']) for row in rows if row['data_collection_passed']}
remaining = [dict(model=model, fixture=fixture) for model, fixture in expected if (model, fixture) not in observed]
incomplete_answers = [dict(model=row['model'], fixture=row['fixture'], quality=row.get('quality'))
                      for row in rows if row['kind'] == 'quality' and not row.get('quality', {}).get('quality_passed')]
report = {'date': '2026-10-24', 'apk_sha256': sha, 'milestone_accepted': False,
          'scope': 'M2 ledger in progress. Collection success is not whole-model quality/performance acceptance.',
          'completed': rows, 'failed_series': failed_series, 'remaining_fixtures': remaining, 'incomplete_quality_answers': incomplete_answers,
          'other_remaining': ['Corrected upstream operation-smoke filters need a final run',
                              'Larger output budget for completed binary-search answers; retain thinking',
                              'Intermediate/long-depth repeat baselines and protocol review',
                              'Per-step finite/logit reference coverage and wider quality corpus before compute changes',
                              '16K runtime and sustained/endurance belong to later acceptance'],
          'native_tests': 'bench/runtime-tests-preserve-20261024.json',
          'numeric_tests': 'bench/numeric-correctness-phone-20261024.json',
          'short_baseline': 'bench/m2-baseline-20261024.json'}
(ROOT / 'bench/m2-correctness-20261024.json').write_text(json.dumps(report, indent=2) + '\n')
print(f'M2 collected rows: {len(rows)}; remaining fixtures: {len(remaining)}; incomplete quality answers: {len(incomplete_answers)}')
