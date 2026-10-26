#!/usr/bin/env python3
"""Export build inputs and pristine upstream files; never include local models or credentials."""
import argparse
import hashlib
import io
import json
import pathlib
import shutil
import subprocess
import tarfile
from source_tree import source_tree

ROOT=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('destination',type=pathlib.Path)
args=parser.parse_args()
destination=args.destination.resolve()
if not destination.is_relative_to(ROOT/'build') or destination.exists(): parser.error('Use a fresh path inside build/')
lock=json.loads((ROOT/'upstream.lock.json').read_text());upstream=ROOT/lock['path']
if subprocess.check_output(['git','-C',str(upstream),'rev-parse','HEAD'],text=True).strip()!=lock['commit']: raise RuntimeError('Upstream revision differs')
subprocess.run(['git','-C',str(upstream),'diff','--quiet'],check=True)
subprocess.run(["python3",str(ROOT/"scripts/check-public-source.py")],check=True)
destination.mkdir(parents=True)
roots=['app/src','baseline-driver/src','native-runtime','scripts','patches','licenses','docs','gradle','bench','fastlane','fdroid']
files=[ROOT/name for name in ['.gitignore','LICENSE','README.md','build.gradle.kts','settings.gradle.kts',
       'gradle.properties','gradlew','app/build.gradle.kts','baseline-driver/build.gradle.kts',
       'upstream.lock.json','toolchain.lock.json','tool-archives.lock.json','native-dependencies.lock.json']]
for name in roots:
    files.extend(path for path in (ROOT/name).rglob('*') if path.is_file() and '__pycache__' not in path.parts and
                 not path.is_relative_to(ROOT/'bench/results'))
manifest={}
for source in sorted(set(files)):
    relative=source.relative_to(ROOT);target=destination/relative
    if source.is_symlink(): raise RuntimeError('Unexpected build-input symlink: '+str(relative))
    target.parent.mkdir(parents=True,exist_ok=True)
    data=source.read_bytes();target.write_bytes(data);target.chmod(0o755 if source.stat().st_mode&0o111 else 0o644)
    manifest[str(relative)]=hashlib.sha256(data).hexdigest()
archive=subprocess.check_output(['git','-C',str(upstream),'archive','--format=tar',lock['commit']])
exported=destination/lock['path'];exported.mkdir(parents=True)
with tarfile.open(fileobj=io.BytesIO(archive)) as tar: tar.extractall(exported,filter='data')
actual=source_tree(exported)
if actual!=lock['tree']: raise RuntimeError('Raw upstream source export differs from locked tree')
report=dict(scope='Build input snapshot, not a release/signing/reproducibility acceptance',upstream_commit=lock['commit'],upstream_tree=actual,files=manifest)
(destination.parent/(destination.name+'-manifest.json')).write_text(json.dumps(report,indent=2)+'\n')
print(destination)
