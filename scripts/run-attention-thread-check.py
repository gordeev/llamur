#!/usr/bin/env python3
"""Validate canonical decode reduction using candidate APK libraries before install."""
import argparse
import copy
import datetime
import hashlib
import json
import pathlib
import shlex
import struct
import subprocess
import tempfile
import uuid
import zipfile
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready, read_health

ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--phone-binary',type=pathlib.Path,required=True)
p.add_argument('--host-build',type=pathlib.Path,default=ROOT/'build/stable-attention-v2-host')
args=p.parse_args();lease=acquire_phone_lease();health=require_phone_ready()
apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk';sha=lambda path:hashlib.file_digest(path.open('rb'),'sha256').hexdigest()
out=ROOT/'bench/results'/('attention-thread-check-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir(parents=True)
report=dict(passed=False,scope='Standalone exact APK CPU kernels before install: decode single-query output must equal original4-worker bits for1/2/3/4/5/6/7 workers; prefill4 bits preserved. Every run keeps original independent F64 gates. No weights, model inference or speed acceptance.',candidate_apk_sha256=sha(apk),initial_health=health,cases=[])
(out/'controller.py').write_bytes(pathlib.Path(__file__).read_bytes())
def adb(*parts):return subprocess.check_output(['adb','-d',*parts],text=True,timeout=60)
installed=adb('shell','pm path org.motoai.runtime').strip()
if not installed.startswith('package:') or '\n' in installed:raise RuntimeError('Expected one reference APK')
installed=installed[8:];old_libs=str(pathlib.PurePosixPath(installed).parent/'lib/arm64')
report['installed_reference_apk_sha256']=adb('shell',shlex.join(['sha256sum',installed])).split()[0]
report['phone_executable_sha256']=sha(args.phone_binary);report['host_executable_sha256']=sha(args.host_build/'motoai-attention')
remote='/data/local/tmp/motoai-fa-canonical-'+uuid.uuid4().hex

def save(): (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')

def extra_case(name,heads=None,keys=None,valid_keys=None):
    d=json.loads((ROOT/'bench/results/attention-variant-matrix-20261024T205935Z/case-3.json').read_text())
    for node in d['result']['node_probes']:
        kind=node['name'];shape=node['shape'];width=4 if node['type']=='f32' else 2;data=bytes.fromhex(node['data_hex'])
        if kind=='attention.q-6' and heads:
            node['data_hex']=(data*(heads//shape[2])).hex();shape[2]=heads
        elif kind=='attention.output-6' and heads:
            shape[1]=heads;node['data_hex']=bytes(shape[0]*heads*4).hex()
        elif kind in ('attention.k-6','attention.v-6') and keys:
            old=shape[1];per_head=shape[0]*old*width;pieces=[]
            for head in range(shape[2]):
                values=data[head*per_head:(head+1)*per_head];row=shape[0]*width
                pieces.append((values*((keys+old-1)//old))[:keys*row])
            node['data_hex']=b''.join(pieces).hex();shape[1]=keys
        elif kind=='attention.mask-6' and (keys or valid_keys):
            size=keys or shape[0];valid=valid_keys or size;shape[0]=size
            node['data_hex']=struct.pack('<'+'e'*size,*([0.0]*valid+[-float('inf')]*(size-valid))).hex()
    return name,d

try:
    actual_mask=adb('shell',"taskset 7f sh -c 'cat /proc/self/status'")
    report['shell_affinity_status']=actual_mask
    if 'Cpus_allowed_list:\t0-6' not in actual_mask:raise RuntimeError('Numerical thread matrix requires actual0–6 CPUs')
    adb('shell',shlex.join(['mkdir','-p',remote+'/candidate']))
    adb('push',str(args.phone_binary),remote+'/replay');adb('shell','chmod 755 '+remote+'/replay')
    with tempfile.TemporaryDirectory(prefix='motoai-fa-libs-') as tmp,zipfile.ZipFile(apk) as z:
        report['candidate_libraries_sha256']={}
        for name in z.namelist():
            if name.startswith('lib/arm64-v8a/') and name.endswith('.so'):
                local=pathlib.Path(tmp)/pathlib.PurePosixPath(name).name;local.write_bytes(z.read(name));report['candidate_libraries_sha256'][local.name]=sha(local)
                adb('push',str(local),remote+'/candidate/'+local.name)
                if adb('shell',shlex.join(['sha256sum',remote+'/candidate/'+local.name])).split()[0]!=report['candidate_libraries_sha256'][local.name]:raise RuntimeError('Candidate library transfer differs')
    cases=[('base-'+str(i),json.loads((ROOT/f'bench/results/attention-variant-matrix-20261024T205935Z/case-{i}.json').read_text())) for i in range(12)]
    cases += [extra_case('heads128-k512',heads=128),extra_case('padded257-k512',valid_keys=257),extra_case('odd-k513',keys=513),extra_case('long-k8192',keys=8192)]
    for name,document in cases:
        path=out/(name+'.json');path.write_text(json.dumps(document)+'\n');adb('push',str(path),remote+'/case.json')
        if adb('shell',shlex.join(['sha256sum',remote+'/case.json'])).split()[0]!=sha(path):raise RuntimeError('Frozen tensor transfer differs')
        entry=dict(name=name,fixture_sha256=sha(path),runs={});report['cases'].append(entry);save()
        outputs={}
        threads=[1,4,7]
        if name in ('base-3','heads128-k512'):threads=[1,2,3,4,5,6,7]
        jobs=[('reference-phone4',['adb','-d','shell','LD_LIBRARY_PATH='+shlex.quote(old_libs)+' taskset 7f '+shlex.join([remote+'/replay',old_libs,remote+'/case.json','4','--independent'])])]
        for n in threads:
            jobs += [(f'candidate-phone{n}',['adb','-d','shell','LD_LIBRARY_PATH='+shlex.quote(remote+'/candidate')+' taskset 7f '+shlex.join([remote+'/replay',remote+'/candidate',remote+'/case.json',str(n),'--independent'])]),
                     (f'candidate-host{n}',[str(args.host_build/'motoai-attention'),str(args.host_build/'bin'),str(path),str(n),'--independent'])]
        for label,command in jobs:
            run=subprocess.run(command,capture_output=True,text=True,timeout=180)
            (out/(name+'-'+label+'.json')).write_text(run.stdout);(out/(name+'-'+label+'.stderr.txt')).write_text(run.stderr)
            data=json.loads(run.stdout);outputs[label]=data['output_hex'];entry['runs'][label]={key:value for key,value in data.items() if key!='output_hex'}
            if run.returncode or data.get('passed') is not True:raise RuntimeError('Unchanged F64 gates failed: '+name+'/'+label)
        query=next(node for node in document['result']['node_probes'] if node['name']=='attention.q-6')
        entry['single_query']=query['shape'][1]==1
        entry['baseline4_preserved']=outputs['candidate-phone4']==outputs['reference-phone4']
        entry['phone_threads_identical']=all(outputs[f'candidate-phone{n}']==outputs['reference-phone4'] for n in threads)
        entry['host_threads_identical']=all(outputs[f'candidate-host{n}']==outputs['candidate-host4'] for n in threads)
        entry['host_phone4_identical']=outputs['candidate-host4']==outputs['candidate-phone4']
        entry['passed']=entry['baseline4_preserved'] and (not entry['single_query'] or entry['phone_threads_identical'] and entry['host_threads_identical'])
        save();print(name,entry['passed'],'decode',entry['single_query'],'thread_exact',entry['phone_threads_identical'],flush=True)
        if not entry['passed']:raise RuntimeError('Canonical decode/baseline4 bits failed: '+name)
    report['baseline4_preserved']=all(row['baseline4_preserved'] for row in report['cases'])
    report['decode_threads_identical']=all(row['phone_threads_identical'] and row['host_threads_identical'] for row in report['cases'] if row['single_query'])
    report['independent_reference_passed']=True;report['passed']=True
except (OSError,ValueError,KeyError,RuntimeError,subprocess.SubprocessError,KeyboardInterrupt) as error:report['error']=str(error) or type(error).__name__
finally:
    try:adb('shell',shlex.join(['rm','-rf',remote]))
    except (OSError,subprocess.SubprocessError) as error:report['cleanup_error']=str(error);report['passed']=False
    report['final_health']=read_health();save();print('Results:',out.relative_to(ROOT),flush=True);print('PASS' if report['passed'] else report.get('error','FAIL'),flush=True)
raise SystemExit(0 if report['passed'] else 1)
