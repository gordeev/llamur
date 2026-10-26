#!/usr/bin/env python3
"""Verify downloaded tool archives against locked publisher checksums."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
failed = False
for archive in json.loads((root / 'tool-archives.lock.json').read_text())['archives']:
    path = root / '.tools/downloads' / archive['filename']
    if not path.exists() or path.stat().st_size != archive['bytes']:
        print(f"PENDING {archive['id']}")
        failed = True
        continue
    digest = hashlib.new(archive['checksum_type'])
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != archive['checksum']:
        print(f"INVALID {archive['id']}")
        failed = True
    else:
        print(f"OK {archive['id']}")
raise SystemExit(1 if failed else 0)
