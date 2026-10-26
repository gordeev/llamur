#!/usr/bin/env python3
"""Fetch the pinned public Debian base via host HTTPS, then import locally offline."""
import gzip
import hashlib
import io
import json
import pathlib
import tarfile
import subprocess

ROOT=pathlib.Path(__file__).resolve().parents[2]
DIGEST='sha256:ec31d744f83f4a73447ba045aaab9409997efe5cc706eca4890c1be36cc1606a'
out=ROOT/'.tools/linux-base';out.mkdir(parents=True,exist_ok=True)
# Anonymous, pull-only public-repository token. Never store or print it.
def https(url, headers=()):
    args=['/usr/bin/curl','--fail','--location','--silent','--show-error','--max-time','180','--retry','2']
    for header in headers: args.extend(['--header',header])
    completed=subprocess.run([*args,url],capture_output=True)
    if completed.returncode: raise RuntimeError('Public image HTTPS failed: '+completed.stderr.decode(errors='replace')[-400:])
    return completed.stdout

token=json.loads(https('https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/debian:pull'))['token']
def fetch(path,digest):
    name=digest.split(':')[1];cache=out/name
    if not cache.exists():
        raw=https('https://registry-1.docker.io/v2/library/debian/'+path+'/'+digest,
                  ('Authorization: Bearer '+token,'Accept: application/vnd.oci.image.manifest.v1+json'))
        cache.write_bytes(raw)
    raw=cache.read_bytes()
    if hashlib.sha256(raw).hexdigest()!=name: raise RuntimeError('OCI digest mismatch')
    return raw
manifest=json.loads(fetch('manifests',DIGEST));configuration=fetch('blobs',manifest['config']['digest'])
config=json.loads(configuration);layers=[]
if len(manifest['layers'])!=len(config['rootfs']['diff_ids']): raise RuntimeError('OCI layer count differs')
for layer,expected in zip(manifest['layers'],config['rootfs']['diff_ids']):
    encoded=fetch('blobs',layer['digest'])
    raw=gzip.decompress(encoded) if 'gzip' in layer['mediaType'] else encoded
    if 'sha256:'+hashlib.sha256(raw).hexdigest()!=expected: raise RuntimeError('OCI uncompressed layer mismatch')
    layers.append((expected.split(':')[1]+'/layer.tar',raw))
config_name=manifest['config']['digest'].split(':')[1]+'.json'
metadata=json.dumps([dict(Config=config_name,RepoTags=['motoai-debian:bookworm-ec31d744'],Layers=[name for name,_ in layers])]).encode()
archive=out/'debian-bookworm-ec31d744.tar'
with tarfile.open(archive,'w') as tar:
    for name,raw in [(config_name,configuration),('manifest.json',metadata),*layers]:
        info=tarfile.TarInfo(name);info.size=len(raw);info.mode=0o644;info.mtime=0;tar.addfile(info,io.BytesIO(raw))
(out/'provenance.json').write_text(json.dumps(dict(repository='docker.io/library/debian',manifest_digest=DIGEST,
    config_digest=manifest['config']['digest'],layers=manifest['layers'],docker_archive_sha256=hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()),indent=2)+'\n')
print(archive)
