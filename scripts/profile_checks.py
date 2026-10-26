"""Fail-closed symbol identity and sampling-accounting checks for diagnostics."""
import hashlib
import pathlib
import re
import subprocess
import tempfile
import zipfile


def elf_build_id(notes):
    values = re.findall(r'Build ID:\s*([0-9a-fA-F]+)', notes)
    if len(values) != 1:
        raise ValueError('Expected exactly one ELF build ID')
    return values[0].lower()


def verify_native_symbols(apk, native_libs, ndk):
    # Keep the llvm-strip symlink name: resolving it invokes llvm-objcopy mode.
    binaries = pathlib.Path(ndk) / 'toolchains/llvm/prebuilt/darwin-x86_64/bin'
    verified = {}
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as temporary:
        directory = pathlib.Path(temporary)
        entries = [name for name in archive.namelist()
                   if name.startswith('lib/arm64-v8a/') and name.endswith('.so')]
        if not entries or len(entries) != len(set(entries)):
            raise ValueError('Missing or duplicate APK native libraries')
        for entry in entries:
            name = pathlib.PurePosixPath(entry).name
            source = pathlib.Path(native_libs) / name
            packaged = directory / name
            packaged.write_bytes(archive.read(entry))
            ids = [elf_build_id(subprocess.check_output(
                [str(binaries / 'llvm-readelf'), '-n', str(path)], text=True))
                for path in (source, packaged)]
            if ids[0] != ids[1]:
                raise ValueError('Symbol/APK build ID mismatch: ' + name)
            stripped = directory / ('stripped-' + name)
            subprocess.run([str(binaries / 'llvm-strip'), '--strip-unneeded',
                            '-o', str(stripped), str(source)], check=True,
                           capture_output=True)
            if stripped.read_bytes() != packaged.read_bytes():
                raise ValueError('Symbol/APK stripped bytes mismatch: ' + name)
            verified[name] = {'build_id': ids[0],
                              'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                              'apk_library_sha256': hashlib.sha256(packaged.read_bytes()).hexdigest(),
                              'strip_normalized_identity': True}
    return verified


def check_recorded_build_ids(dump, verified, installed_directory):
    records = re.findall(r'build_id 0x([0-9a-fA-F]+)\s+filename ([^\n]+)', dump)
    matched = {}
    for identifier, path in records:
        if not path.startswith(installed_directory.rstrip('/') + '/'):
            continue
        name = pathlib.PurePosixPath(path).name
        if not name.endswith('.so'):
            continue
        if name not in verified or identifier.lower() != verified[name]['build_id'].ljust(40, '0'):
            raise ValueError('Recorded/APK build ID mismatch: ' + name)
        matched[name] = identifier.lower()
    if not any(name.startswith('libggml-cpu-') for name in matched):
        raise ValueError('No verified sampled CPU backend')
    return matched


def record_statistics(dump):
    match = re.search(r'^\s*record_stat = ([^\n]+)', dump, re.MULTILINE)
    if not match:
        raise ValueError('Missing sampling loss accounting')
    values = dict(re.findall(r'([a-z_]+)=(\d+)', match[1]))
    required = ('sample_record_count', 'kernelspace_lost_records',
                'userspace_lost_samples', 'userspace_lost_non_samples')
    if any(key not in values for key in required):
        raise ValueError('Incomplete sampling loss accounting')
    return {key: int(value) for key, value in values.items()}
