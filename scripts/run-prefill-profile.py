#!/usr/bin/env python3
"""Separate, bounded app-UID sampling experiment. Never use its timings as speed evidence."""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import sys
import time
import uuid

from phone_health import require_phone_ready
from phone_lease import acquire_phone_lease
from phone_model import model_sha256
from profile_checks import verify_native_symbols, check_recorded_build_ids, record_statistics
from trial_finalization import finalize_trial

ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('model',choices=['ornith','gemma'])
p.add_argument('--native-libs',required=True,type=pathlib.Path,help='Matching unstripped APK obj/arm64-v8a directory')
args=p.parse_args()
lease=acquire_phone_lease();health=require_phone_ready()
apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
native_symbols=verify_native_symbols(apk,args.native_libs,ROOT/'.tools/android-ndk-r28c')
out=ROOT/'bench/results'/('prefill-profile-'+args.model+'-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
out.mkdir(parents=True)
(out/'source-run-prefill-profile.py').write_bytes(pathlib.Path(__file__).read_bytes())
(out/'source-profile_checks.py').write_bytes((ROOT/'scripts/profile_checks.py').read_bytes())
simpleperf=ROOT/'.tools/android-ndk-r28c/simpleperf/bin/android/arm64/simpleperf'
remote='/data/local/tmp/motoai-profile-'+uuid.uuid4().hex
spec=json.loads((ROOT/'bench/fixtures/generated'/args.model/'pp2048-diag.json').read_text())
spec['config'].update(load_mode='mmap',weight_buffers='native',mmap_prefault=False)
spec['request']['max_tokens']=1
request_path=out/'request.json';request_path.write_text(json.dumps(spec,indent=2)+'\n')
report={'passed':False,'scope':'Diagnostic sampled CPU self time during a separate synthetic 2048-token prefill. Sampling overhead excluded from speed acceptance. Does not prove late8K behavior or useful work vs spin from CPU accounting alone.',
        'apk_sha256':hashlib.file_digest((ROOT/'app/build/outputs/apk/debug/app-debug.apk').open('rb'),'sha256').hexdigest(),
        'sampler_sha256':hashlib.sha256(simpleperf.read_bytes()).hexdigest(),'health_before':health,
        'verified_native_symbols':native_symbols}


def adb(*arguments,timeout=30,check=True):
    return subprocess.run(['adb','-d',*arguments],capture_output=True,text=True,timeout=timeout,check=check)


trial=None
try:
    model=next(m for m in json.loads((ROOT/'bench/models.lock.json').read_text())['models'] if m['id']==args.model)
    if model_sha256(model['filename'])!=model['sha256']: raise RuntimeError('Model SHA mismatch')
    installed=adb('shell','pm','path','org.motoai.runtime').stdout.strip()
    if not installed.startswith('package:') or '\n' in installed: raise RuntimeError('Expected one installed APK')
    apk_path=installed[8:]
    if adb('shell','sha256sum',apk_path).stdout.split()[0]!=report['apk_sha256']: raise RuntimeError('Installed APK differs')
    adb('shell','mkdir','-p',remote)
    adb('push',str(simpleperf),remote+'/simpleperf')
    adb('shell','chmod','755',remote+'/simpleperf')
    before=set((ROOT/'bench/results').glob('app-service-'+args.model+'-*'))
    with (out/'trial.log').open('w') as log:
        trial=subprocess.Popen([sys.executable,'scripts/run-app-smoke.py',args.model,'--service','--request',str(request_path),
                                '--record-device','--timeout','900'],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,**lease.child_options())
        deadline=time.monotonic()+600
        artifact=None
        while time.monotonic()<deadline:
            new=set((ROOT/'bench/results').glob('app-service-'+args.model+'-*'))-before
            if len(new)>1: raise RuntimeError('Unexpected concurrent trial artifacts')
            if new:
                artifact=new.pop();f=artifact/'last-progress.json'
                if f.exists():
                    observation=json.loads(f.read_text())
                    if (observation.get('phase')=='prefill' and
                        512 <= observation.get('completed',0) < observation.get('total',0)):
                        break
            if trial.poll() is not None: raise RuntimeError('Trial ended before profile window')
            time.sleep(1)
        else: raise RuntimeError('Prefill profiling window not reached')
        pid=int(json.loads((artifact/'process.json').read_text())['pid'])
        report.update(artifact=str(artifact.relative_to(ROOT)),pid=pid,profile_start_progress=observation)
        report['optional_memory_observations']={}
        for label,arguments in [('process_rollup',['shell','run-as','org.motoai.runtime','cat',f'/proc/{pid}/smaps_rollup']),
                                ('swap_devices',['shell','cat','/proc/swaps'])]:
            try:
                observed=adb(*arguments,timeout=10,check=False)
                report['optional_memory_observations'][label]={'exit_code':observed.returncode,'stdout':observed.stdout,'stderr':observed.stderr}
            except subprocess.SubprocessError as error:
                report['optional_memory_observations'][label]={'unavailable':str(error)}
        command=[remote+'/simpleperf','record','--app','org.motoai.runtime','-p',str(pid),
                 '-e','task-clock:u','-f','200','--duration','90','-o',remote+'/perf.data']
        report['record_command']=command
        recorded=adb('shell',*command,timeout=150,check=False)
        (out/'record.stdout').write_text(recorded.stdout);(out/'record.stderr').write_text(recorded.stderr)
        report['record_exit_code']=recorded.returncode
        if recorded.returncode: raise RuntimeError('Sampling unavailable; see stderr. No root/global profiling settings changed.')
        report['profile_end_progress']=json.loads((artifact/'last-progress.json').read_text())
        end=report['profile_end_progress']
        report['window_inside_prefill']=(end.get('phase')=='prefill' and
            observation['completed'] <= end.get('completed',0) < end.get('total',0) and
            trial.poll() is None and not (artifact/'result.json').exists())
        if not report['window_inside_prefill']: raise RuntimeError('Sampling window extended beyond prefill; preserve unqualified samples')
        adb('pull',remote+'/perf.data',str(out/'perf.data'))
        # Mirror only verified APK libraries for offline symbols, never pushed.
        symbols=out/'symbols'/pathlib.PurePosixPath(apk_path).parent.relative_to('/')/'lib/arm64'
        symbols.mkdir(parents=True)
        for name in native_symbols: shutil.copyfile(args.native_libs/name,symbols/name)
        hostperf=ROOT/'.tools/android-ndk-r28c/simpleperf/bin/darwin/x86_64/simpleperf'
        # This pinned binary returns empty output for a comma-separated feature
        # list despite its help text. Request each section independently.
        dumped='\n'.join(subprocess.check_output([str(hostperf),'dump','--dump-feature',feature,str(out/'perf.data')],text=True,timeout=30)
                         for feature in ('build_id','meta_info'))
        (out/'record-features.txt').write_text(dumped)
        report['recorded_native_build_ids']=check_recorded_build_ids(dumped,native_symbols,str(pathlib.PurePosixPath(apk_path).parent))
        report['record_statistics']=record_statistics(dumped)
        if any(report['record_statistics'][key] for key in ('kernelspace_lost_records','userspace_lost_samples','userspace_lost_non_samples')):
            raise RuntimeError('Sampling lost records; preserve unqualified diagnostic data')
        decoded=subprocess.run([str(hostperf),'report','-i',str(out/'perf.data'),'--symfs',str(out/'symbols'),
                                '--symdir',str(args.native_libs),
                                '--sort','dso,symbol','--include-pid',str(pid)],capture_output=True,text=True,timeout=120)
        (out/'cpu-self-time.txt').write_text(decoded.stdout+'\n'+decoded.stderr)
        report['offline_report_exit_code']=decoded.returncode
        if decoded.returncode: raise RuntimeError('Offline symbol report failed')
        threads=subprocess.run([str(hostperf),'report','-i',str(out/'perf.data'),
                                '--symfs',str(out/'symbols'),'--symdir',str(args.native_libs),
                                '--sort','tid,comm,dso,symbol','--include-pid',str(pid),'-n'],
                               capture_output=True,text=True,timeout=120)
        (out/'cpu-thread-self-time.txt').write_text(threads.stdout+'\n'+threads.stderr)
        report['thread_report_exit_code']=threads.returncode
        if threads.returncode: raise RuntimeError('Offline per-thread report failed')
        samples=re.search(r'^Samples:\s*(\d+)',decoded.stdout,re.MULTILINE)
        report['target_process_samples']=int(samples.group(1)) if samples else 0
        if report['target_process_samples']<100: raise RuntimeError('Insufficient target-process samples')
        if trial.wait(timeout=900)!=0: raise RuntimeError('Profiled inference failed; do not interpret as accepted performance')
        report['passed']=True
except (OSError,ValueError,RuntimeError,subprocess.SubprocessError) as error:
    report['error']=str(error)
finally:
    def stop_trial():
        if trial is not None and trial.poll() is None:
            try:
                stopped=adb('shell','am','force-stop','org.motoai.runtime',check=False)
            finally:
                try: trial.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    trial.terminate();trial.wait(timeout=10)
            return stopped
    try:
        report['cleanup_errors']=finalize_trial(out,[('stop-profiled-trial',stop_trial),
                           ('remove-profile-directory',lambda: adb('shell','rm','-rf',remote,check=False))],
                       primary_error_active=not report['passed'])
    except Exception as error:
        report['passed']=False
        report['cleanup_error']=str(error)
    finally:
        if (out/'finalization-errors.json').exists():
            report['passed']=False
            report['cleanup_error_report']='finalization-errors.json'
        (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
        print('Results:',out.relative_to(ROOT))
        print('PASS' if report['passed'] else report.get('error',report.get('cleanup_error','FAIL')))
raise SystemExit(0 if report['passed'] else 1)
