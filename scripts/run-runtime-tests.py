#!/usr/bin/env python3
"""Run framework/JNI lifecycle tests under the same exclusive phone lease."""
import argparse
import datetime
import hashlib
import json
import pathlib
import subprocess

from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready

ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk', type=pathlib.Path, default=ROOT/'app/build/outputs/apk/debug/app-debug.apk')
parser.add_argument('--tests', type=pathlib.Path, default=ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
parser.add_argument('--require-ephemeral', action='store_true')
args = parser.parse_args()
if any(not p.resolve().is_relative_to(ROOT) for p in (args.apk,args.tests)): parser.error('Expected workspace APKs')
lease = acquire_phone_lease()
require_phone_ready()
out = ROOT / 'bench/results' / ('runtime-tests-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
out.mkdir(parents=True)
apk = args.apk.resolve()
tests = args.tests.resolve()
report = {'passed': False, 'apk_sha256': hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest(),
          'test_apk_sha256': hashlib.file_digest(tests.open('rb'), 'sha256').hexdigest()}

for path in ['scripts/run-runtime-tests.py', 'app/src/androidTest/java/org/motoai/runtime/RuntimeInstrumentation.kt',
             'app/src/androidTest/java/org/motoai/runtime/ServiceChecks.kt', 'app/src/androidTest/java/org/motoai/runtime/EphemeralChecks.kt']:
    (out/pathlib.Path(path).name).write_bytes((ROOT/path).read_bytes())


def adb(*arguments, timeout=60, check=True):
    return subprocess.run(['adb', '-d', *arguments], capture_output=True, text=True, timeout=timeout, check=check)


try:
    installed = adb('shell', 'pm', 'path', 'org.motoai.runtime').stdout.strip()
    if not installed.startswith('package:') or '\n' in installed: raise RuntimeError('Expected one installed APK')
    if adb('shell', 'sha256sum', installed[8:]).stdout.split()[0] != report['apk_sha256']:
        raise RuntimeError('Install the current app APK before testing')
    adb('install', '-t', '-r', str(tests))
    adb('shell', 'am', 'force-stop', 'org.motoai.runtime')
    adb('shell', 'run-as', 'org.motoai.runtime', 'rm', '-f', 'files/runtime-tests.json')
    completed = adb('shell', 'am', 'instrument', '-w', '-r',
                    'org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation', timeout=600)
    (out / 'instrumentation.txt').write_text(completed.stdout + '\n' + completed.stderr)
    data = json.loads(adb('exec-out', 'run-as', 'org.motoai.runtime', 'cat', 'files/runtime-tests.json').stdout)
    report['runtime'] = data
    report['passed'] = (data.get('passed') is True and data.get('cycles') == 30 and data.get('service', {}).get('passed') is True and
                        (not args.require_ephemeral or data.get('ephemeral', {}).get('passed') is True))
except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
    report['error'] = str(error)
finally:
    try:
        (out / 'exit-info.txt').write_text(adb('shell', 'dumpsys', 'activity', 'exit-info', 'org.motoai.runtime', check=False).stdout)
    except (OSError, subprocess.SubprocessError) as error:
        report['diagnostic_error'] = str(error)
    try:
        adb('shell', 'am', 'force-stop', 'org.motoai.runtime', check=False)
    except (OSError, subprocess.SubprocessError) as error:
        report['cleanup_error'] = str(error)
        report['passed'] = False
    (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2), flush=True)
    print('Results:', out.relative_to(ROOT), flush=True)
raise SystemExit(0 if report['passed'] else 1)
