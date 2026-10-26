#!/usr/bin/env python3
"""Single-engine handset B/P/P/B UI overhead observation, not speed promotion."""
import argparse,datetime,hashlib,json,pathlib,statistics,subprocess,uuid
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready,read_health
from phone_model import model_sha256
from frequency_caps import POLICY_COMMAND
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--reuse-installation',action='store_true');p.add_argument('model',choices=['ornith','gemma']);p.add_argument('--apk',type=pathlib.Path,required=True);p.add_argument('--tests',type=pathlib.Path,required=True);a=p.parse_args()
for f in [a.apk,a.tests]:
 if not f.resolve().is_relative_to(ROOT):p.error('Frozen workspace APKs required')
model=next(m for m in json.loads((ROOT/'bench/models.lock.json').read_text())['models'] if m['id']==a.model)
lease=acquire_phone_lease();health=require_phone_ready();out=ROOT/'bench/results'/('ui-performance-'+a.model+'-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
r=dict(passed=False,health_before=health,model=a.model,model_sha256=model['sha256'],apk_sha256=hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest(),test_apk_sha256=hashlib.file_digest(a.tests.open('rb'),'sha256').hexdigest(),scope='Two B/P/P/B pairs on one loaded CPU4/4K engine with original GGUF, short public prompt,128-token reserve, thinkingON/cacheOFF/transientOFF. Actual UI sends; raw output equality required. An overhead observation, not5-pair speed acceptance, long-history UI, general model quality or discharge energy certification.')
def adb(*items,timeout=30):return subprocess.run(['adb','-d',*items],capture_output=True,text=True,timeout=timeout,check=True)
try:
 adb('shell','am','force-stop','org.motoai.runtime')
 if not a.reuse_installation:
  adb('install','-r',str(a.apk),timeout=300);adb('install','-t','-r',str(a.tests),timeout=300)
 for package,expected in [('org.motoai.runtime',r['apk_sha256']),('org.motoai.runtime.test',r['test_apk_sha256'])]:
  path=adb('shell','pm','path',package).stdout.strip().removeprefix('package:')
  if adb('shell','sha256sum',path).stdout.split()[0]!=expected:raise RuntimeError('Installed APK differs')
 if model_sha256(model['filename'])!=model['sha256']:raise RuntimeError('Original GGUF SHA differs')
 r['policies_before']=adb('shell',POLICY_COMMAND).stdout
 adb('shell','run-as','org.motoai.runtime','rm','-f','files/runtime-ui-performance-tests.json')
 result=adb('shell','am','instrument','-w','-r','-e','scope','ui-performance','-e','model',model['filename'],'org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation',timeout=2400)
 (out/'instrumentation.txt').write_text(result.stdout+result.stderr)
 raw=adb('exec-out','run-as','org.motoai.runtime','cat','files/runtime-ui-performance-tests.json').stdout;(out/'app-report.json').write_text(raw);app=json.loads(raw);r['app']=app
 if not app.get('passed') or 'INSTRUMENTATION_CODE: -1' not in result.stdout:raise RuntimeError(app.get('error','Instrumentation failed'))
 rows=app['ui_performance']['rows']
 if [v['kind'] for v in rows]!=['baseline','product','product','baseline']:raise RuntimeError('Unexpected trial order')
 invariant=[(v['snapshot']['result']['prompt_tokens'],v['snapshot']['result']['generated_tokens'],v['snapshot']['result']['finish_reason'],v['output_sha256']) for v in rows]
 if len(set(invariant))!=1:raise RuntimeError('Token counts, finish or raw output differ')
 ratios=[rows[1]['wall_us']/rows[0]['wall_us'],rows[2]['wall_us']/rows[3]['wall_us']]
 r['wall_product_over_baseline_pairs']=ratios;r['median_wall_ratio']=statistics.median(ratios);r['speed_promoted']=False
 r['policies_after']=adb('shell',POLICY_COMMAND).stdout;r['passed']=True
except Exception as error:r['error']=str(error)
finally:
 r['health_after']=read_health()
 for source in ['scripts/run-ui-performance.py','app/src/androidTest/java/org/motoai/runtime/UiPerformanceChecks.kt']:(out/pathlib.Path(source).name).write_bytes((ROOT/source).read_bytes())
 (out/'report.json').write_text(json.dumps(r,indent=2)+'\n');print('Results:',out.relative_to(ROOT));print('PASS' if r['passed'] else r['error'])
raise SystemExit(0 if r['passed'] else 1)
