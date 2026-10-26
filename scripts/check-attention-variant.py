#!/usr/bin/env python3
"""Independent double-reference matrix for explicit standalone attention candidates."""
import datetime
import argparse
import hashlib
import json
import math
import pathlib
import random
import shlex
import struct
import subprocess
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk', action='store_true', help='Use installed APK libraries rather than the standalone candidate')
p.add_argument('--phone-binary', type=pathlib.Path)
p.add_argument('--long-only', action='store_true', help='Separate 8192-key decode/prefill matrix; preserve the original short cases')
args = p.parse_args()
lease = acquire_phone_lease()
require_phone_ready()
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
out = ROOT / 'bench/results' / ('attention-variant-matrix-' + stamp); out.mkdir(parents=True)
host = ROOT / 'build/cpu-recurrent-host'
phone = ROOT / 'build/cpu-recurrent-android'
phone_exe = args.phone_binary or phone / 'motoai-attention'
destination = '/data/local/tmp/motoai/f32-recurrent-scalar-math'
def adb(*args): return subprocess.run(['adb', '-d', *args], text=True, capture_output=True, check=True, timeout=60)
adb('shell', shlex.join(['mkdir', '-p', destination]))
libraries = destination
apk_sha = None
if args.apk:
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    apk_sha = hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()
    installed = adb('shell', 'pm path org.motoai.runtime').stdout.strip()
    if not installed.startswith('package:') or '\n' in installed: raise RuntimeError('Expected one APK')
    installed = installed[8:]
    if adb('shell', shlex.join(['sha256sum', installed])).stdout.split()[0] != apk_sha: raise RuntimeError('Installed APK differs')
    libraries = str(pathlib.PurePosixPath(installed).parent / 'lib/arm64')
adb('push', str(phone_exe), destination + '/motoai-attention')
if not args.apk:
    for path in (phone / 'bin').glob('*.so'): adb('push', str(path), destination + '/' + path.name)
adb('shell', shlex.join(['chmod', '755', destination + '/motoai-attention']))
report = {'passed': False, 'scope': 'Independent F64 softmax/dot/value reference on exact F32 Q and F16/F32 KV; explicit candidate only. Gates frozen in replay before initial model capture. Masked causal GQA, vec/tiled/split-KV, DK/DV256/512 and unequal dimensions. No speed or full-model claim.',
          'series_sha256': hashlib.sha256((ROOT / 'patches/cpu-numeric/series.json').read_bytes()).hexdigest(), 'cases': [],
          'apk_sha256': apk_sha, 'phone_executable_sha256': hashlib.file_digest(phone_exe.open('rb'), 'sha256').hexdigest(),
          'host_executable_sha256': hashlib.file_digest((host / 'motoai-attention').open('rb'), 'sha256').hexdigest()}
report['case_set'] = '8192-keys' if args.long_only else 'original-short-matrix'
report['controller_sha256'] = hashlib.file_digest(pathlib.Path(__file__).open('rb'), 'sha256').hexdigest()
cases = [(d,d,q,n,'f16') for d in [256,512] for q,n in [(1,32),(5,32),(64,128),(1,512)]]
cases += [(256,128,5,32,'f16'),(512,256,1,512,'f16'),(256,256,5,32,'f32'),(512,512,64,128,'f32')]
if args.long_only:
    cases = [(d,d,q,8192,'f16') for d in [256,512] for q in [1,64]]
    report['scope'] = 'Independent F64 attention reference on exact F32 Q, F16 KV, causal GQA:8192 keys,1/64 queries,DK=DV256/512,1/4 threads. Original gates unchanged; separate from the short/unequal-dimension matrix. No model or speed acceptance.'
for index,(dk,dv,nq,nk,kv_type) in enumerate(cases):
    generator = random.Random(7107+index)
    def tensor(name, shape, kind, values):
        encoded = struct.pack('<' + ('f' if kind == 'f32' else 'e')*len(values), *values)
        return {'name':'attention.'+name+'-6','shape':shape,'type':kind,'data_hex':encoded.hex(),'status':'captured_exact_bytes'}
    # Q must contain values not representable in F16; a coarse power-of-two
    # generator could hide the unwanted query rounding we are testing.
    def values(count): return [(generator.randrange(-1000000,1000001))/1000000 for _ in range(count)]
    hq,hk=8,2
    nodes=[tensor('q',[dk,nq,hq,1],'f32',values(dk*nq*hq)),
           tensor('k',[dk,nk,hk,1],kv_type,values(dk*nk*hk)), tensor('v',[dv,nk,hk,1],kv_type,values(dv*nk*hk)),
           tensor('mask',[nk,nq,1,1],'f16',[0 if key<=nk-nq+query else -math.inf for query in range(nq) for key in range(nk)]),
           tensor('output',[dv,hq,nq,1],'f32',[0]*(dv*hq*nq))]
    params=[0]*16;params[0]=struct.unpack('<i',struct.pack('<f',1/math.sqrt(dk)))[0];params[3]=10
    nodes[-1]['op_params']=params
    fixture=out/f'case-{index}.json';fixture.write_text(json.dumps({'scope':'Generated independent fixture; saved output contains zeros as a placeholder, not a native baseline','result':{'node_probes':nodes}})+'\n')
    adb('push',str(fixture),destination+'/capture.json')
    fixture_sha = hashlib.file_digest(fixture.open('rb'), 'sha256').hexdigest()
    if adb('shell', shlex.join(['sha256sum', destination+'/capture.json'])).stdout.split()[0] != fixture_sha:
        raise RuntimeError('Phone attention input differs from the frozen host bytes')
    for threads in [1,4]:
        entry={'index':index,'dk':dk,'dv':dv,'queries':nq,'keys':nk,'kv_type':kv_type,'threads':threads,'runs':{},
               'fixture_sha256':fixture_sha}
        for name,command in [('host',[str(host/'motoai-attention'),str(host/'bin'),str(fixture),str(threads),'--independent']),
            ('phone',['adb','-d','shell','LD_LIBRARY_PATH='+shlex.quote(libraries)+' '+shlex.join([destination+'/motoai-attention',libraries,destination+'/capture.json',str(threads),'--independent'])])]:
            run=subprocess.run(command,capture_output=True,text=True,timeout=180)
            (out/f'case-{index}-{threads}-{name}.json').write_text(run.stdout)
            (out/f'case-{index}-{threads}-{name}.stderr.txt').write_text(run.stderr)
            result=json.loads(run.stdout);result.pop('output_hex',None);entry['runs'][name]=result
        entry['passed']=all(result['passed'] and result['independent_reference_passed'] for result in entry['runs'].values())
        report['cases'].append(entry)
        (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
        print(f'case{index} dk={dk} dv={dv} q={nq} k={nk} {kv_type} threads={threads}: {entry["passed"]}',flush=True)
report['passed']=all(case['passed'] for case in report['cases']) and len(report['cases'])==2*len(cases)
(out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print('Results:',out.relative_to(ROOT))
raise SystemExit(0 if report['passed'] else 1)
