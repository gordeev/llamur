#!/usr/bin/env python3
"""Bounded single-matrix transient-pack feasibility; never APK speed acceptance."""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import shlex
import subprocess
import uuid
import zipfile

from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready, read_health, comparable_power
from trial_finalization import finalize_trial
from frequency_caps import POLICY_COMMAND, parse_policies, screen_caps

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--binary', type=pathlib.Path, required=True)
selection = p.add_mutually_exclusive_group()
selection.add_argument('--small-only', action='store_true', help='Correctness/allocator boundary probe, omit model-sized matrices')
selection.add_argument('--amortization', action='store_true', help='Model-sized N4/8/16/17/32/33/64 matrix curve, including GEMV tails; not app speed')
p.add_argument('--amortization-rows', nargs='+', type=int, help='Explicit1..8 unique row counts4..512 for a bounded amortization follow-up')
p.add_argument('--workspace', action='store_true', help='Explicit experimental per-op CPU graph workspace, native source buffers')
args = p.parse_args()
if args.amortization_rows is not None and (not args.amortization or not 1 <= len(args.amortization_rows) <= 8 or
        len(set(args.amortization_rows)) != len(args.amortization_rows) or any(n < 4 or n > 512 for n in args.amortization_rows)):
    p.error('Explicit amortization rows require --amortization and1..8 unique values4..512')
if not args.binary.is_file(): p.error('Build motoai-prefill-pack-bench first')
lease = acquire_phone_lease()
health = require_phone_ready()
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
out = ROOT / 'bench/results' / ('prefill-pack-' + stamp)
out.mkdir(parents=True)
remote = '/data/local/tmp/motoai-prefill-pack-' + uuid.uuid4().hex
gates_path = ROOT / 'bench/numeric-correctness-gates.json'
gates = json.loads(gates_path.read_text())['types']
shapes_path = ROOT / 'bench/prefill-pack-shapes-20261025.json'
shapes = json.loads(shapes_path.read_text())
locked_model = next(m for m in json.loads((ROOT / 'bench/models.lock.json').read_text())['models'] if m['id'] == 'ornith')
if shapes['model_sha256_from_lock'] != locked_model['sha256']: raise RuntimeError('Shape metadata is for another model revision')
cases = [{'type': t, 'k': 256, 'm': 32, 'n': n, 'numeric_gate': gates[t]}
         for t in ('q4_K', 'q6_K') for n in (1, 3, 4, 5, 16, 128)]
if args.amortization:
    cases = [{'type':t, 'k':shapes['largest_linear_shapes'][t]['k'], 'm':shapes['largest_linear_shapes'][t]['m'],
              'n':n, 'numeric_gate':gates[t]} for t in ('q4_K','q6_K') for n in (args.amortization_rows or (4,8,16,17,32,33,64))]
elif not args.small_only:
    cases += [{'type': t, 'k': shapes['largest_linear_shapes'][t]['k'], 'm': shapes['largest_linear_shapes'][t]['m'], 'n': n, 'numeric_gate': gates[t]}
              for t in ('q4_K', 'q6_K') for n in (128, 512)]
spec = {'threads': 4, 'cpus': list(range(7)), 'cases': cases,
        'mode': 'cpu_graph_workspace' if args.workspace else 'allocate_repack_free'}
(out / 'spec.json').write_text(json.dumps(spec, indent=2) + '\n')
(out / 'numeric-gates.json').write_bytes(gates_path.read_bytes())
(out / 'shape-metadata.json').write_bytes(shapes_path.read_bytes())
for name in ('run-prefill-pack-bench.py', 'phone_health.py', 'phone_lease.py', 'trial_finalization.py', 'frequency_caps.py'):
    (out / name).write_bytes((ROOT / 'scripts' / name).read_bytes())
(out / 'prefill-pack-bench.cpp').write_bytes((ROOT / 'native-runtime/tools/prefill-pack-bench.cpp').read_bytes())
apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
apk_sha = hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()
report = {'passed': False, 'scope': 'ADB shell synthetic kernel feasibility; own affinity intersects allowed CPUs0–6. Different lifecycle/cgroup from APK. Not model quality, app speed, stability, or product acceptance.',
          'apk_sha256': apk_sha, 'executable_sha256': hashlib.file_digest(args.binary.open('rb'), 'sha256').hexdigest(),
          'gates_sha256': hashlib.sha256(gates_path.read_bytes()).hexdigest(),
          'shape_metadata_sha256': hashlib.sha256(shapes_path.read_bytes()).hexdigest(),
          'health_before': health, 'app_speed_accepted': False}


def adb(*arguments, check=True, timeout=30):
    return subprocess.run(['adb', '-d', *arguments], capture_output=True, text=True, timeout=timeout, check=check)


def thermal(when):
    observed = adb('shell', 'dumpsys', 'thermalservice', check=False)
    (out / ('thermal-' + when + '.txt')).write_text(observed.stdout + '\n' + observed.stderr)
    match = re.search(r'^Thermal Status:\s*(\d+)\s*$', observed.stdout, re.MULTILINE)
    return int(match[1]) if observed.returncode == 0 and match else None


def policies(when):
    observed = adb('shell', POLICY_COMMAND, check=False)
    (out / ('cpu-policies-' + when + '.txt')).write_text(observed.stdout + '\n' + observed.stderr)
    return parse_policies(observed.stdout)


try:
    installed = adb('shell', 'pm', 'path', 'org.motoai.runtime').stdout.strip()
    if not installed.startswith('package:') or '\n' in installed: raise RuntimeError('Expected one installed APK')
    if adb('shell', shlex.join(['sha256sum', installed[8:]])).stdout.split()[0] != apk_sha:
        raise RuntimeError('Installed APK differs')
    libraries = str(pathlib.PurePosixPath(installed[8:]).parent / 'lib/arm64')
    adb('shell', 'mkdir', '-p', remote)
    adb('push', str(args.binary), remote + '/bench')
    adb('push', str(out / 'spec.json'), remote + '/spec.json')
    adb('shell', 'chmod', '755', remote + '/bench')
    command = 'LD_LIBRARY_PATH=' + shlex.quote(libraries) + ' ' + shlex.join([remote + '/bench', libraries, remote + '/spec.json'])
    report['command'] = command
    report['thermal_before'] = thermal('before')
    report['frequency_policies_before'] = policies('before')
    run = adb('shell', command, check=False, timeout=300)
    (out / 'stdout.txt').write_text(run.stdout)
    (out / 'stderr.txt').write_text(run.stderr)
    report['exit_code'] = run.returncode
    raw = json.loads(run.stdout)
    (out / 'kernel-report.json').write_text(json.dumps(raw, indent=2) + '\n')
    report['kernel_passed'] = raw.get('passed') is True
    selected = raw.get('cpu_backend_library', '')
    if pathlib.PurePosixPath(selected).parent != pathlib.PurePosixPath(libraries):
        raise RuntimeError('CPU backend did not load from installed APK')
    library_name = pathlib.PurePosixPath(selected).name
    with zipfile.ZipFile(apk) as archive:
        expected = hashlib.sha256(archive.read('lib/arm64-v8a/' + library_name)).hexdigest()
    actual = adb('shell', shlex.join(['sha256sum', selected])).stdout.split()[0]
    if actual != expected: raise RuntimeError('Selected CPU library differs from APK')
    report['cpu_library_sha256'] = actual
    report['health_after'] = read_health()
    report['thermal_after'] = thermal('after')
    report['frequency_policies_after'] = policies('after')
    report['frequency_screen'] = screen_caps(report['frequency_policies_before'], report['frequency_policies_after'], raw.get('cpus', []))
    report['power_comparable'] = comparable_power(health['battery'], report['health_after'].get('battery', {}))
    if run.returncode or not report['kernel_passed']: raise RuntimeError('Kernel gate failed; preserve all cases, no speed acceptance')
    if len(raw.get('cases', [])) != len(cases): raise RuntimeError('Missing kernel cases')
    if args.workspace and len(raw.get('eligibility_checks', [])) != 10:
        raise RuntimeError('Missing transient workspace eligibility/cap boundary checks')
    if raw.get('threads') != spec['threads'] or not raw.get('cpus') or any(v not in spec['cpus'] for v in raw['cpus']):
        raise RuntimeError('Wrong thread/CPU configuration')
    temperatures = [h.get('battery', {}).get('temperature_c') for h in (health, report['health_after'])]
    report['timings_screened'] = (report['power_comparable'] and report['frequency_screen']['boundary_caps_comparable'] and
        all(t is not None and t < 2 for t in (report['thermal_before'], report['thermal_after'])) and
        None not in temperatures and abs(temperatures[0] - temperatures[1]) <= 2 and
        report['health_after'].get('ready') is True and health.get('boot_id_sha256') is not None and
        report['health_after'].get('boot_id_sha256') == health['boot_id_sha256'])
    if not report['timings_screened']: raise RuntimeError('Kernel checks passed but power/temperature/boot conditions do not qualify timing')
    report['passed'] = True
except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
    report['error'] = str(error)
finally:
    try:
        report['cleanup_errors'] = finalize_trial(out, [('remove-own-directory', lambda: adb('shell', 'rm', '-rf', remote, check=False))], primary_error_active=not report['passed'])
    except Exception as error:
        report['passed'] = False
        report['cleanup_error'] = str(error)
    finally:
        if (out / 'finalization-errors.json').exists(): report['passed'] = False
        (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print('Results:', out.relative_to(ROOT))
        print('PASS' if report['passed'] else report.get('error', report.get('cleanup_error', 'FAIL')))
raise SystemExit(0 if report['passed'] else 1)
