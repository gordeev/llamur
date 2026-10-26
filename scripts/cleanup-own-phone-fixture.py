#!/usr/bin/env python3
"""Remove only the known public developer tiny fixture; preserve main/user models."""
import datetime,hashlib,json,pathlib,re,subprocess,time,xml.etree.ElementTree as ET
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready,read_health
from phone_model import model_sha256
ROOT=pathlib.Path(__file__).resolve().parents[1];lease=acquire_phone_lease();health=require_phone_ready()
out=ROOT/'bench/results'/('fixture-cleanup-phone-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
fixture=next(m for m in json.loads((ROOT/'bench/models.lock.json').read_text())['models'] if m['id']=='smoke');name=fixture['filename'];r={'passed':False,'health_before':health,'scope':'Only exact known stories260K public developer fixture, app-managed canonical copy through actual UI plus verified old private test copy. Unknown/external sources preserved. Main original GGUF SHA rechecked; no general storage cleanup.'}
def adb(*args,check=True):return subprocess.run(['adb','-d',*args],check=check,capture_output=True,text=True,timeout=180).stdout
remote='/sdcard/motoai-own-fixture-cleanup.xml'
def tree():
 policy=adb('shell','dumpsys','window','policy');showing=re.search(r'^\s*showing=(true|false)\s*$',policy,re.M)
 if not showing or showing[1]!='false':raise RuntimeError('Owner unlock required before reading only MotoAI UI; no keyguard UI captured')
 rows=[v for v in adb('shell','dumpsys','activity','activities').splitlines() if re.search(r'\b(?:mResumedActivity|topResumedActivity)\b',v)]
 if not rows or not all('org.motoai.runtime/' in row for row in rows):raise RuntimeError('MotoAI not resumed; no private UI captured')
 adb('shell','uiautomator','dump',remote);return ET.fromstring(adb('exec-out','cat',remote))
def tap(label):
 candidates=[n for n in tree().iter('node') if label.casefold() in n.get('text','').casefold()];exact=[n for n in candidates if n.get('text','').casefold()==label.casefold()]
 if not candidates:raise RuntimeError('Missing own app action: '+label)
 n=(exact or candidates)[0];x,y,xx,yy=map(int,re.findall(r'\d+',n.get('bounds')));adb('shell','input','tap',str((x+xx)//2),str((y+yy)//2));time.sleep(.6)
def await_owner_unlock():
 deadline=time.monotonic()+180
 waiting=out/'waiting-owner-unlock.json';waiting.write_text(json.dumps({'waiting_for_owner_unlock':True,'scope':'Owner must unlock normally before MotoAI-only UI inspection'})+'\n')
 try:
  while True:
   policy=adb('shell','dumpsys','window','policy');showing=re.search(r'^\s*showing=(true|false)\s*$',policy,re.M)
   if showing and showing[1]=='false':return
   if time.monotonic()>deadline:raise RuntimeError('Owner unlock required; no security settings changed')
   time.sleep(1)
 finally:waiting.unlink(missing_ok=True)
try:
 installed=adb('shell','pm','path','org.motoai.runtime').strip().removeprefix('package:')
 if adb('shell','sha256sum',installed).split()[0]!='d281a711f30249cbd0282393a313cbb222fd366ab03f4597b389553fa4e23980':raise RuntimeError('Expected final frozen test-enabled APK before normal release install')
 adb('shell','am','force-stop','org.motoai.runtime')
 adb('shell','input','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard');await_owner_unlock()
 raw=adb('exec-out','run-as','org.motoai.runtime','cat','files/models/'+name+'.shared.json',check=False)
 shared=json.loads(raw) if raw.lstrip().startswith('{') else None
 external=shared is not None and shared.get('kind')=='external_saf'
 if external:r['external_fixture_preserved']=True
 elif shared is not None:
  if shared.get('sha256')!=fixture['sha256'] or shared.get('bytes')!=fixture['bytes'] or model_sha256(name)!=fixture['sha256']:raise RuntimeError('Fixture identity differs; preserved')
  adb('shell','am','start','-n','org.motoai.runtime/.ChatActivity');time.sleep(1)
  tap('Модели');tap('Менеджер места');tap(name);tap('Удалить');time.sleep(1)
  raw=adb('exec-out','run-as','org.motoai.runtime','cat','files/models/'+name+'.shared.json',check=False)
  if raw.lstrip().startswith('{'):raise RuntimeError('Managed fixture registry still exists')
  r['managed_fixture_removed_through_ui']=True
 if not external:
  digest=adb('shell','run-as','org.motoai.runtime','sha256sum','files/models/'+name,check=False).split()
  if digest and re.fullmatch('[0-9a-f]{64}',digest[0]):
   if digest[0]!=fixture['sha256']:raise RuntimeError('Private fixture changed; preserved')
   adb('shell','run-as','org.motoai.runtime','rm','files/models/'+name);r['old_private_test_copy_removed']=True
 r['main_models_sha256']={}
 for model in json.loads((ROOT/'bench/models.lock.json').read_text())['models']:
  if model['id']=='smoke':continue
  actual=model_sha256(model['filename']);r['main_models_sha256'][model['id']]=actual
  if actual!=model['sha256']:raise RuntimeError('Main original model SHA differs')
 # Exact project test executable/input only, not any model or unknown temporary folder.
 adb('shell','rm','-f','/data/local/tmp/motoai/m2-numeric/motoai-numeric','/data/local/tmp/motoai/m2-numeric/gates.json')
 r['passed']=True
except Exception as error:r['error']=str(error)
finally:
 try:adb('shell','am','force-stop','org.motoai.runtime');adb('shell','rm','-f',remote)
 except Exception as error:r['cleanup_error']=str(error);r['passed']=False
 r['health_after']=read_health();(out/'controller.py').write_bytes(pathlib.Path(__file__).read_bytes());(out/'report.json').write_text(json.dumps(r,indent=2)+'\n');print('Results:',out.relative_to(ROOT));print('PASS' if r['passed'] else r.get('error',r.get('cleanup_error')))
raise SystemExit(0 if r['passed'] else 1)
