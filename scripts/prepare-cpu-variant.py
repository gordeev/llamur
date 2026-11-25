#!/usr/bin/env python3
"""Materialize hash-locked CPU patches from their pinned upstream; preserve pristine B0."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import shutil
import os
from source_tree import source_tree

root = pathlib.Path(__file__).resolve().parents[1]
series = json.loads((root / 'patches/cpu-numeric/series.json').read_text())
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('variant', choices=series['variants'])
args = p.parse_args()
variant = series['variants'][args.variant]
def repository_path(value):
    relative = pathlib.PurePosixPath(value)
    if relative.is_absolute() or '..' in relative.parts or not relative.parts:
        raise RuntimeError('Upstream lock and source paths must be safe repository-relative paths')
    result = root / relative
    if not result.resolve().is_relative_to(root.resolve()):
        raise RuntimeError('Upstream lock and source paths must stay inside the repository')
    return result

lock = json.loads(repository_path(variant.get('upstream_lock', 'upstream.lock.json')).read_text())
for key in ('commit', 'tree'):
    if len(lock[key]) != 40 or any(c not in '0123456789abcdef' for c in lock[key]):
        raise RuntimeError('Upstream commit and tree must be complete lowercase Git hashes')
if 'upstream_lock' not in variant and lock['commit'] != series['upstream_commit']:
    raise RuntimeError('Legacy series and upstream lock revisions differ')
pristine = repository_path(lock['path'])
source = root / variant['directory']
if not source.resolve().is_relative_to((root / '.tools').resolve()): raise RuntimeError('Variant must be generated inside .tools')
for patch in variant['patches']:
    if hashlib.sha256((root / patch['path']).read_bytes()).hexdigest() != patch['sha256']: raise RuntimeError('Changed patch')
expected = b''.join((root / patch['path']).read_bytes() for patch in variant['patches'])
# F-Droid/source archives may omit .git. Verify the exact pinned raw Git tree,
# copy it, then apply the same locked patches without a network clone or commit.
if not (pristine/'.git').exists():
    if source_tree(pristine)!=lock['tree']:
        raise RuntimeError('Exported upstream files do not match the locked Git tree')
    digest=hashlib.sha256(expected).hexdigest()
    expected_tree=variant.get('patched_tree')
    if not expected_tree: raise RuntimeError('This variant lacks a locked complete patched tree for source exports')
    metadata=source.with_name(source.name+'.motoai-source.json')
    if source.exists():
        if not metadata.exists(): raise RuntimeError('Unowned exported variant directory')
        stamp_data=json.loads(metadata.read_text())
        if (stamp_data.get('patchset_sha256')!=digest or stamp_data.get('upstream_tree')!=lock['tree']
                or stamp_data.get('upstream_commit')!=lock['commit']):
            raise RuntimeError('Existing exported variant has different provenance')
        if source_tree(source)!=stamp_data['patched_tree'] or stamp_data['patched_tree']!=expected_tree:
            raise RuntimeError('Exported variant modified outside its locked patches')
    else:
        shutil.copytree(pristine,source,symlinks=True)
        try:
            environment=dict(os.environ,GIT_CEILING_DIRECTORIES=str(root.parent))
            for patch in variant['patches']:
                command=['git','apply','--no-index','--directory',variant['directory']]
                subprocess.run([*command,'--check',str(root/patch['path'])],cwd=root,env=environment,check=True)
                subprocess.run([*command,str(root/patch['path'])],cwd=root,env=environment,check=True)
            actual_tree=source_tree(source)
            if actual_tree!=expected_tree: raise RuntimeError('Patched export differs from the locked complete Git tree; no build accepted')
            metadata.write_text(json.dumps(dict(upstream_commit=lock['commit'],upstream_tree=lock['tree'],
                patchset_sha256=digest,patched_tree=actual_tree),indent=2)+'\n')
        except Exception:
            # Preserve the failed checkout for diagnosis; never silently reset it.
            raise
    print(source)
    raise SystemExit(0)

if subprocess.check_output(['git', '-C', str(pristine), 'rev-parse', 'HEAD'], text=True).strip() != lock['commit']:
    raise RuntimeError('Pristine upstream revision differs from selected lock')
if subprocess.check_output(['git', '-C', str(pristine), 'rev-parse', 'HEAD^{tree}'], text=True).strip() != lock['tree']:
    raise RuntimeError('Pristine upstream tree differs from selected lock')
if subprocess.check_output(['git', '-C', str(pristine), 'status', '--porcelain'], text=True):
    raise RuntimeError('Pristine upstream contains local changes; refusing to use them')
stamp = source / '.git/motoai-patches.sha256'
digest = hashlib.sha256(expected).hexdigest()
if source.exists():
    if not stamp.exists() or stamp.read_text().strip() != digest: raise RuntimeError('Existing variant does not match; preserve it and choose a new explicit variant')
else:
    subprocess.run(['git', 'clone', '--shared', '--no-checkout', str(pristine), str(source)], check=True)
    subprocess.run(['git', '-C', str(source), 'checkout', '--detach', lock['commit']], check=True)
    for patch in variant['patches']:
        subprocess.run(['git', '-C', str(source), 'apply', '--check', str(root / patch['path'])], check=True)
        subprocess.run(['git', '-C', str(source), 'apply', str(root / patch['path'])], check=True)
    stamp.write_text(digest + '\n')
if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != lock['commit']: raise RuntimeError('Wrong upstream commit')
# Verify the entire materialized diff each time, including unexpected edits.
actual = subprocess.check_output(['git', '-C', str(source), 'diff', 'HEAD', '--binary'])
if not actual: raise RuntimeError('Missing numeric patch')
snapshot = source / '.git/motoai-diff.sha256'
diff_digest = hashlib.sha256(actual).hexdigest()
if snapshot.exists() and snapshot.read_text().strip() != diff_digest: raise RuntimeError('Variant modified outside its patch series')
if not snapshot.exists(): snapshot.write_text(diff_digest + '\n')
print(source)
