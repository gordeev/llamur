#!/usr/bin/env python3
"""Keep a local immutable APK and an explicit post-build source snapshot, no weights/secrets."""
import argparse
import datetime
import hashlib
import io
import json
import pathlib
import shutil
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk', type=pathlib.Path, default=ROOT/'app/build/outputs/apk/debug/app-debug.apk')
parser.add_argument('--tests', type=pathlib.Path)
args = parser.parse_args()
apk = args.apk.resolve()
if not apk.is_relative_to(ROOT.resolve()) or apk.suffix != '.apk': parser.error('APK must be a workspace artifact')
sha = hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()
out = ROOT / 'bench/results/builds' / sha
out.mkdir(parents=True, exist_ok=True)
target = out / 'app-debug.apk'
if not target.exists(): shutil.copyfile(apk, target)
if hashlib.file_digest(target.open('rb'), 'sha256').hexdigest() != sha:
    raise RuntimeError('Archived APK differs from build output')
test_sha = None
if args.tests is not None:
    tests = args.tests.resolve()
    if not tests.is_relative_to(ROOT.resolve()) or tests.suffix != '.apk': parser.error('Test APK must be a workspace artifact')
    test_sha = hashlib.file_digest(tests.open('rb'), 'sha256').hexdigest()
    test_target = out / ('tests-' + test_sha + '.apk')
    if not test_target.exists(): shutil.copyfile(tests, test_target)
    if hashlib.file_digest(test_target.open('rb'), 'sha256').hexdigest() != test_sha:
        raise RuntimeError('Archived test APK differs from build output')
files = [ROOT / name for name in ['build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'app/build.gradle.kts',
         'upstream.lock.json', 'toolchain.lock.json', 'tool-archives.lock.json', 'native-dependencies.lock.json', 'bench/gpu-dependencies.lock.json', 'LICENSE']]
for directory in ['app/src/main', 'app/src/androidTest', 'native-runtime', 'scripts', 'gradle', 'licenses', 'patches']:
    files += [path for path in (ROOT / directory).rglob('*') if path.is_file() and
              (path.suffix in ('.cpp', '.h', '.kt', '.xml', '.json', '.py', '.sh', '.cmake', '.jar', '.properties', '.txt', '.patch') or
               path.name in ('CMakeLists.txt', 'LICENSE', 'COPYING', 'NOTICE')) and '__pycache__' not in path.parts]
files += [ROOT / 'gradlew', ROOT / 'bench/models.lock.json']
payloads = {str(path.relative_to(ROOT)): path.read_bytes() for path in sorted(set(files))}
snapshot = io.BytesIO()
with tarfile.open(fileobj=snapshot, mode='w') as archive:
    for name, data in sorted(payloads.items()):
        info = tarfile.TarInfo(name)
        info.size = len(data); info.mtime = 0; info.mode = 0o755 if name == 'gradlew' else 0o644
        archive.addfile(info, io.BytesIO(data))
digest = hashlib.sha256(snapshot.getvalue()).hexdigest()
source_path = out / ('source-' + digest + '.tar')
if not source_path.exists(): source_path.write_bytes(snapshot.getvalue())
manifest = {'apk_sha256': sha, 'source_snapshot_sha256': digest, 'source_snapshot': source_path.name,
            'captured_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'scope': 'Post-build working sources and tool locks. Call immediately after a successful build, before further edits. Source capture is not an embedded compiler attestation. Older baselines without captures remain explicitly limited.',
            'files': {name: hashlib.sha256(data).hexdigest() for name, data in payloads.items()}}
if test_sha: manifest.update(test_apk_sha256=test_sha, test_apk='tests-' + test_sha + '.apk')
(out / ('source-' + digest + '.json')).write_text(json.dumps(manifest, indent=2) + '\n')
print('Build archive:', out.relative_to(ROOT), 'source', digest)
