#!/usr/bin/env python3
"""Move verified app-owned weights into visible Downloads/MotoAI, with no persistent duplicate."""
import argparse
import datetime
import hashlib
import json
import pathlib
import shlex
import subprocess
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready
from phone_model import model_sha256

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('models', nargs='+', choices=['gemma', 'ornith'])
args = p.parse_args()
lease = acquire_phone_lease()
require_phone_ready()
models = [m for m in json.loads((ROOT / 'bench/models.lock.json').read_text())['models'] if m['id'] in args.models]
apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
tests = ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
digest = lambda path: hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()
out = ROOT / 'bench/results' / ('model-storage-migration-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
out.mkdir(parents=True)
report = {'passed': False, 'apk_sha256': digest(apk), 'test_apk_sha256': digest(tests),
          'models': models, 'scope': 'Move only verified app-private staging files. Destination SHA checked by app before private unlink. User SAF originals never deleted.'}
def adb(*command, timeout=60, **options):
    return subprocess.run(['adb', '-d', *command], text=True, capture_output=True, timeout=timeout, check=True, **options)
try:
    installed = adb('shell', 'pm path org.motoai.runtime').stdout.strip()
    if not installed.startswith('package:') or '\n' in installed: raise RuntimeError('Expected one APK')
    if adb('shell', shlex.join(['sha256sum', installed[8:]])).stdout.split()[0] != report['apk_sha256']: raise RuntimeError('Install matching current APK first')
    for model in models:
        if model_sha256(model['filename']) != model['sha256']: raise RuntimeError('Source model differs from lock')
    adb('install', '-t', '-r', str(tests))
    adb('shell', 'am force-stop org.motoai.runtime')
    adb('shell', 'run-as org.motoai.runtime mkdir -p files/bench')
    adb('shell', '-T', shlex.join(['run-as', 'org.motoai.runtime', 'sh', '-c', 'cat > files/bench/storage-migration.json']), input=json.dumps(models))
    adb('shell', 'run-as org.motoai.runtime rm -f files/runtime-storage-tests.json')
    run = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'scope', 'storage', '-e', 'migrationFile',
              'storage-migration.json', 'org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation', timeout=900)
    (out / 'instrumentation.txt').write_text(run.stdout + '\n' + run.stderr)
    data = json.loads(adb('exec-out', 'run-as', 'org.motoai.runtime', 'cat', 'files/runtime-storage-tests.json').stdout)
    report['instrumentation'] = data
    report['passed'] = data.get('passed') is True and len(data.get('migrated', [])) == len(models)
    if report['passed']:
        report['independent_phone_sha256'] = {m['id']: model_sha256(m['filename']) for m in models}
        report['passed'] = all(report['independent_phone_sha256'][m['id']] == m['sha256'] for m in models)
except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
    report['passed'] = False
    report['error'] = str(error)
finally:
    try: adb('shell', 'am force-stop org.motoai.runtime')
    except (OSError, subprocess.SubprocessError) as error: report['cleanup_error'] = str(error)
    (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2)); print('Results:', out.relative_to(ROOT))
raise SystemExit(0 if report['passed'] else 1)
