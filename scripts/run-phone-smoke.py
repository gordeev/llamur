#!/usr/bin/env python3
"""Run the tiny fixture on the phone; this is not a main-model speed result."""
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready
import datetime
import hashlib
import json
import re
import shlex
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
lease = acquire_phone_lease()
require_phone_ready()
revision = json.loads((root / 'upstream.lock.json').read_text())['commit']
if not re.fullmatch('[0-9a-f]{40}', revision):
    raise SystemExit('Invalid upstream revision')
model = next(m for m in json.loads((root / 'bench/models.lock.json').read_text())['models']
             if m['id'] == 'smoke')
path = root / 'models' / model['filename']
if path.stat().st_size != model['bytes'] or hashlib.sha256(path.read_bytes()).hexdigest() != model['sha256']:
    raise SystemExit('Smoke fixture integrity check failed')
target = f'/data/local/tmp/motoai/{revision[:12]}'
subprocess.run(['adb', '-d', 'push', str(path), target + '/smoke.gguf'], check=True)
args = [target + '/bin/llama-completion', '-m', target + '/smoke.gguf',
        '-p', 'Once upon a time', '-n', '16', '-c', '512', '-t', '2',
        '--seed', '42', '--temp', '0', '--offline', '--no-display-prompt']
command = f'LD_LIBRARY_PATH={shlex.quote(target + "/lib")} ' + shlex.join(args)
started = datetime.datetime.now(datetime.timezone.utc)
result = subprocess.run(['adb', '-d', 'shell', command], capture_output=True, text=True, timeout=120)
record = dict(scope='tiny fixture smoke; not main-model performance',
              started_utc=started.isoformat(), upstream_commit=revision,
              model_sha256=model['sha256'], command=command,
              exit_code=result.returncode, stdout=result.stdout, stderr=result.stderr)
failed = result.returncode != 0 or not result.stdout.strip() or bool(re.search(
    r'error loading model|failed to load model|failed to create.*context', result.stderr, re.I))
record['passed'] = not failed
destination = root / 'bench/results' / ('smoke-' + started.strftime('%Y%m%dT%H%M%SZ'))
destination.mkdir(parents=True, exist_ok=False)
(destination / 'result.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n')
print(destination)
print(result.stdout)
raise SystemExit(1 if failed else 0)
