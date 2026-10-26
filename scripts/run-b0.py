#!/usr/bin/env python3
"""Run a short fixture against the unmodified B0 native JNI in its app UID."""
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready
import argparse
import datetime
import json
import shlex
import subprocess
import uuid
from pathlib import Path

root = Path(__file__).resolve().parents[1]
models = json.loads((root / 'bench/models.lock.json').read_text())['models']
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('model', choices=[m['id'] for m in models])
parser.add_argument('--tokens', type=int, default=32)
parser.add_argument('--variant', choices=['B0', 'B1-jinja', 'B1-jinja-no-mmap'], default='B0')
parser.add_argument('--foreground', action='store_true')
parser.add_argument('--prompt', default='Write a Python function that adds two integers.')
args = parser.parse_args()
lease = acquire_phone_lease()
require_phone_ready()
if not 1 <= args.tokens <= 256 or len(args.prompt) > 2048:
    parser.error('Only short baseline fixtures are supported')
model = next(m for m in models if m['id'] == args.model)
run_id = uuid.uuid4().hex
started = datetime.datetime.now(datetime.timezone.utc)
directory = root / 'bench/results' / ('b0-' + args.model + '-' + started.strftime('%Y%m%dT%H%M%SZ') + '-' + run_id[:8])
directory.mkdir(parents=True, exist_ok=False)
command = ['am', 'instrument', '-w', '-r', '-e', 'model', model['filename'],
           '-e', 'tokens', str(args.tokens), '-e', 'prompt', args.prompt,
           '-e', 'runId', run_id, '-e', 'variant', args.variant,
           '-e', 'foreground', str(args.foreground).lower(),
           'org.motoai.baseline/.BaselineInstrumentation']
record = dict(run_id=run_id, started_utc=started.isoformat(), model=model,
              variant=args.variant,
              upstream=json.loads((root / 'upstream.lock.json').read_text()),
              scope='single mechanical native JNI smoke, not a quality/performance conclusion')
passed = False
try:
    variant = json.loads((root / 'bench/android-variants.json').read_text())[args.variant]
    installed = subprocess.check_output(['adb', '-d', 'shell', 'pm', 'path',
                                         'com.example.llama.aichat'], text=True).strip()
    if not installed.startswith('package:') or '\n' in installed:
        raise ValueError('Expected a single installed baseline APK')
    installed_hash = subprocess.check_output(['adb', '-d', 'shell', 'sha256sum', installed[8:]],
                                            text=True).split()[0]
    if installed_hash != variant['sha256']:
        raise ValueError('Installed APK does not match the selected baseline variant')
    record['apk'] = variant
    result = subprocess.run(['adb', '-d', 'shell', shlex.join(command)],
                            capture_output=True, text=True, timeout=900)
    (directory / 'instrumentation.txt').write_text(result.stdout + '\n' + result.stderr)
    record['adb_exit_code'] = result.returncode
    response = subprocess.run(['adb', '-d', 'exec-out', 'run-as', 'com.example.llama.aichat',
                               'cat', f'files/motoai-b0-result-{run_id}.json'],
                              capture_output=True, text=True, timeout=30, check=True)
    if not response.stdout.strip():
        raise ValueError('No report: inspect instrumentation.txt for native crash or process termination')
    report = json.loads(response.stdout)
    if report.get('run_id') != run_id or report.get('model') != model['filename'] or report.get('variant') != args.variant:
        raise ValueError('Stale or mismatched report')
    record['report'] = report
    passed = result.returncode == 0 and report.get('passed') is True
except (subprocess.SubprocessError, ValueError) as error:
    record['error'] = str(error)
    progress = subprocess.run(['adb', '-d', 'exec-out', 'run-as', 'com.example.llama.aichat',
                               'cat', f'files/motoai-b0-progress-{run_id}.json'],
                              capture_output=True, text=True, timeout=30)
    (directory / 'progress.txt').write_text(progress.stdout + progress.stderr)
    exit_info = subprocess.run(['adb', '-d', 'shell', 'dumpsys', 'activity', 'exit-info',
                                'com.example.llama.aichat'], capture_output=True, text=True, timeout=30)
    (directory / 'exit-info.txt').write_text(exit_info.stdout + exit_info.stderr)
record['passed'] = passed
(directory / 'result.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n')
print(directory)
print(json.dumps(record.get('report', {'error': record.get('error')}), ensure_ascii=False, indent=2))
raise SystemExit(0 if passed else 1)
