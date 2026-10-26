#!/usr/bin/env python3
"""Write ignored Android local.properties for a project inside this workspace."""
import argparse
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--project', type=Path, default=Path('.'))
parser.add_argument('--sdk', type=Path, required=True)
parser.add_argument('--ndk', type=Path, required=True)
parser.add_argument('--cmake', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
project = args.project.resolve()
if not project.is_relative_to(root):
    parser.error('Project must be inside the workspace')
values = {'sdk.dir': args.sdk.resolve(), 'ndk.dir': args.ndk.resolve(),
          'cmake.dir': args.cmake.resolve()}
for key, value in values.items():
    if not value.is_dir():
        parser.error(f'{key} is not an existing directory: {value}')
def escape(value):
    return str(value).replace('\\', '\\\\').replace(':', '\\:')
(project / 'local.properties').write_text(''.join(
    f'{key}={escape(value)}\n' for key, value in values.items()))
