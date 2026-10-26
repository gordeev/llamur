#!/usr/bin/env python3
"""Fetch exact Linux SDK/NDK/CMake packages from locked official repository metadata."""
import concurrent.futures
import hashlib
import json
import pathlib
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT=pathlib.Path(__file__).resolve().parents[2]
BASE=ROOT/'.tools/linux-tools';BASE.mkdir(parents=True,exist_ok=True)
repository=ROOT/'.tools/downloads/android-repository.xml';tree=ET.parse(repository).getroot()
selected={'ndk;28.2.13676358':'ndk-r28c','build-tools;36.1.0':'sdk/build-tools/36.1.0','cmake;3.31.6':'cmake-3.31.6'}
records=[]
for package,destination in selected.items():
    matches=[p for p in tree.findall('remotePackage') if p.get('path')==package]
    if len(matches)!=1: raise RuntimeError('Ambiguous SDK package '+package)
    archives=[p for p in matches[0].findall('archives/archive') if p.findtext('host-os')=='linux']
    if len(archives)!=1: raise RuntimeError('Ambiguous Linux SDK archive')
    archive=archives[0].find('complete')
    records.append(dict(package=package,destination=destination,url='https://dl.google.com/android/repository/'+archive.findtext('url'),
                        bytes=int(archive.findtext('size')),sha1=archive.findtext('checksum')))
def fetch(record):
    name=record['url'].rsplit('/',1)[1];archive=BASE/name
    if not archive.exists():
        subprocess.run(['/usr/bin/curl','--fail','--location','--retry','2','--output',str(archive),record['url']],check=True)
    if archive.stat().st_size!=record['bytes'] or hashlib.file_digest(archive.open('rb'),'sha1').hexdigest()!=record['sha1']:
        raise RuntimeError('Linux tool archive integrity mismatch: '+name)
    record['sha256']=hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()
    record['archive']=name
    return record
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:records=list(executor.map(fetch,records))
platform=BASE/'sdk/platforms/android-36'
if not platform.exists():
    platform.parent.mkdir(parents=True,exist_ok=True)
    shutil.copytree((ROOT/'.tools/android-sdk/platforms/android-36').resolve(),platform,symlinks=False)
licenses=BASE/'sdk/licenses'
shutil.copytree(ROOT/'.tools/android-sdk/licenses',licenses,dirs_exist_ok=True)
(BASE/'tool-manifest.json').write_text(json.dumps(dict(repository_sha256=hashlib.sha256(repository.read_bytes()).hexdigest(),
 packages=records,platform_jar_sha256=hashlib.file_digest((platform/'android.jar').open('rb'),'sha256').hexdigest()),indent=2)+'\n')
(ROOT/'bench/linux-tools.lock.json').write_bytes((BASE/'tool-manifest.json').read_bytes())
print(BASE)
