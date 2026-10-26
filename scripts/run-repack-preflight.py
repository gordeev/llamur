#!/usr/bin/env python3
"""Audit CPU_REPACK metadata/weight bits with the installed APK's native libraries."""
import argparse
import datetime
import hashlib
import json
import pathlib
import shlex
import subprocess
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready
from phone_model import model_source

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--binary-directory', type=pathlib.Path, required=True)
p.add_argument('--policy', choices=['cpu_repack','cpu_repack_q6','cpu_repack_q4'], default='cpu_repack')
p.add_argument('--plan-lock', type=pathlib.Path)
args = p.parse_args()
if args.policy in ('cpu_repack_q6','cpu_repack_q4') and not args.plan_lock: p.error('Selective policy requires --plan-lock')
lease = acquire_phone_lease()
health = require_phone_ready()
out = ROOT/'bench/results'/('repack-apk-preflight-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
out.mkdir(parents=True)
report = {'passed':False, 'health':health, 'cases':[], 'scope':'Installed APK libraries, shell metadata/inverse-bit audit; not app speed or quality'}
def adb(*cmd, **kw):
    return subprocess.run(['adb','-d',*cmd],capture_output=True,text=True,timeout=180,**kw)
try:
    installed = adb('shell','pm path org.motoai.runtime',check=True).stdout.strip()
    if not installed.startswith('package:') or '\n' in installed: raise RuntimeError('Expected one APK')
    apk = ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    report['apk_sha256'] = hashlib.file_digest(apk.open('rb'),'sha256').hexdigest()
    if adb('shell',shlex.join(['sha256sum',installed[8:]]),check=True).stdout.split()[0] != report['apk_sha256']:
        raise RuntimeError('APK identity mismatch')
    libs = str(pathlib.PurePosixPath(installed[8:]).parent/'lib/arm64')
    remote = '/data/local/tmp/motoai/m3-repack-apk-preflight'
    adb('shell',shlex.join(['mkdir','-p',remote]),check=True)
    config = out/'config.json'
    config.write_text(json.dumps({'context':8192,'threads':4,'batch':512,'micro_batch':128,'load_mode':'mmap'}))
    adb('push',str(config),remote+'/config.json',check=True)
    report['executables_sha256'] = {}
    for name in ['motoai-load-plan','motoai-repack-audit']:
        path = args.binary_directory/name
        report['executables_sha256'][name] = hashlib.file_digest(path.open('rb'),'sha256').hexdigest()
        adb('push',str(path),remote+'/'+name,check=True)
        adb('shell',shlex.join(['chmod','755',remote+'/'+name]),check=True)
    models = [m for m in json.loads((ROOT/'bench/models.lock.json').read_text())['models'] if m['id'] in ('gemma','ornith')]
    paths = [model_source(m['filename'])['path'] for m in models]
    if not all(path.startswith(('/storage/emulated/0/Download/','/sdcard/Download/')) for path in paths):
        raise RuntimeError('Diagnostic expects existing shared models; never creates duplicate weights')
    commands = [(m['id']+'-plan',[remote+'/motoai-load-plan',path,remote+'/config.json',args.policy,libs]) for m,path in zip(models,paths)]
    commands += [('inverse-audit',[remote+'/motoai-repack-audit',libs,*paths])]
    for label,cmd in commands:
        run = adb('shell','LD_LIBRARY_PATH='+shlex.quote(libs)+' '+shlex.join(cmd))
        (out/(label+'.stdout.txt')).write_text(run.stdout)
        (out/(label+'.stderr.txt')).write_text(run.stderr)
        data = json.loads(run.stdout)
        (out/(label+'.json')).write_text(json.dumps(data,indent=2)+'\n')
        passed = run.returncode == 0 and data.get('passed',data.get('ok')) is True
        if label.endswith('-plan'):
            expected = (json.loads(args.plan_lock.read_text())['models'][label.split('-')[0]]['packed_model_bytes']
                if args.plan_lock else 2746060800 if label.startswith('gemma') else 5041766400)
            passed &= data.get('available_cpu_extra_buffers') == ['CPU_REPACK'] and data.get('packed_model_bytes') == expected
            allowed = ('q6_K',) if args.policy == 'cpu_repack_q6' else ('q4_K',) if args.policy == 'cpu_repack_q4' else ('q4_K','q6_K')
            passed &= all(t['type'] in allowed for t in data['tensors'] if t['buffer']=='CPU_REPACK')
        report['cases'].append({'label':label,'command':cmd,'returncode':run.returncode,'passed':passed})
        if not passed: raise RuntimeError('Preflight failed: '+label)
    report['passed'] = True
except (OSError,ValueError,KeyError,RuntimeError,subprocess.SubprocessError) as error:
    report['error'] = str(error)
finally:
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
    print('Results:',out.relative_to(ROOT))
raise SystemExit(0 if report['passed'] else 1)
