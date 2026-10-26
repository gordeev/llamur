#!/usr/bin/env python3
"""Run independent matmul reference checks against installed APK libraries."""
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready
import argparse
import collections
import datetime
import hashlib
import json
import pathlib
import shlex
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--gates', default='bench/numeric-correctness-gates.json')
p.add_argument('--binary', type=pathlib.Path)
p.add_argument('--apk',type=pathlib.Path,default=ROOT/'app/build/outputs/apk/debug/app-debug.apk',help='Exact installed frozen candidate APK')
p.add_argument('--extended', action='store_true', help='Q4_K/Q6_K, K up to16384 and GEMV/GEMM boundary batches')
p.add_argument('--require-repack-bitwise', action='store_true', help='Fail unless every extended repack output matches native bits')
args = p.parse_args()
if args.require_repack_bitwise and not args.extended: p.error('--require-repack-bitwise requires --extended')
lease = acquire_phone_lease()
require_phone_ready()
candidates = list((ROOT / 'app/build/intermediates/cxx/Release').glob('*/obj/arm64-v8a/motoai-numeric'))
if args.binary is None and len(candidates) != 1: p.error('Pass --binary for the matching APK build')
exe = args.binary if args.binary else candidates[0]
if not exe.is_file(): raise RuntimeError('Build the motoai-numeric target; see docs/build.md')
gates = ROOT / args.gates
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
out = ROOT / 'bench/results' / ('numeric-' + stamp)
out.mkdir(parents=True)
destination = '/data/local/tmp/motoai/m2-numeric'
def adb(*command, **kw): return subprocess.run(['adb', '-d', *command], check=True, **kw)
adb('shell', shlex.join(['mkdir', '-p', destination]))
adb('push', str(exe), destination + '/motoai-numeric', stdout=subprocess.DEVNULL)
adb('push', str(gates), destination + '/gates.json', stdout=subprocess.DEVNULL)
adb('shell', shlex.join(['chmod', '755', destination + '/motoai-numeric']))
installed = subprocess.check_output(['adb', '-d', 'shell', 'pm', 'path', 'org.motoai.runtime'], text=True).strip()
if not installed.startswith('package:') or '\n' in installed: raise RuntimeError('Expected one installed APK')
installed = installed[8:]
apk = args.apk.resolve()
if not apk.is_relative_to(ROOT):p.error('Workspace APK required')
apk_sha = hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()
actual_sha = subprocess.check_output(['adb', '-d', 'shell', shlex.join(['sha256sum', installed])], text=True).split()[0]
if actual_sha != apk_sha: raise RuntimeError('Installed APK hash mismatch')
libs = str(pathlib.PurePosixPath(installed).parent / 'lib/arm64')
command = 'LD_LIBRARY_PATH=' + shlex.quote(libs) + ' ' + shlex.join([destination + '/motoai-numeric', libs, destination + '/gates.json'])
if args.extended: command += ' --extended'
run = subprocess.run(['adb', '-d', 'shell', command], capture_output=True, text=True, timeout=180)
(out / 'stdout.txt').write_text(run.stdout); (out / 'stderr.txt').write_text(run.stderr)
report = json.loads(run.stdout[run.stdout.index('{'):])
report.update(scope='Target phone ADB shell; same native libraries as installed APK; CPU scalar reference. Experimental extra buffers remain unqualified.',
              apk_sha256=apk_sha, executable_sha256=hashlib.file_digest(exe.open('rb'), 'sha256').hexdigest(),
              gates_sha256=hashlib.sha256(gates.read_bytes()).hexdigest())
if args.require_repack_bitwise:
    report['bitwise_gate_passed'] = report.get('passed') is True and report.get('repack_compared_cases',0) > 0 and report.get('repack_nonidentical_cases') == 0
(out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({'baseline_passed': report.get('baseline_passed'), 'all_paths_passed': report['passed'],
                  'repack_compared_cases': report.get('repack_compared_cases'), 'repack_nonidentical_cases': report.get('repack_nonidentical_cases'),
                  'counts': dict(collections.Counter(case['status'] for case in report['cases'])),
                  'failures': [case for case in report['cases'] if case['status'] == 'fail']}, indent=2))
print('Results:', out.relative_to(ROOT))
raise SystemExit(0 if (report.get('bitwise_gate_passed') if args.require_repack_bitwise else report.get('baseline_passed')) else 1)
