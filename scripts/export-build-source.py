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
lock_paths = ['upstream.lock.json', 'upstream-v0.9.0.lock.json']
upstreams = []
for lock_path in lock_paths:
    lock=json.loads((ROOT/lock_path).read_text())
    relative=pathlib.PurePosixPath(lock['path'])
    if relative.is_absolute() or '..' in relative.parts or not relative.parts or not (ROOT/relative).resolve().is_relative_to(ROOT):
        raise RuntimeError('Upstream source path must stay inside the repository')
    upstream=ROOT/relative
    if subprocess.check_output(['git','-C',str(upstream),'rev-parse','HEAD'],text=True).strip()!=lock['commit']: raise RuntimeError('Upstream revision differs: '+lock_path)
    if subprocess.check_output(['git','-C',str(upstream),'rev-parse','HEAD^{tree}'],text=True).strip()!=lock['tree']: raise RuntimeError('Upstream tree differs: '+lock_path)
    if subprocess.check_output(['git','-C',str(upstream),'status','--porcelain'],text=True): raise RuntimeError('Upstream checkout contains changes: '+lock_path)
    upstreams.append((lock_path,lock,upstream))
subprocess.run(["python3",str(ROOT/"scripts/check-public-source.py")],check=True)
destination.mkdir(parents=True)
roots=['app/src','baseline-driver/src','native-runtime','scripts','patches','licenses','docs','gradle','bench','fastlane','fdroid']
files=[ROOT/name for name in ['.gitignore','LICENSE','README.md','build.gradle.kts','settings.gradle.kts',
       'gradle.properties','gradlew','app/build.gradle.kts','baseline-driver/build.gradle.kts',
       *lock_paths,'toolchain.lock.json','tool-archives.lock.json','native-dependencies.lock.json']]
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
exported_upstreams = {}
for lock_path,lock,upstream in upstreams:
    archive=subprocess.check_output(['git','-C',str(upstream),'archive','--format=tar',lock['commit']])
    exported=destination/lock['path'];exported.mkdir(parents=True)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar: tar.extractall(exported,filter='data')
    actual=source_tree(exported)
    if actual!=lock['tree']: raise RuntimeError('Raw upstream source export differs from locked tree: '+lock_path)
    exported_upstreams[lock_path]=dict(commit=lock['commit'],tree=actual,path=lock['path'])
# Retain baseline manifest fields for existing consumers; record every source pin.
baseline=exported_upstreams['upstream.lock.json']
report=dict(scope='Build input snapshot, not a release/signing/reproducibility acceptance',upstream_commit=baseline['commit'],upstream_tree=baseline['tree'],upstreams=exported_upstreams,files=manifest)
(destination.parent/(destination.name+'-manifest.json')).write_text(json.dumps(report,indent=2)+'\n')
print(destination)
