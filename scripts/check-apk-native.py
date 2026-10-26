#!/usr/bin/env python3
"""Tie every packaged ARM64 library to its local symbols and check16KiB compatibility."""
import argparse
import hashlib
import json
import pathlib
import re
import struct
import subprocess
import tempfile
import zipfile

ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk',type=pathlib.Path,required=True)
p.add_argument('--symbols',type=pathlib.Path,required=True)
p.add_argument('--output',type=pathlib.Path,required=True)
a=p.parse_args()
for path in (a.apk,a.symbols,a.output):
    if not path.resolve().is_relative_to(ROOT):p.error('Workspace paths only')
llvm=ROOT/'.tools/android-ndk-r28c/toolchains/llvm/prebuilt/darwin-x86_64/bin'
report=dict(passed=False,scope='Packaged native/symbol byte identity and static ELF/ZIP page compatibility; no runtime/quality/speed acceptance',
            apk_sha256=hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest(),libraries={})
try:
    with tempfile.TemporaryDirectory(prefix='motoai-native-check-') as temporary,zipfile.ZipFile(a.apk) as archive:
        temporary=pathlib.Path(temporary)
        libraries=[i for i in archive.infolist() if i.filename.startswith('lib/') and i.filename.endswith('.so')]
        if not libraries:raise RuntimeError('No native libraries packaged')
        for item in libraries:
            if not item.filename.startswith('lib/arm64-v8a/'):raise RuntimeError('Unexpected ABI')
            name=pathlib.Path(item.filename).name
            if name in ('libOpenCL.so','libvulkan.so'):raise RuntimeError('OEM loader must not be bundled')
            original=a.symbols/name
            if not original.is_file():raise RuntimeError('Missing symbols for '+name)
            stripped=temporary/name
            subprocess.run([str(llvm/'llvm-strip'),'--strip-unneeded','-o',str(stripped),str(original)],check=True,capture_output=True)
            payload=archive.read(item)
            if stripped.read_bytes()!=payload:raise RuntimeError('Packaged/symbol library mismatch: '+name)
            headers=subprocess.check_output([str(llvm/'llvm-readelf'),'--program-headers','--wide',str(original)],text=True)
            alignments=[int(line.split()[-1],16) for line in headers.splitlines() if line.strip().startswith('LOAD ')]
            if not alignments or any(value<16384 for value in alignments):raise RuntimeError('ELF load alignment below16KiB: '+name)
            notes=subprocess.check_output([str(llvm/'llvm-readelf'),'--notes',str(original)],text=True)
            build_id=re.search(r'Build ID: (\w+)',notes)
            with a.apk.open('rb') as source:
                source.seek(item.header_offset);header=source.read(30)
                name_size,extra_size=struct.unpack_from('<HH',header,26)
                offset=item.header_offset+30+name_size+extra_size
            if item.compress_type==zipfile.ZIP_STORED and offset%16384:raise RuntimeError('Uncompressed native ZIP offset is not16KiB aligned')
            report['libraries'][name]=dict(build_id=build_id[1] if build_id else None,
                symbol_sha256=hashlib.file_digest(original.open('rb'),'sha256').hexdigest(),
                apk_library_sha256=hashlib.sha256(payload).hexdigest(),strip_normalized_identity=True,
                load_alignments=alignments,zip_compression=item.compress_type,zip_data_offset=offset)
    subprocess.run([str(ROOT/'.tools/android-sdk/build-tools/36.1.0/zipalign'),'-c','-P','16','4',str(a.apk)],check=True,capture_output=True)
    report['zipalign_passed']=True;report['passed']=True
except Exception as error:report['error']=str(error)
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,indent=2)+'\n')
print('PASS' if report['passed'] else report['error'])
raise SystemExit(0 if report['passed'] else 1)
