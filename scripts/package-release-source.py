#!/usr/bin/env python3
"""Export a reviewable source archive without local models, credentials or raw phone logs."""
import argparse,datetime,gzip,hashlib,json,pathlib,subprocess,tarfile,shutil
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--destination',type=pathlib.Path,required=True);a=p.parse_args();dest=a.destination.resolve()
if not dest.is_relative_to(ROOT/'build') or not dest.is_dir():p.error('Existing release output directory under build/ required')
accept=ROOT/'bench/release-acceptance-20261026.json'
if not accept.exists() or json.loads(accept.read_text()).get('passed') is not True:raise RuntimeError('Final passed acceptance summary required before packaging')
identity=json.loads((dest/'production-source-identity.json').read_text());e=ROOT/'build/linux-product-g-20261026'
for name,expected in identity['files'].items():
 source=ROOT/name
 if hashlib.file_digest(source.open('rb'),'sha256').hexdigest()!=expected:raise RuntimeError('Production source changed since final Linux pair: '+name)
source=ROOT/'build'/('release-source-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
subprocess.run(['python3',str(ROOT/'scripts/export-build-source.py'),str(source)],check=True)
review=source/'release-review';review.mkdir()
export_manifest=source.parent/(source.name+'-manifest.json')
shutil.copyfile(export_manifest,review/'build-input-manifest.json')
for name in ['production-source-identity.json','linux-build-environment.json','signature-verification.txt','MotoAI-0.1.0-dev.cdx.json']:
 shutil.copyfile(dest/name,review/name)
# Deliberately curated summaries only: no raw device dumps, credentials,
# third-party repository files or model tensor probes in the source distribution.
for folder in ['linux-product-url-copy-pair-20261026','linux-url-copy-native-bridge-20261026','linux-release-test-kernel-bridge-20261026','release-audit-url-copy-signed-20261026','fdroid-binary-scan-url-copy-signed-20261026','fdroid-prebuild-url-copy-executed-20261026','release-sbom-url-copy-schema-20261026','release-ui-emulator-20261026T070445Z']:
 shutil.copyfile(ROOT/'bench/results'/folder/'report.json',review/(folder+'.json'))
accept=ROOT/'bench/release-acceptance-20261026.json'
if not accept.exists():raise RuntimeError('Final acceptance summary must exist before packaging')
shutil.copyfile(accept,review/'acceptance.json')
for label,evidence in json.loads(accept.read_text()).get('evidence',{}).items():
 path=(ROOT/evidence['path']).resolve()
 if not path.is_relative_to(ROOT/'bench') or path.suffix!='.json':raise RuntimeError('Expected a curated workspace JSON summary')
 if hashlib.sha256(path.read_bytes()).hexdigest()!=evidence['sha256']:raise RuntimeError('Acceptance evidence changed: '+label)
 # Explicit evidence paths are top-level summaries, never raw model probes/device dumps.
 shutil.copyfile(path,review/(label+'.json'))
for index,evidence in enumerate(json.loads(accept.read_text()).get('retained_failed_attempts',[])):
 path=(ROOT/evidence['path']).resolve()
 if not path.is_relative_to(ROOT/'bench/results') or path.suffix!='.json':raise RuntimeError('Expected a retained failure summary')
 if hashlib.sha256(path.read_bytes()).hexdigest()!=evidence['sha256']:raise RuntimeError('Retained failure changed')
 shutil.copyfile(path,review/('retained-failed-attempt-'+str(index+1)+'.json'))
archive=dest/'MotoAI-0.1.0-dev-sources.tar.gz'
with archive.open('wb') as raw,gzip.GzipFile(filename='',mode='wb',fileobj=raw,mtime=0) as compressed,tarfile.open(fileobj=compressed,mode='w') as tar:
 for path in sorted(source.rglob('*')):
  name='MotoAI-0.1.0-dev/'+str(path.relative_to(source));info=tar.gettarinfo(str(path),arcname=name)
  info.mtime=0;info.uid=info.gid=0;info.uname=info.gname=''
  if path.is_file() and not path.is_symlink():
   with path.open('rb') as stream:tar.addfile(info,stream)
  else:tar.addfile(info)
sha=hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()
(dest/'source-package.json').write_text(json.dumps(dict(archive=archive.name,sha256=sha,source_export=str(source.relative_to(ROOT)),build_input_manifest_sha256=hashlib.sha256(export_manifest.read_bytes()).hexdigest(),scope='Production input identity checked against final Linux pair; additional current tests/docs/controllers/catalog and curated evidence supplied. No local model weights, secrets, tool caches or raw phone logs. Pristine upstream includes its own vocabulary-only development fixtures, never bundled in APK.'),indent=2)+'\n')
print(archive,sha)
