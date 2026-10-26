#!/usr/bin/env python3
"""Materialize the reviewed B1 integration patch while preserving clean B0."""
import hashlib
import json
import argparse
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
series = json.loads((root / 'patches/android-b1/series.json').read_text())
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--variant', choices=series['variants'], default='B1-jinja')
args = parser.parse_args()
variant = series['variants'][args.variant]
source = root / variant['directory']
if not source.resolve().is_relative_to((root / '.tools').resolve()):
    raise SystemExit('Generated checkout must remain inside .tools')
if not source.exists():
    subprocess.run(['git', 'clone', '--shared', str(root / 'third_party/llama.cpp'), str(source)], check=True)
actual = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
if actual != series['upstream_commit']:
    raise SystemExit('B1 checkout revision differs from the locked source')
patches = []
for item in variant['patches']:
    path = root / item['path']
    if hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
        raise SystemExit('B1 patch hash mismatch')
    patches.append(path)
diff = subprocess.check_output(['git', '-C', str(source), 'diff'], text=True)
if not diff:
    for path in patches:
        subprocess.run(['git', '-C', str(source), 'apply', '--check', str(path)], check=True)
        subprocess.run(['git', '-C', str(source), 'apply', str(path)], check=True)
    diff = subprocess.check_output(['git', '-C', str(source), 'diff'], text=True)
if hashlib.sha256(diff.encode()).hexdigest() != variant['diff_sha256']:
    raise SystemExit('B1 contains changes outside the reviewed patch series')
print(source)
