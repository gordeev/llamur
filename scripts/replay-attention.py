#!/usr/bin/env python3
"""Replay an immutable attention capture on host and installed-APK CPU libraries."""
import argparse
import datetime
import hashlib
import json
import pathlib
import shlex
import subprocess
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('capture', type=pathlib.Path)
args = p.parse_args()
lease = acquire_phone_lease()
require_phone_ready()
capture = args.capture.resolve()
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
out = ROOT / 'bench/results' / ('attention-replay-' + stamp)
out.mkdir(parents=True)
apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
digest = lambda path: hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()
apk_sha = digest(apk)
installed = subprocess.check_output(['adb', '-d', 'shell', 'pm path org.motoai.runtime'], text=True).strip()
if not installed.startswith('package:') or '\n' in installed: raise RuntimeError('Expected single APK')
installed = installed[8:]
actual_sha = subprocess.check_output(['adb', '-d', 'shell', shlex.join(['sha256sum', installed])], text=True).split()[0]
if actual_sha != apk_sha: raise RuntimeError('Installed APK differs')
libs = str(pathlib.PurePosixPath(installed).parent / 'lib/arm64')
phone_exe = ROOT / 'app/build/intermediates/cxx/Release/2612h373/obj/arm64-v8a/motoai-attention'
host_exe = ROOT / 'build/reference-host/motoai-attention'
destination = '/data/local/tmp/motoai/attention-replay'
subprocess.run(['adb', '-d', 'shell', shlex.join(['mkdir', '-p', destination])], check=True)
for source, name in [(phone_exe, 'motoai-attention'), (capture, 'capture.json')]:
    subprocess.run(['adb', '-d', 'push', str(source), destination + '/' + name], check=True, capture_output=True)
subprocess.run(['adb', '-d', 'shell', shlex.join(['chmod', '755', destination + '/motoai-attention'])], check=True)
report = {'scope': 'Same exact captured tensors on both CPUs, no model inference. Preserves strict predeclared attention gates.',
          'capture': str(capture.relative_to(ROOT)), 'capture_sha256': digest(capture), 'apk_sha256': apk_sha,
          'host_executable_sha256': digest(host_exe), 'phone_executable_sha256': digest(phone_exe), 'runs': {}}
for name, command in [
    ('host', [str(host_exe), str(ROOT / 'build/reference-host/bin'), str(capture), '4']),
    ('phone', ['adb', '-d', 'shell', 'LD_LIBRARY_PATH=' + shlex.quote(libs) + ' ' + shlex.join([
        destination + '/motoai-attention', libs, destination + '/capture.json', '4'])])]:
    run = subprocess.run(command, capture_output=True, text=True, timeout=180)
    (out / (name + '.json')).write_text(run.stdout)
    (out / (name + '.stderr.txt')).write_text(run.stderr)
    data = json.loads(run.stdout)
    report['runs'][name] = {key: value for key, value in data.items() if key != 'output_hex'}
    report['runs'][name]['returncode'] = run.returncode
report['outputs_bit_exact'] = json.loads((out / 'host.json').read_text()).get('output_hex') == json.loads((out / 'phone.json').read_text()).get('output_hex')
(out / 'comparison.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
print('Results:', out.relative_to(ROOT))
raise SystemExit(0 if report['outputs_bit_exact'] and all(run['passed'] for run in report['runs'].values()) else 1)
