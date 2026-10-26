#!/usr/bin/env python3
"""Deploy only project-built native tools to a revision-specific ADB shell path."""
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready
import json
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
lease = acquire_phone_lease()
require_phone_ready()
revision = json.loads((root / 'upstream.lock.json').read_text())['commit']
if not re.fullmatch('[0-9a-f]{40}', revision):
    raise SystemExit('Invalid upstream revision')
target = f'/data/local/tmp/motoai/{revision[:12]}'
binary_dir = root / 'build/android-cpu/bin'
names = ['llama-completion', 'llama-bench', 'llama-tokenize', 'test-backend-ops']
files = [binary_dir / name for name in names] + sorted(binary_dir.glob('*.so*'))
if not all(path.is_file() for path in files) or not list(binary_dir.glob('*.so*')):
    raise SystemExit('Build all CPU tools and shared libraries first')
subprocess.run(['adb', '-d', 'shell', 'mkdir', '-p', target + '/bin', target + '/lib'], check=True)
for path in files:
    directory = 'bin' if path.name in names else 'lib'
    subprocess.run(['adb', '-d', 'push', str(path), f'{target}/{directory}/{path.name}'], check=True)
subprocess.run(['adb', '-d', 'shell', 'chmod', '700'] +
               [f'{target}/bin/{name}' for name in names], check=True)
print(target)
