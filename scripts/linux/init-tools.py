#!/usr/bin/env python3
"""Run inside the isolated Linux builder; keep case-sensitive NDK headers in a volume."""
import hashlib
import json
import pathlib
import shutil
import subprocess

archives=pathlib.Path('/archives');root=pathlib.Path('/opt/motoai')
manifest=json.loads((archives/'tool-manifest.json').read_text())
for item in manifest['packages']:
    source=archives/item['archive']
    if hashlib.file_digest(source.open('rb'),'sha256').hexdigest()!=item['sha256']: raise RuntimeError('Linux tool checksum differs')
    destination=root/item['destination']
    if destination.exists():
        if not (destination/'.motoai-archive-sha256').exists() or (destination/'.motoai-archive-sha256').read_text().strip()!=item['sha256']:
            raise RuntimeError('Unowned or incomplete Linux tool destination')
        continue
    temporary=root/(item['archive']+'.unpack')
    if not temporary.exists():
        temporary.mkdir(parents=True)
        subprocess.run(['unzip','-q',str(source),'-d',str(temporary)],check=True)
    elif item['package']!='cmake;3.31.6':
        raise RuntimeError('Preserve failed Linux extraction for inspection')
    directories=list(temporary.iterdir())
    destination.parent.mkdir(parents=True,exist_ok=True)
    if item['package']=='cmake;3.31.6' and (temporary/'bin/cmake').is_file() and (temporary/'source.properties').is_file():
        temporary.rename(destination) # Official CMake package has a flat archive layout.
    elif len(directories)==1 and directories[0].is_dir():
        directories[0].rename(destination);temporary.rmdir()
    else: raise RuntimeError('Unexpected Linux archive layout')
    (destination/'.motoai-archive-sha256').write_text(item['sha256']+'\n')
platform=root/'sdk/platforms/android-36'
if not platform.exists():
    platform.parent.mkdir(parents=True,exist_ok=True);shutil.copytree(archives/'sdk/platforms/android-36',platform)
if hashlib.file_digest((platform/'android.jar').open('rb'),'sha256').hexdigest()!=manifest['platform_jar_sha256']:
    raise RuntimeError('SDK platform jar identity differs')
shutil.copytree(archives/'sdk/licenses',root/'sdk/licenses',dirs_exist_ok=True)
(root/'tool-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('Verified Linux SDK/NDK/CMake prepared on case-sensitive filesystem')
