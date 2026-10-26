#!/usr/bin/env python3
"""Reconcile resumed M2 evidence, preserving failed/unfinished trials and APK ids."""
import hashlib
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
rows, comparisons, repeats, controls = [], [], [], []
for path in sorted((ROOT / 'bench/results').glob('app-service-*/manifest.json')):
    stamp = re.search(r'(\d{8}T\d{6}Z)', path.parent.name)
    if not stamp or stamp.group(1) < '20261024T183500Z': continue
    manifest = json.loads(path.read_text())
    row = {'artifact': str(path.parent.relative_to(ROOT)), 'model': manifest['model']['id'],
           'model_sha256': manifest['model']['sha256'], 'apk_sha256': manifest['apk_sha256'],
           'fixture': pathlib.Path(manifest['arguments'].get('request') or 'direct-smoke').stem,
           'status': 'in_progress', 'collection_passed': False}
    result_path, failure_path = path.parent / 'result.json', path.parent / 'failure.json'
    if result_path.is_file():
        try:
            report = json.loads(result_path.read_text())
            result = report.get('result', {})
            row.update(status='completed' if report.get('passed') else 'failed', collection_passed=report.get('passed', False),
                       mode=result.get('mode', 'generation'), prompt_tokens=result.get('prompt_tokens'),
                       output_tokens=result.get('generated_tokens'), finish_reason=result.get('finish_reason'),
                       finite_logit_checks=result.get('logits_checks'), prefill_us=result.get('prefill_us'),
                       reset_us=result.get('reset_us'), prefill_chunks=result.get('prefill_chunks'),
                       thermal_status=report.get('thermal_status'), buffer_allocations=report.get('load', {}).get('buffer_allocations'),
                       arm64_fpcr=result.get('arm64_fpcr'))
        except (OSError, ValueError): pass
    if failure_path.is_file():
        row.update(status='failed', failure=json.loads(failure_path.read_text()))
    quality = path.parent / 'quality.json'
    if quality.is_file(): row['quality'] = json.loads(quality.read_text())
    progress = path.parent / 'last-progress.json'
    if progress.is_file(): row['last_progress'] = json.loads(progress.read_text())
    invalid = path.parent / 'invalidated.json'
    if invalid.is_file(): row.update(status='invalidated', collection_passed=False, invalidated=json.loads(invalid.read_text()))
    rows.append(row)
for path in sorted((ROOT / 'bench/results').glob('score-*/comparison.json')):
    data = json.loads(path.read_text())
    comparisons.append(dict(data, artifact=str(path.parent.relative_to(ROOT))))
for path in sorted((ROOT / 'bench/results').glob('score-repeat-*/repeatability.json')):
    data = json.loads(path.read_text())
    repeats.append(dict(data, artifact=str(path.parent.relative_to(ROOT))))
for path in sorted((ROOT / 'bench/results').glob('host-libm-control-*/control.json')):
    controls.append(dict(json.loads(path.read_text()), artifact=str(path.parent.relative_to(ROOT))))
for path in sorted((ROOT / 'bench/results').glob('host-gelu-control-*/control.json')):
    controls.append(dict(json.loads(path.read_text()), artifact=str(path.parent.relative_to(ROOT))))
acceptance_path = ROOT / 'bench/m2-acceptance-20261025.json'
accepted = acceptance_path.is_file() and json.loads(acceptance_path.read_text()).get('milestone_accepted') is True
report = {
    'schema_version': 1, 'session_resumed_utc': '2026-10-24T18:35:00Z', 'milestone_accepted': accepted,
    'milestone_acceptance_report': str(acceptance_path.relative_to(ROOT)) if acceptance_path.is_file() else None,
    'scope': 'Resumed evidence; bounded M2 harness/reference acceptance is separate from optimized/product/sustained profiles. Different APKs and failed comparisons stay identified; no timing pooling.',
    'current_local_apk_sha256': hashlib.file_digest((ROOT / 'app/build/outputs/apk/debug/app-debug.apk').open('rb'), 'sha256').hexdigest(),
    'trials': rows, 'host_phone_scoring': comparisons, 'same_phone_repeatability': repeats, 'host_component_controls': controls,
    'tests': {'runtime': 'bench/results/runtime-tests-20261024T220801Z/report.json',
              'expanded_operations': 'bench/results/backend-smoke-20261024T211948Z/report.json',
              'independent_scalar_numeric': 'bench/results/numeric-20261024T211947Z/report.json',
              'independent_attention': 'bench/results/attention-variant-matrix-20261024T211942Z/report.json',
              'python_tests': '36 owned harness tests passed, including phone health/boot and power-state transition, evaluator, lease, process-death and failed-cleanup checks',
              'native_host': 'reference-host ctest1/1 passed, including actual BOS-only trace regression'},
    'initial_baseline': 'bench/m2-baseline-20261024.json',
    'cpu_profile': 'bench/cpu-prefill-profile-20261024.json',
    'memory_load': 'bench/memory-load-investigation-20261024.json',
    'numeric_investigation': 'bench/cross-cpu-investigation-20261024.json',
    'bounded_numeric_correction': 'bench/m2-numeric-fix-20261024.json',
    'debug_replay_guard': 'bench/one-shot-build-20261024.json',
    'shared_model_storage': 'bench/model-storage-pfd-verification-20261024.json',
    'layer6_investigation': 'bench/layer6-investigation-20261024.json',
    'activation_tables': 'bench/activation-tables-20261024.json',
    'remaining': ['M3 CPU_REPACK allowlist/representation/memory screening; no optimized model profile accepted yet',
                  'Admission/load-pressure cancellation, four fresh Ornith mmap scores and long tasks pass; changing real memory pressure and endurance remain limited',
                  'Both lower_bound tasks, corrected Gemma filled8K and Ornith beginning retrieval pass; extend the position/model/corpus matrix per finalist',
                  'Representative wider coding corpus and sampling seeds; original and corrected reference baselines remain separate',
                  'Sustained/battery/16K-page acceptance remains later; no optimization claim']}
destination = ROOT / 'bench/m2-resume-20261024.json'
destination.write_text(json.dumps(report, indent=2) + '\n')
print(f'Resumed evidence: {len(rows)} APK artifacts, {len(comparisons)} cross-CPU comparisons, {len(repeats)} repeat series; bounded M2 accepted={accepted}; no speed-profile acceptance')
