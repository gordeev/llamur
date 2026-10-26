#!/usr/bin/env python3
"""Independent F64 attention controls at16K/32K keys using public synthetic bytes."""
import argparse,datetime,hashlib,json,pathlib,struct,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--build',type=pathlib.Path,required=True);p.add_argument('--baseline-build',type=pathlib.Path);p.add_argument('--queries',type=int,nargs='+',default=[1,5,64]);p.add_argument('--short-only',action='store_true');a=p.parse_args()
if a.short_only and not a.baseline_build:p.error('--short-only requires --baseline-build')
if any(x not in (1,5,64) for x in a.queries):p.error('Declared query controls:1/5/64')
out=ROOT/'bench/results'/('attention-long-host-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'));out.mkdir()
report=dict(passed=False,scope='Standalone CPU attention/F64/bitwise thread controls only; no model/filled-context/Android/speed acceptance',cases=[])
(out/'controller.py').write_bytes(pathlib.Path(__file__).read_bytes())
report['executable_sha256']=hashlib.file_digest((a.build/'motoai-attention').open('rb'),'sha256').hexdigest()
try:
 if a.baseline_build:
  report['short_controls']=[]
  for index in range(12):
   fixture=ROOT/f'bench/results/attention-variant-matrix-20261024T205935Z/case-{index}.json'
   outputs=[]
   for label,build in [('baseline',a.baseline_build),('candidate',a.build)]:
    run=subprocess.run([str(build/'motoai-attention'),str(build/'bin'),str(fixture),'4','--independent'],capture_output=True,text=True,timeout=60)
    data=json.loads(run.stdout);(out/f'short-{index}-{label}.json').write_text(run.stdout)
    if run.returncode or not data.get('passed'):raise RuntimeError('Short F64 control failed')
    outputs.append(data['output_hex'])
   exact=outputs[0]==outputs[1];report['short_controls'].append(dict(case=index,baseline4_bitwise_preserved=exact))
   if not exact:raise RuntimeError('Previously accepted short arithmetic changed')
 for width,base in ((256,3),(512,7)):
  for keys in (() if a.short_only else (16384,32768)):
   for queries in a.queries:
    document=json.loads((ROOT/f'bench/results/attention-variant-matrix-20261024T205935Z/case-{base}.json').read_text())
    for node in document['result']['node_probes']:
     name=node['name'];shape=node['shape'];data=bytes.fromhex(node['data_hex']);element=4 if node['type']=='f32' else 2
     if name=='attention.q-6':
      # Base is one query. Repeat each head's query, not all heads as a flat slab.
      row=shape[0]*element;node['data_hex']=b''.join(data[h*row:(h+1)*row]*queries for h in range(shape[2])).hex();shape[1]=queries
     elif name in ('attention.k-6','attention.v-6'):
      old=shape[1];per_head=shape[0]*old*element
      node['data_hex']=b''.join((data[h*per_head:(h+1)*per_head]*((keys+old-1)//old))[:keys*shape[0]*element] for h in range(shape[2])).hex();shape[1]=keys
     elif name=='attention.mask-6':
      # Distinct valid causal lengths, including a padded non-power-of-two tail.
      node['data_hex']=b''.join(struct.pack('<'+'e'*keys,*([0.]*(keys-queries+t)+[-float('inf')]*(queries-t))) for t in range(queries)).hex();shape[0]=keys;shape[1]=queries
     elif name=='attention.output-6':
      shape[2]=queries;node['data_hex']=bytes(shape[0]*shape[1]*shape[2]*4).hex()
    name=f'd{width}-k{keys}-q{queries}';path=out/(name+'.json');path.write_text(json.dumps(document)+'\n')
    row=dict(name=name,fixture_sha256=hashlib.file_digest(path.open('rb'),'sha256').hexdigest(),runs={});report['cases'].append(row);outputs={}
    for threads in (1,4,7):
     run=subprocess.run([str(a.build/'motoai-attention'),str(a.build/'bin'),str(path),str(threads),'--independent'],capture_output=True,text=True,timeout=180)
     (out/f'{name}-t{threads}.json').write_text(run.stdout);(out/f'{name}-t{threads}.log').write_text(run.stderr);data=json.loads(run.stdout)
     row['runs'][str(threads)]={k:v for k,v in data.items() if k!='output_hex'};outputs[threads]=data['output_hex']
     if run.returncode or data.get('passed') is not True:raise RuntimeError('Unchanged independent F64 gate failed: '+name)
    row['thread_bitwise_equal']=len(set(outputs.values()))==1
    if queries==1 and not row['thread_bitwise_equal']:raise RuntimeError('Decode thread-dependent arithmetic: '+name)
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(name,'PASS',flush=True)
 report['passed']=True
except Exception as error:report['error']=str(error)
finally:
 (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print('Results:',out.relative_to(ROOT),flush=True)
raise SystemExit(0 if report['passed'] else 1)
