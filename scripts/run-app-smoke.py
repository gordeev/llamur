#!/usr/bin/env python3
"""Run the debug APK's native or service harness with immutable model inputs."""
from phone_lease import acquire_phone_lease
from process_watch import ProcessWatch, HarnessProcessDied
from process_exit_info import collect_exit_info
from trial_finalization import finalize_trial
from phone_health import require_phone_ready, read_health, check_running_health, PhoneHealthFailure
import argparse
import base64
import datetime
import hashlib
import json
import pathlib
import shlex
import subprocess
import time
import uuid
import sys
import shutil

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('model', choices=['smoke', 'gemma', 'ornith'])
p.add_argument('--service', action='store_true')
p.add_argument('--background', action='store_true')
p.add_argument('--screen-off', action='store_true')
p.add_argument('--context', type=int, default=4096)
p.add_argument('--tokens', type=int, default=32)
p.add_argument('--prompt', default='Write a Python function that adds two integers.')
p.add_argument('--raw', action='store_true')
p.add_argument('--trace', action='store_true')
p.add_argument('--threads', type=int, default=4)
p.add_argument('--load-mode', choices=['read', 'mmap', 'auto'], default='read')
p.add_argument('--timeout', type=int, default=300)
p.add_argument('--start-timeout',type=int,default=120,help='Separate bound until first observable inference PID; does not shorten an active model workload')
p.add_argument('--request', help='config/request JSON fixture (service only)')
p.add_argument('--record-device', action='store_true')
p.add_argument('--apk', default=str(ROOT/'app/build/outputs/apk/debug/app-debug.apk'))
p.add_argument('--expect-error', choices=['runtime', 'context_overflow', 'cancelled', 'memory_admission', 'unsupported_profile'])
args = p.parse_args()
lease = acquire_phone_lease()
initial_health = require_phone_ready()
spec = None
if args.request:
    if not args.service: p.error('--request requires --service')
    raw_spec = (ROOT / args.request).read_bytes()
    if len(raw_spec) > 128 * 1024: p.error('Fixture file exceeds diagnostic budget')
    spec = json.loads(raw_spec)
    args.context = spec['config']['context']
    args.threads = spec['config']['threads']
    args.tokens = spec['request']['max_tokens']
if not 128 <= args.context <= 262144 or not 1 <= args.threads <= 64: p.error('Invalid context/threads')
if not 1 <= args.tokens <= 262144 or args.tokens > args.context: p.error('Invalid output budget')
if not 1 <= args.start_timeout <= 300:p.error('Start timeout must be1..300 seconds')
if not 1 <= args.timeout <= 7200: p.error('Timeout must be1..7200 seconds; explicit long-context research only beyond1800')
if len(args.prompt.encode()) > 32768 or '\0' in args.prompt: p.error('Prompt exceeds transport limit')
lock = json.loads((ROOT / 'bench/models.lock.json').read_text())
models = lock['models'] if isinstance(lock, dict) and 'models' in lock else lock
model = next(item for item in models if item['id'] == args.model)
run_id = uuid.uuid4().hex
if (args.background or args.screen_off) and not args.service: p.error('Background checks need --service')
scope = 'service' if args.service else 'native'
out = ROOT / 'bench/results' / f'app-{scope}-{args.model}-{datetime.datetime.now(datetime.timezone.utc):%Y%m%dT%H%M%SZ}-{run_id[:8]}'
out.mkdir(parents=True)
apk = pathlib.Path(args.apk).resolve()
if not apk.is_relative_to(ROOT) or apk.suffix != '.apk': p.error('Expected a workspace APK')
meta = {'run_id': run_id, 'apk_sha256': hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest(),
        'model': model, 'scope': scope, 'arguments': vars(args), 'request_spec': spec}
meta['device_health_before'] = initial_health
meta['device_health_policy'] = {'minimum_start_percent': 20, 'minimum_running_percent': 10, 'poll_seconds': 10}
source_snapshot = out / 'collector-sources'
source_snapshot.mkdir()
meta['collector_sources_sha256'] = {}
for name in ['run-app-smoke.py', 'process_watch.py', 'process_exit_info.py', 'phone_health.py', 'phone_lease.py', 'trial_finalization.py', 'device-info.py']:
    contents = (ROOT / 'scripts' / name).read_bytes()
    (source_snapshot / name).write_bytes(contents)
    meta['collector_sources_sha256'][name] = hashlib.sha256(contents).hexdigest()
archive = ROOT / 'bench/results/builds' / meta['apk_sha256'] / 'app-debug.apk'
archive.parent.mkdir(parents=True, exist_ok=True)
if not archive.exists(): shutil.copyfile(apk, archive)
if hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest() != meta['apk_sha256']:
    raise RuntimeError('APK changed while archiving')
meta['apk_archive'] = str(archive.relative_to(ROOT))
(out / 'manifest.json').write_text(json.dumps(meta, indent=2) + '\n')

def adb(*command, check=True):
    return subprocess.run(['adb', '-d', *command], capture_output=True, text=True, check=check, timeout=30)

installed = adb('shell', 'pm path org.motoai.runtime').stdout.strip()
if not installed.startswith('package:') or '\n' in installed: raise RuntimeError('Expected one installed APK')
installed_sha = adb('shell', shlex.join(['sha256sum', installed[8:]])).stdout.split()[0]
if installed_sha != meta['apk_sha256']: raise RuntimeError('Installed APK differs from local artifact')
adb('shell', 'am force-stop org.motoai.runtime')
if spec is not None:
    adb('shell', 'run-as org.motoai.runtime mkdir -p files/bench')
    destination = f'files/bench/request-{run_id}.json'
    stage_command = shlex.join(['run-as', 'org.motoai.runtime', 'sh', '-c', 'cat > ' + shlex.quote(destination)])
    subprocess.run(['adb', '-d', 'shell', '-T', stage_command], input=raw_spec, capture_output=True, check=True, timeout=30)
if args.record_device:
    with (out / 'device-before.json').open('w') as stream:
        subprocess.run([sys.executable, str(ROOT / 'scripts/device-info.py')], stdout=stream, check=True, timeout=90)
command = ['am', 'start', '-n', 'org.motoai.runtime/.MainActivity', '--ez', f'{scope}Smoke', 'true',
           '--es', 'model', model['filename'], '--es', 'runId', run_id, '--ei', 'context', str(args.context),
           '--ei', 'threads', str(args.threads), '--ei', 'tokens', str(args.tokens), '--ez', 'raw', str(args.raw).lower(),
           '--es', 'prompt', args.prompt, '--es', 'loadMode', args.load_mode, '--ez', 'trace', str(args.trace).lower()]
if spec is not None: command += ['--es', 'requestFile', f'request-{run_id}.json']
path = f'files/{scope}-result-{run_id}.json'
backgrounded = False
last_progress = None
process_watch = ProcessWatch()
process_name = 'org.motoai.runtime' + (':inference' if args.service else '')
next_health_check = time.monotonic() + 10
try:
    # am start can time out after Android has already started the app. Keep it
    # inside cleanup protection, so a failed launch cannot leave inference running.
    launch=adb('shell', shlex.join(command))
    (out/'activity-launch.txt').write_text(launch.stdout+'\n'+launch.stderr)
    deadline = time.monotonic() + args.timeout
    launch_deadline=time.monotonic()+args.start_timeout
    while time.monotonic() < deadline:
        if time.monotonic() >= next_health_check:
            health = check_running_health(initial_health)
            with (out / 'device-health.jsonl').open('a') as stream: stream.write(json.dumps(health) + '\n')
            next_health_check = time.monotonic() + 10
        if args.service:
            telemetry = adb('exec-out', 'run-as', 'org.motoai.runtime', 'cat', f'files/service-progress-{run_id}.json', check=False)
            if telemetry.returncode == 0:
                try:
                    observed = json.loads(telemetry.stdout)
                    if observed.get('run_id') == run_id and observed != last_progress:
                        last_progress = observed
                        (out / 'last-progress.json').write_text(json.dumps(observed, indent=2) + '\n')
                        with (out / 'progress.jsonl').open('a') as stream:
                            stream.write(json.dumps(observed) + '\n')
                        if observed.get('completed') is not None:
                            print(f"Progress: {observed['phase']} {observed['completed']}/{observed['total']} at {observed.get('elapsed_us',0)/1e6:.1f}s", flush=True)
                except json.JSONDecodeError:
                    pass
        if (args.background or args.screen_off) and not backgrounded:
            progress = adb('exec-out', 'run-as', 'org.motoai.runtime', 'cat', f'files/service-progress-{run_id}.json', check=False)
            if progress.stdout.lstrip().startswith('{'):
                if args.background: adb('shell', 'input keyevent KEYCODE_HOME')
                if args.screen_off: adb('shell', 'input keyevent KEYCODE_SLEEP')
                backgrounded = True
        result = adb('exec-out', 'run-as', 'org.motoai.runtime', 'cat', path, check=False)
        if result.returncode == 0 and result.stdout.lstrip().startswith('{'):
            try:
                data = json.loads(result.stdout)
            except json.JSONDecodeError:
                time.sleep(0.2)
                continue
            if data['run_id'] != run_id: raise RuntimeError('Wrong run ID')
            if data.get('model') != model['filename']: raise RuntimeError('Wrong model')
            if args.service and data.get('output_file'):
                filename = data.get('output_file', '')
                if not filename.startswith('output-') or '/' in filename: raise RuntimeError('Invalid output file')
                content = subprocess.check_output(['adb', '-d', 'exec-out', 'run-as', 'org.motoai.runtime', 'cat', f'files/{filename}'], timeout=30)
                (out / 'output.bin').write_bytes(content)
                data['output'] = content.decode('utf-8', errors='replace')
                data['output_base64'] = base64.b64encode(content).decode('ascii')
            if data.get('result', {}).get('generated_tokens', 0) == 0: data['passed'] = False
            (out / 'result.json').write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')
            print(json.dumps(data, indent=2, ensure_ascii=False))
            if args.expect_error:
                if data.get('error_kind') != args.expect_error: raise RuntimeError('Expected error did not match')
            elif not data.get('passed'): raise RuntimeError('App harness failed')
            break
        previous_pid = process_watch.pid
        identity = adb('shell', 'pidof', process_name, check=False)
        if identity.stderr.strip() or identity.returncode not in (0, 1):
            raise RuntimeError('Cannot observe inference process: ' + identity.stderr.strip())
        current_pid = process_watch.observe(identity.stdout)
        if current_pid is None and time.monotonic()>launch_deadline:raise TimeoutError('Inference process never became observable within the separate launch deadline; no long-context result')
        if current_pid is not None and previous_pid is None:
            (out / 'process.json').write_text(json.dumps({'name': process_name, 'pid': current_pid}) + '\n')
        time.sleep(1)
    else:
        raise TimeoutError('No result from app harness')
except Exception as error:
    kind = 'device_health' if isinstance(error, PhoneHealthFailure) else 'process_died' if isinstance(error, HarnessProcessDied) else 'timeout' if isinstance(error, (TimeoutError, subprocess.TimeoutExpired)) else type(error).__name__
    failure = {'run_id': run_id, 'passed': False, 'kind': kind, 'message': str(error), 'observed_pid': process_watch.pid, 'last_progress': last_progress, 'diagnostic_errors': []}
    failure['device_health_after'] = error.health if isinstance(error, PhoneHealthFailure) else read_health()
    (out / 'failure.json').write_text(json.dumps(failure, indent=2) + '\n')
    if last_progress and last_progress.get('output_file'):
        filename = last_progress['output_file']
        if filename.startswith('output-') and '/' not in filename:
            try:
                partial = subprocess.run(['adb', '-d', 'exec-out', 'run-as', 'org.motoai.runtime', 'cat', f'files/{filename}'], capture_output=True, timeout=30)
                (out / 'partial-output.bin').write_bytes(partial.stdout)
            except (OSError, subprocess.SubprocessError) as diagnostic:
                failure['diagnostic_errors'].append(str(diagnostic))
    try:
        def save_exit_info(index, diagnostic):
            contents = diagnostic.stdout + '\nSTDERR\n' + diagnostic.stderr
            (out / f'exit-info-{index}.txt').write_text(contents)
            (out / 'exit-info.txt').write_text(contents)
            failure['exit_info_exit_code'] = diagnostic.returncode
        # ActivityManager may publish the cause after pidof first observes death.
        # Never assign a previous run's LOW_MEMORY record to the current PID.
        failure['process_exit'] = collect_exit_info(
            lambda: subprocess.run(['adb', '-d', 'shell', 'dumpsys activity exit-info org.motoai.runtime'],
                                   capture_output=True, text=True, timeout=5),
            save_exit_info, process_watch.pid, process_name)
    except (OSError, subprocess.SubprocessError) as diagnostic:
        failure['diagnostic_errors'].append(str(diagnostic))
    (out / 'failure.json').write_text(json.dumps(failure, indent=2) + '\n')
    raise
finally:
    primary_error_active = sys.exc_info()[0] is not None
    def record_after():
        with (out / 'device-after.json').open('w') as stream:
            return subprocess.run([sys.executable, str(ROOT / 'scripts/device-info.py')], stdout=stream, check=False, timeout=90)
    actions = []
    if args.record_device: actions.append(('device-after', record_after))
    if args.screen_off and backgrounded:
        actions.append(('wake-screen', lambda: adb('shell', 'input keyevent KEYCODE_WAKEUP', check=False)))
    actions.append(('stop-motoai', lambda: adb('shell', 'am force-stop org.motoai.runtime', check=False)))
    try:
        finalize_trial(out, actions, primary_error_active=primary_error_active)
    finally:
        print(f'Results: {out.relative_to(ROOT)}')
