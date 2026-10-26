#!/usr/bin/env python3
"""Bounded upstream operation smoke using the installed APK's native libraries."""
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import shlex
import subprocess
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--binary', type=pathlib.Path, help='Explicit executable built against the current APK patch variant')
args = p.parse_args()
lease = acquire_phone_lease()
require_phone_ready()
candidates = list((ROOT / 'app/build/intermediates/cxx/Release').glob('*/obj/arm64-v8a/motoai-backend-ops'))
if args.binary is None and len(candidates) != 1: raise RuntimeError('Pass --binary for the matching APK patch variant')
exe = args.binary if args.binary else candidates[0]
installed = subprocess.check_output(['adb', '-d', 'shell', 'pm', 'path', 'org.motoai.runtime'], text=True).strip().removeprefix('package:')
libs = str(pathlib.PurePosixPath(installed).parent / 'lib/arm64')
apk_sha = hashlib.file_digest((ROOT / 'app/build/outputs/apk/debug/app-debug.apk').open('rb'), 'sha256').hexdigest()
if subprocess.check_output(['adb', '-d', 'shell', shlex.join(['sha256sum', installed])], text=True).split()[0] != apk_sha:
    raise RuntimeError('Installed APK mismatch')
directory = '/data/local/tmp/motoai/m2-ops'
subprocess.run(['adb', '-d', 'shell', shlex.join(['mkdir', '-p', directory])], check=True)
subprocess.run(['adb', '-d', 'push', str(exe), directory + '/backend-ops'], check=True, stdout=subprocess.DEVNULL)
subprocess.run(['adb', '-d', 'shell', shlex.join(['chmod', '755', directory + '/backend-ops'])], check=True)
with zipfile.ZipFile(ROOT / 'app/build/outputs/apk/debug/app-debug.apk') as archive:
    for name in archive.namelist():
        leaf = pathlib.PurePosixPath(name).name
        if name.startswith('lib/arm64-v8a/libggml-cpu-') and name.endswith('.so'):
            subprocess.run(['adb', '-d', 'shell', shlex.join(['ln', '-sf', libs + '/' + leaf, directory + '/' + leaf])], check=True)
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
out = ROOT / 'bench/results' / ('backend-smoke-' + stamp); out.mkdir()
records = []
for index, case in enumerate(json.loads((ROOT / 'bench/operation-smoke.json').read_text())):
    command = 'LD_LIBRARY_PATH=' + shlex.quote(libs) + ' ' + shlex.join([directory + '/backend-ops', '-b', 'CPU', '-o', case['op'], '-p', case['params']])
    run = subprocess.run(['adb', '-d', 'shell', command], capture_output=True, text=True, timeout=90)
    text = run.stdout + run.stderr
    (out / (case.get('id', case['op'] + '-' + str(index)) + '.txt')).write_text(text)
    counts = re.findall(r'(\d+)/(\d+) tests passed', text)
    passed = run.returncode == 0 and bool(counts) and all(int(total) > 0 and good == total for good, total in counts)
    records.append(dict(case, passed=passed, counts=counts, exit_code=run.returncode))
    print(case['op'], passed, counts, flush=True)
report = {'scope': 'Upstream CPU-vs-CPU layout/sentinel smoke; not independent numeric proof. Independent scalar matmul is separate.',
          'apk_sha256': apk_sha, 'executable_sha256': hashlib.file_digest(exe.open('rb'), 'sha256').hexdigest(),
          'passed': all(record['passed'] for record in records), 'cases': records}
(out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
print('Results:', out.relative_to(ROOT))
raise SystemExit(0 if report['passed'] else 1)
