"""Resolve the app's canonical model copy for independent ADB integrity checks.

Runtime opens MediaStore URIs via PFD. The physical path here is diagnostic only;
never silently stage another private copy when a shared entry is inaccessible.
"""
import json
import re
import shlex
import subprocess
import uuid


def model_source(filename):
    if not re.fullmatch(r'[A-Za-z0-9._-]+\.gguf', filename):
        raise ValueError('Invalid model filename')
    command = ['adb', '-d', 'exec-out', 'run-as', 'org.motoai.runtime', 'cat',
               'files/models/' + filename + '.shared.json']
    registry = subprocess.run(command, capture_output=True, text=True, timeout=30)
    # adb exec-out can return zero even when the device-side cat fails.
    if registry.returncode == 0 and registry.stdout.lstrip().startswith('{'):
        entry = json.loads(registry.stdout)
        path = entry['diagnostic_file_path']
        if not path.startswith(('/storage/emulated/0/Download/MotoAI/Models/', '/sdcard/Download/MotoAI/Models/')):
            raise RuntimeError('Unexpected shared model location')
        return {'path': path, 'storage': 'MediaStore Downloads/MotoAI/Models', 'uri': entry['uri']}
    if 'No such file' not in registry.stderr and 'No such file' not in registry.stdout:
        raise RuntimeError('Cannot inspect app model registry: ' + registry.stderr)
    return {'path': 'files/models/' + filename, 'storage': 'app-private'}


def model_sha256(filename):
    source = model_source(filename)
    if source['storage'] != 'app-private':
        # Scoped storage can deny raw FUSE paths even for app-owned Downloads.
        # Hash the real URI/PFD under app UID; do not trust the registry's digest.
        run_id = uuid.uuid4().hex
        run = subprocess.run(['adb', '-d', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'scope', 'model-info',
            '-e', 'model', filename, '-e', 'runId', run_id,
            'org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation'], capture_output=True, text=True, timeout=180, check=True)
        output = subprocess.check_output(['adb', '-d', 'exec-out', 'run-as', 'org.motoai.runtime', 'cat',
                                         'files/model-info-' + run_id + '.json'], text=True, timeout=30)
        try: info = json.loads(output)
        except ValueError as error: raise RuntimeError('Install the matching model-info test APK; no shared-file hash accepted: ' + run.stdout[-600:]) from error
        if info.get('passed') is not True or info.get('model') != filename or info.get('run_id') != run_id:
            raise RuntimeError('App-UID shared-file SHA failed: ' + output)
        sha = info['sha256']
    else:
        output = subprocess.check_output(['adb', '-d', 'shell', shlex.join([
            'run-as', 'org.motoai.runtime', 'sha256sum', source['path']])], text=True, timeout=120)
        sha = output.split()[0]
    if not re.fullmatch('[0-9a-f]{64}', sha): raise RuntimeError('Missing phone SHA-256')
    return sha
