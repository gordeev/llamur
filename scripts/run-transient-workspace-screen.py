#!/usr/bin/env python3
"""Finish bounded model-workspace screening from four completed quality reports."""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import sys

from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready, read_health

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
for name in ('code-nll', 'russian-nll', 'completed-task', 'filled-context'):
    p.add_argument('--' + name, type=pathlib.Path, required=True)
p.add_argument('--kernel-binary', type=pathlib.Path, required=True)
p.add_argument('--resume-speed-from', type=pathlib.Path, help='Reuse completed operator/cancellation stages from this exact APK; keep the failed screen')
p.add_argument('--precondition-native', action='store_true', help='Run a separately recorded2048-token native prefill before measured series; exclude its timings')
p.add_argument('--prefill-only', action='store_true', help='Bounded prefill screen only; do not claim decode or complete-M3 acceptance')
args = p.parse_args()
if not args.kernel_binary.is_file(): p.error('Build workspace helper first')
evidence = {key: getattr(args, key).resolve() for key in ('code_nll', 'russian_nll', 'completed_task', 'filled_context')}
for path in evidence.values():
    if not path.is_relative_to(ROOT / 'bench/results') or not path.is_file(): p.error('Completed local quality reports required')
lease = acquire_phone_lease()
health = require_phone_ready()
out = ROOT / 'bench/results' / ('transient-workspace-screen-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
out.mkdir(parents=True)
apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
report = {'passed': False, 'scope': 'Bounded Ornith CPU/I8MM workspace screen; not default-profile, other-device, broad task or endurance acceptance',
          'candidate_policy': 'native_transient_repack', 'apk_sha256': hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest(),
          'health_before': health, 'quality_evidence': {k: str(v.relative_to(ROOT)) for k, v in evidence.items()}, 'stages': []}
report.update(prefill_only=args.prefill_only, milestone_accepted=False, default_profile_promoted=False,
              decode_screened=False)
if args.prefill_only:
    report['scope'] = 'Bounded Ornith CPU/I8MM prefill-only screen. Not decode, complete-M3, default/product, other-device, broad task, cold-storage or endurance acceptance.'
(out / pathlib.Path(__file__).name).write_bytes(pathlib.Path(__file__).read_bytes())


def save():
    (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')


def run(label, command, timeout, result_file='report.json'):
    stage = {'label': label, 'command': [sys.executable, *command], 'passed': False}
    report['stages'].append(stage)
    save()
    print('Starting', label, flush=True)
    completed = subprocess.run([sys.executable, *command], cwd=ROOT, capture_output=True, text=True,
                               timeout=timeout, **lease.child_options())
    (out / (label + '.log')).write_text(completed.stdout + '\n' + completed.stderr)
    match = re.search(r'^Results: (bench/results/[^\n]+)$', completed.stdout, re.MULTILINE)
    if match: stage['report'] = match[1] + '/' + result_file
    stage['exit_code'] = completed.returncode
    save()
    if completed.returncode: raise RuntimeError('Stage failed: ' + label + '; preserve all trials, no speed acceptance')
    if label != 'quality-lock':
        if not match: raise RuntimeError('Missing stage report: ' + label)
        data = json.loads((ROOT / stage['report']).read_text())
        identity = data if result_file == 'report.json' else json.loads((ROOT / stage['report']).with_name('manifest.json').read_text())
        if data.get('passed') is not True or identity.get('apk_sha256') != report['apk_sha256']:
            raise RuntimeError('Stage identity/acceptance mismatch: ' + label)
        if label == 'native-preconditioning' and (data['result']['prompt_tokens'] != 2048 or data['result']['generated_tokens'] != 1 or
                data['load']['weight_buffers'] != 'native' or data['load'].get('transient_repack') is not False):
            raise RuntimeError('Preconditioning work/config differs')
    stage['passed'] = True
    save()
    print('Completed', label, stage.get('report', ''), flush=True)


try:
    lock = out / 'ornith-quality-gate.json'
    command = ['scripts/accept-repack-screening.py', 'ornith', '--output', str(lock)]
    for key, path in evidence.items(): command += ['--' + key.replace('_', '-'), str(path)]
    run('quality-lock', command, 30)
    gate = json.loads(lock.read_text())
    if gate.get('candidate_policy') != report['candidate_policy']: raise RuntimeError('Quality evidence is for another candidate')
    report['quality_lock'] = str(lock.relative_to(ROOT))
    if args.resume_speed_from:
        prior_path = args.resume_speed_from.resolve()
        if not prior_path.is_relative_to(ROOT / 'bench/results'): raise RuntimeError('Resume source must be a saved local screen')
        prior = json.loads(prior_path.read_text())
        if prior.get('apk_sha256') != report['apk_sha256'] or prior.get('candidate_policy') != report['candidate_policy']:
            raise RuntimeError('Resume screen is for another APK/policy')
        report['prior_screen'] = {'path':str(prior_path.relative_to(ROOT)), 'sha256':hashlib.sha256(prior_path.read_bytes()).hexdigest()}
        report['reused_stages'] = {}
        for label in ('operator-guards', 'cancellation-recovery'):
            stage = next((item for item in prior['stages'] if item['label'] == label), None)
            if stage is None:
                saved = prior.get('reused_stages', {}).get(label)
                if not saved: raise RuntimeError('Missing reused stage: ' + label)
                saved_path = (ROOT / saved['path']).resolve()
                if not saved_path.is_relative_to(ROOT / 'bench/results') or hashlib.sha256(saved_path.read_bytes()).hexdigest() != saved['sha256']:
                    raise RuntimeError('Reused stage changed')
                stage = {'passed':True, 'report':saved['path']}
            path = (ROOT / stage['report']).resolve()
            if stage.get('passed') is not True or not path.is_relative_to(ROOT / 'bench/results'): raise RuntimeError('Unqualified reused stage')
            data = json.loads(path.read_text())
            if data.get('passed') is not True or data.get('apk_sha256') != report['apk_sha256']: raise RuntimeError('Reused stage identity/acceptance mismatch')
            report['reused_stages'][label] = {'path':str(path.relative_to(ROOT)), 'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
        save()
    else:
        run('operator-guards', ['scripts/run-prefill-pack-bench.py', '--workspace', '--small-only', '--binary', str(args.kernel_binary)], 360)
        run('cancellation-recovery', ['scripts/run-transient-service-tests.py'], 360)
    if args.precondition_native:
        spec = json.loads((ROOT / 'bench/fixtures/generated/ornith/pp2048-diag.json').read_text())
        spec['config'].update(load_mode='mmap',weight_buffers='native',transient_repack=False)
        request = out / 'native-preconditioning-request.json'
        request.write_text(json.dumps(spec,indent=2)+'\n')
        report['preconditioning_scope'] = 'Separate native2048-token prefill before the full warmup+5-pair series, to approach steady thermal behavior. Excluded from speed statistics; no governor/power settings changed. All measured pairs still require matching relevant frequency caps.'
        run('native-preconditioning', ['scripts/run-app-smoke.py','ornith','--service','--request',str(request), '--record-device','--timeout','900'], 1140, 'result.json')
    speeds = [('pp512-prefill-speed-v1', 'prefill-speed')]
    if not args.prefill_only: speeds += [('short-code-mmap-v1', 'short-code-speed')]
    for fixture, label in speeds:
        run(label, ['scripts/compare-weight-buffers.py', 'ornith', fixture, '--candidate-policy', report['candidate_policy'],
                    '--repeat', '5', '--warmup', '1', '--quality-gate', str(lock)], 12 * 2040 + 300)
        if label == 'short-code-speed': report['decode_screened'] = True
    report['passed'] = True
except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
    report['error'] = str(error)
finally:
    report['health_after'] = read_health()
    if report['health_after'].get('ready') is not True or report['health_after'].get('boot_id_sha256') != health['boot_id_sha256']:
        report['passed'] = False
    save()
    print('Results:', out.relative_to(ROOT), flush=True)
    print('PASS' if report['passed'] else report.get('error', 'Device health changed'), flush=True)
raise SystemExit(0 if report['passed'] else 1)
