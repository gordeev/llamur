#!/usr/bin/env python3
"""Replay qualified public long-attention inputs against exact packaged CPU kernels."""
import argparse,datetime,hashlib,json,pathlib,shlex,subprocess,tempfile,uuid,zipfile
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready,check_running_health,read_health
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk',type=pathlib.Path,required=True);p.add_argument('--reference-apk',type=pathlib.Path,required=True)
p.add_argument('--phone-binary',type=pathlib.Path,required=True);p.add_argument('--host-controls',type=pathlib.Path,required=True)
a=p.parse_args()
if any(not x.resolve().is_relative_to(ROOT) for x in (a.apk,a.reference_apk,a.phone_binary,a.host_controls)):p.error('Workspace artifacts only')
host=json.loads((a.host_controls/'report.json').read_text())
if not host.get('passed') or len(host.get('cases',[]))!=12 or len(host.get('short_controls',[]))!=12:raise RuntimeError('Complete same-source12 long+12 unchanged short host controls required')
lease=acquire_phone_lease();health=require_phone_ready()
out=ROOT/'bench/results'/('attention-long-phone-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'));out.mkdir()
sha=lambda x:hashlib.file_digest(x.open('rb'),'sha256').hexdigest()
report=dict(passed=False,scope='Standalone shell CPU operator controls using exact APK libraries. Not app-UID inference/context capacity/speed/endurance acceptance',apk_sha256=sha(a.apk),reference_apk_sha256=sha(a.reference_apk),phone_executable_sha256=sha(a.phone_binary),host_report_sha256=sha(a.host_controls/'report.json'),health_before=health,cases=[],short_controls=[])
remote='/data/local/tmp/motoai-long-attention-'+uuid.uuid4().hex
(out/'controller.py').write_bytes(pathlib.Path(__file__).read_bytes())
def adb(*parts,timeout=60):return subprocess.check_output(['adb','-d',*parts],text=True,stderr=subprocess.STDOUT,timeout=timeout)
def save():(out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
def transfer(source,target):
 adb('push',str(source),target)
 if adb('shell',shlex.join(['sha256sum',target])).split()[0]!=sha(source):raise RuntimeError('Transfer SHA differs')
def run(label,fixture,directory,threads):
 command='LD_LIBRARY_PATH='+shlex.quote(directory)+' taskset 7f '+shlex.join([remote+'/replay',directory,fixture,str(threads),'--independent'])
 done=subprocess.run(['adb','-d','shell',command],capture_output=True,text=True,timeout=360)
 (out/(label+'.json')).write_text(done.stdout);(out/(label+'.log')).write_text(done.stderr)
 data=json.loads(done.stdout)
 if done.returncode or not data.get('passed'):raise RuntimeError('Unchanged F64 gate failed: '+label)
 return data
try:
 adb('shell','am','force-stop','org.motoai.runtime')
 adb('shell',shlex.join(['mkdir','-p',remote+'/candidate',remote+'/reference']))
 transfer(a.phone_binary,remote+'/replay');adb('shell',shlex.join(['chmod','755',remote+'/replay']))
 report['libraries']={}
 with tempfile.TemporaryDirectory(prefix='motoai-long-op-libs-') as tmp:
  for label,apk in [('candidate',a.apk),('reference',a.reference_apk)]:
   report['libraries'][label]={}
   with zipfile.ZipFile(apk) as z:
    for name in z.namelist():
     if name.startswith('lib/arm64-v8a/') and name.endswith('.so'):
      local=pathlib.Path(tmp)/pathlib.PurePosixPath(name).name;local.write_bytes(z.read(name));transfer(local,remote+'/'+label+'/'+local.name);report['libraries'][label][local.name]=sha(local)
 for index in range(12):
  check_running_health(health)
  path=ROOT/f'bench/results/attention-variant-matrix-20261024T205935Z/case-{index}.json';transfer(path,remote+'/case.json')
  old=run(f'short-{index}-reference4',remote+'/case.json',remote+'/reference',4)
  new=run(f'short-{index}-candidate4',remote+'/case.json',remote+'/candidate',4)
  exact=old['output_hex']==new['output_hex'];report['short_controls'].append(dict(case=index,baseline4_bitwise_preserved=exact));save()
  if not exact:raise RuntimeError('Accepted short attention bits changed')
 for case in host['cases']:
  check_running_health(health)
  path=a.host_controls/(case['name']+'.json')
  if sha(path)!=case['fixture_sha256']:raise RuntimeError('Host long fixture differs')
  transfer(path,remote+'/case.json');row=dict(name=case['name'],fixture_sha256=sha(path),runs={});report['cases'].append(row);outputs=[]
  for threads in ([1,4,7] if case['name'].endswith('-q1') else [4]):
   data=run(case['name']+'-t'+str(threads),remote+'/case.json',remote+'/candidate',threads)
   row['runs'][str(threads)]={k:v for k,v in data.items() if k!='output_hex'};outputs.append(data['output_hex']);save()
  row['thread_bitwise_equal']=len(set(outputs))==1
  if not row['thread_bitwise_equal']:raise RuntimeError('Decode depends on thread count')
  save();print(case['name'],'PASS',flush=True)
 report['passed']=True
except Exception as error:report['error']=str(error)
finally:
 try:adb('shell',shlex.join(['rm','-rf',remote]))
 except Exception as error:report['cleanup_error']=str(error);report['passed']=False
 report['health_after']=read_health();save();print('Results:',out.relative_to(ROOT),flush=True)
raise SystemExit(0 if report['passed'] else 1)
