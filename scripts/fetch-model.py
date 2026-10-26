#!/usr/bin/env python3
"""Download a pinned model; only promote files after byte/hash verification."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def verify(path, model):
    if path.stat().st_size != model['bytes']:
        raise ValueError('Model size does not match lock file')
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != model['sha256']:
        raise ValueError('Model SHA-256 mismatch; refusing to use the file')


def main():
    models = json.loads((ROOT / 'bench/models.lock.json').read_text())['models']
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('model', choices=[model['id'] for model in models])
    args = parser.parse_args()
    model = next(m for m in models if m['id'] == args.model)
    directory = ROOT / 'models'
    directory.mkdir(exist_ok=True)
    destination = directory / model['filename']
    if destination.exists():
        verify(destination, model)
        print(destination)
        return
    partial = destination.with_suffix('.gguf.part')
    remote = model.get('remote_filename', model['filename'])
    url = f"https://huggingface.co/{model['repo']}/resolve/{model['revision']}/{remote}"
    subprocess.run(['curl', '--fail', '--location', '--http1.1', '--retry', '3',
                    '--speed-limit', '1024', '--speed-time', '30', '--connect-timeout', '30',
                    '--continue-at', '-', '--output', str(partial), url], check=True)
    verify(partial, model)
    partial.replace(destination)
    print(destination)


if __name__ == '__main__':
    main()
