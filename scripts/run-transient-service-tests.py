#!/usr/bin/env python3
"""Exercise cancellation/recovery of the opt-in model workspace on the same APK."""
import datetime
import hashlib
import json
import pathlib
import subprocess

from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready, read_health
from trial_finalization import finalize_trial

ROOT = pathlib.Path(__file__).resolve().parents[1]
lease = acquire_phone_lease()
health = require_phone_ready()
out = ROOT / 'bench/results' / ('transient-service-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
out.mkdir(parents=True)
apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
tests = ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
report = {'passed': False, 'scope': 'Bounded explicit CPU-workspace cancellation/recovery, not endurance or speed acceptance',
          'apk_sha256': hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest(),
          'test_apk_sha256': hashlib.file_digest(tests.open('rb'), 'sha256').hexdigest(), 'health_before': health}
for path in ['scripts/run-transient-service-tests.py', 'scripts/phone_health.py', 'scripts/phone_lease.py',
             'scripts/trial_finalization.py', 'app/src/androidTest/java/org/motoai/runtime/ServiceChecks.kt',
             'app/src/androidTest/java/org/motoai/runtime/RuntimeInstrumentation.kt']:
    (out / pathlib.Path(path).name).write_bytes((ROOT / path).read_bytes())


def adb(*args, timeout=30, check=True):
    return subprocess.run(['adb', '-d', *args], capture_output=True, text=True, timeout=timeout, check=check)


try:
    installed = adb('shell', 'pm', 'path', 'org.motoai.runtime').stdout.strip()
    if not installed.startswith('package:') or '\n' in installed: raise RuntimeError('Expected one installed APK')
    if adb('shell', 'sha256sum', installed[8:]).stdout.split()[0] != report['apk_sha256']:
        raise RuntimeError('Installed APK differs from quality-tested local artifact')
    adb('install', '-t', '-r', str(tests), timeout=60)
    adb('shell', 'am', 'force-stop', 'org.motoai.runtime')
    adb('shell', 'run-as', 'org.motoai.runtime', 'rm', '-f', 'files/runtime-transient-service-tests.json')
    run = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'scope', 'transient-service',
              'org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation', timeout=240)
    (out / 'instrumentation.txt').write_text(run.stdout + '\n' + run.stderr)
    data = json.loads(adb('exec-out', 'run-as', 'org.motoai.runtime', 'cat', 'files/runtime-transient-service-tests.json').stdout)
    report['runtime'] = data
    for key in ('first_report_id', 'cancelled_report_id', 'recovered_report_id'):
        identifier = data.get('service', {}).get(key)
        if identifier:
            if not identifier.startswith('transient-') or any(c not in '0123456789abcdef-transient' for c in identifier):
                raise RuntimeError('Invalid test report ID')
            raw = adb('exec-out', 'run-as', 'org.motoai.runtime', 'cat', f'files/service-result-{identifier}.json')
            (out / (key + '.json')).write_text(raw.stdout)
    report['health_after'] = read_health()
    report['passed'] = (data.get('passed') is True and data.get('service', {}).get('passed') is True and
                        report['health_after'].get('ready') is True and
                        report['health_after'].get('boot_id_sha256') == health['boot_id_sha256'])
except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
    report['error'] = str(error)
finally:
    try:
        finalize_trial(out, [('stop-motoai', lambda: adb('shell', 'am', 'force-stop', 'org.motoai.runtime', check=False))],
                       primary_error_active=not report['passed'])
    except Exception as error:
        report['passed'] = False
        report['cleanup_error'] = str(error)
    if (out / 'finalization-errors.json').exists(): report['passed'] = False
    (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Results:', out.relative_to(ROOT), flush=True)
    print('PASS' if report['passed'] else report.get('error', 'FAIL'), flush=True)
raise SystemExit(0 if report['passed'] else 1)
