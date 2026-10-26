#!/usr/bin/env python3
"""Verify and stage a pinned GGUF into project-owned phone storage."""
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready
from phone_model import model_source, model_sha256
import argparse
import hashlib
import json
import re
import shlex
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
models = json.loads((root / 'bench/models.lock.json').read_text())['models']
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('model', choices=[m['id'] for m in models])
parser.add_argument('--target', choices=['cli', 'b0', 'app'], required=True)
args = parser.parse_args()
lease = acquire_phone_lease()
require_phone_ready()
model = next(m for m in models if m['id'] == args.model)
name = model['filename']
if Path(name).name != name or not re.fullmatch('[a-zA-Z0-9._-]+', name):
    parser.error('Invalid model filename')
source = root / 'models' / name
if source.stat().st_size != model['bytes']:
    raise SystemExit('Model download is incomplete')
digest = hashlib.sha256()
with source.open('rb') as stream:
    for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''):
        digest.update(chunk)
if digest.hexdigest() != model['sha256']:
    raise SystemExit('Local model SHA-256 mismatch')

def shell(parts):
    return ['adb', '-d', 'shell', shlex.join(parts)]

if args.target in ('b0', 'app'):
    if args.target == 'app' and model_source(name)['storage'] != 'app-private':
        if model_sha256(name) != model['sha256']: raise SystemExit('Shared model SHA-256 mismatch; source preserved')
        print('Already verified in Downloads/MotoAI/Models; no duplicate staged')
        raise SystemExit(0)
    package = 'com.example.llama.aichat' if args.target == 'b0' else 'org.motoai.runtime'
    prefix = ['run-as', package]
    directory = 'files/motoai-bench' if args.target == 'b0' else 'files/models'
    subprocess.run(shell(prefix + ['mkdir', '-p', directory]), check=True)
    destination = directory + '/' + name
    with source.open('rb') as stream:
        subprocess.run(['adb', '-d', 'shell', '-T', shlex.join(prefix +
            ['sh', '-c', 'cat > ' + shlex.quote(destination + '.part')])],
            stdin=stream, check=True, timeout=600)
else:
    revision = json.loads((root / 'upstream.lock.json').read_text())['commit']
    if not re.fullmatch('[0-9a-f]{40}', revision):
        raise SystemExit('Invalid revision')
    prefix = []
    directory = '/data/local/tmp/motoai/' + revision[:12]
    destination = directory + '/' + name
    subprocess.run(shell(['mkdir', '-p', directory]), check=True)
    subprocess.run(['adb', '-d', 'push', str(source), destination + '.part'], check=True, timeout=600)

result = subprocess.run(shell(prefix + ['sha256sum', destination + '.part']),
                        capture_output=True, text=True, check=True, timeout=180)
if result.stdout.split()[0] != model['sha256']:
    raise SystemExit('Phone model SHA-256 mismatch; partial file not promoted')
subprocess.run(shell(prefix + ['mv', destination + '.part', destination]), check=True)
print(f'Verified on phone ({args.target}): {destination}')
