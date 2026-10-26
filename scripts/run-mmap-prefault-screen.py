#!/usr/bin/env python3
"""Serial, same-APK native demand-paging screening; no weight/math changes."""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import sys

from phone_health import require_phone_ready, read_health
from phone_lease import acquire_phone_lease

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('models', nargs='+', choices=['ornith', 'gemma'])
p.add_argument('--ornith-long-pairs', type=int, choices=[1, 2], default=2)
p.add_argument('--cutoff', help='Optional local time: finish active stage, do not start a new one; no inherited cutoff')
args = p.parse_args()
cutoff = datetime.time.fromisoformat(args.cutoff) if args.cutoff else None
lease = acquire_phone_lease()
initial = require_phone_ready()
out = ROOT/'bench/results'/('m3-native-demand-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
out.mkdir(parents=True)
report = {'passed':False, 'scope':'Bounded native mmap loading control; neither M3 nor product/endurance acceptance',
          'apk_sha256':hashlib.file_digest((ROOT/'app/build/outputs/apk/debug/app-debug.apk').open('rb'),'sha256').hexdigest(),
          'candidate_policy':'native_no_prefault', 'weights_unchanged':True, 'kv_type':'f16',
          'initial_health':initial, 'cutoff_local':args.cutoff, 'steps':[], 'models':{}}


def save():
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')


def run(label, arguments, result_file='report.json'):
    if cutoff is not None and datetime.datetime.now().time() >= cutoff:
        raise RuntimeError('Local cutoff reached before new stage: '+label)
    print('Starting',label,flush=True)
    row = {'label':label,'command':[sys.executable,*arguments]}
    report['steps'].append(row);save()
    result = subprocess.run(row['command'],cwd=ROOT,capture_output=True,text=True,**lease.child_options())
    (out/(label+'.log')).write_text(result.stdout+'\n'+result.stderr)
    row['exit_code'] = result.returncode
    match = re.search(r'^Results: (bench/results/[^\n]+)$',result.stdout,re.MULTILINE)
    if match: row['report'] = match.group(1)+'/'+result_file
    save()
    if result.returncode: raise RuntimeError('Stage failed: '+label+'; preserved log and raw artifacts')
    print('Completed',label,row.get('report',''),flush=True)
    return row.get('report')


try:
    report['framework'] = run('framework',['scripts/run-runtime-tests.py'])
    for model in args.models:
        item = {'evidence':{}};report['models'][model]=item
        long_fixture = 'needle-7680-exact-mmap-v2' if model=='ornith' else 'pp8064-full-mmap-v1'
        reference = ('app-service-ornith-20261024T220836Z-82257c5b' if model=='ornith' else
                     'app-service-gemma-20261024T221603Z-2398c2cc')
        for key,fixture in [('code_nll','teacher-forcing-code-v1'),('russian_nll','teacher-forcing-ru-v1'),
                            ('completed_task','binary-search-quality-4096-mmap-v1'),('filled_context',long_fixture)]:
            command=['scripts/compare-weight-buffers.py',model,fixture,'--candidate-policy','native_no_prefault','--require-identical']
            if key=='completed_task': command+=['--reference-artifact','bench/results/'+reference]
            if key=='filled_context': command+=['--repeat',str(args.ornith_long_pairs if model=='ornith' else 1)]
            item['evidence'][key]=run(model+'-'+key,command)
        lock=out/(model+'-quality-gate.json')
        command=['scripts/accept-repack-screening.py',model,'--output',str(lock)]
        for key,path in item['evidence'].items(): command+=['--'+key.replace('_','-'),path]
        run(model+'-quality-lock',command)
        item['quality_lock']=str(lock.relative_to(ROOT))
        item['speed']=run(model+'-short-speed',['scripts/compare-weight-buffers.py',model,'short-code-mmap-v1',
            '--candidate-policy','native_no_prefault','--repeat','5','--warmup','1','--quality-gate',str(lock)])
        item['bounded_screen_completed']=True;save()
    report['passed']=True
except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
    report['error']=str(error)
finally:
    report['final_health']=read_health();save()
    print('Results:',out.relative_to(ROOT),flush=True)
    print('PASS' if report['passed'] else report.get('error','FAIL'),flush=True)
raise SystemExit(0 if report['passed'] else 1)
