#!/usr/bin/env python3
"""Actual non-debug release on the leased handset through its public UI only."""
import argparse,datetime,hashlib,json,pathlib,re,subprocess,time,xml.etree.ElementTree as ET
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready,read_health
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=pathlib.Path,required=True);p.add_argument('--model',choices=['ornith','gemma'],default='ornith');a=p.parse_args()
if not a.apk.resolve().is_relative_to(ROOT):p.error('Workspace release APK required')
lease=acquire_phone_lease();health=require_phone_ready()
model=next(m for m in json.loads((ROOT/'bench/models.lock.json').read_text())['models'] if m['id']==a.model)
out=ROOT/'bench/results'/('release-ui-phone-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
original_notifications=None
r=dict(passed=False,health_before=health,apk_sha256=hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest(),model=a.model,scope='Actual non-debug Linux release on physical Motorola; unchanged original GGUF, real profile selection and composer, screen-off completed reply and actual notification tap. Separate native-identical instrumentation supplies numerical/context/contract gates. No publication, private screen capture or speed promotion.')
def adb(*args,timeout=60):return subprocess.run(['adb','-d',*args],check=True,capture_output=True,text=True,timeout=timeout).stdout
remote='/sdcard/motoai-release-ui-'+str(time.time_ns())+'.xml'
def tree():
 policy=adb('shell','dumpsys','window','policy');showing=re.search(r'^\s*showing=(true|false)\s*$',policy,re.M)
 if not showing or showing[1]!='false':raise RuntimeError('Owner unlock required before reading only MotoAI UI; no keyguard UI captured')
 state=adb('shell','dumpsys','activity','activities')
 resumed=[v for v in state.splitlines() if re.search(r'\b(?:mResumedActivity|topResumedActivity)\b',v)]
 if not resumed or not all('org.motoai.runtime/' in v for v in resumed):raise RuntimeError('MotoAI is not the resumed app; no private screen captured or keyguard bypassed')
 adb('shell','uiautomator','dump',remote);return ET.fromstring(adb('exec-out','cat',remote))
def nodes(label):return [n for n in tree().iter('node') if label.casefold() in (n.get('text','')+' '+n.get('content-desc','')).casefold()]
def tapnode(n):
 x,y,xx,yy=map(int,re.findall(r'\d+',n.get('bounds')));adb('shell','input','tap',str((x+xx)//2),str((y+yy)//2))
def tap(label):
 if label=='Модели':tap('Настройки');label='Модели на телефоне'
 matches=nodes(label);exact=[n for n in matches if n.get('text','').casefold()==label.casefold()]
 if not matches:raise RuntimeError('Missing public MotoAI action: '+label)
 tapnode((exact or matches)[0]);time.sleep(.3)
def until(condition,seconds=120):
 end=time.monotonic()+seconds
 while not condition():
  if time.monotonic()>end:raise RuntimeError('MotoAI UI deadline')
  time.sleep(.5)
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
 from phone_model import model_sha256
 if model_sha256(model['filename'])!=model['sha256']:raise RuntimeError('Original model SHA differs')
 adb('shell','am','force-stop','org.motoai.runtime');adb('install','-r',str(a.apk),timeout=300)
 path=adb('shell','pm','path','org.motoai.runtime').strip().removeprefix('package:')
 if adb('shell','sha256sum',path).split()[0]!=r['apk_sha256']:raise RuntimeError('Installed release differs')
 package=adb('shell','dumpsys','package','org.motoai.runtime')
 if any('DEBUGGABLE' in v.split() for v in re.findall(r'flags=\[([^\]]*)\]',package)):raise RuntimeError('Release debuggable')
 permissions=adb('shell','dumpsys','package','org.motoai.runtime');match=re.search(r'android\.permission\.POST_NOTIFICATIONS: granted=(true|false)',permissions)
 if not match:raise RuntimeError('Unknown original notification permission')
 original_notifications=match[1]=='true';r['notifications_granted_before']=original_notifications
 adb('shell','pm','grant','org.motoai.runtime','android.permission.POST_NOTIFICATIONS')
 adb('shell','input','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard');await_owner_unlock();adb('shell','am','start','-n','org.motoai.runtime/.ChatActivity');until(lambda:bool(nodes('Новый диалог')))
 tap('Новый диалог');tap('Модель нового диалога');tap(model['filename'].removesuffix('.gguf'));tap('Создать')
 bar=next(n for n in tree().iter('node') if n.get('class')=='android.widget.SeekBar');x,y,xx,yy=map(int,re.findall(r'\d+',bar.get('bounds')))
 adb('shell','input','tap',str(x+round((xx-x)*.25)),str((y+yy)//2))
 if not nodes('Контекст: 4096'):raise RuntimeError('Failed to select conservative 4K context')
 for n in list(tree().iter('node')):
  if n.get('class')=='android.widget.CheckBox' and n.get('checked')=='true':tapnode(n)
 edit=next(n for n in tree().iter('node') if n.get('class')=='android.widget.EditText');tapnode(edit)
 adb('shell','input','keyevent','KEYCODE_MOVE_END',*(['KEYCODE_DEL']*8));adb('shell','input','text','2048');adb('shell','input','keyevent','KEYCODE_BACK')
 tap('Загрузить')
 edit=next(n for n in tree().iter('node') if n.get('class')=='android.widget.EditText');tapnode(edit)
 adb('shell','input','text','What%sis%s2%splus%s2?%sReply%sbriefly.');until(lambda:any(n.get('content-desc')=='Отправить' and n.get('enabled')=='true' for n in tree().iter('node')),180);tap('Отправить')
 adb('shell','input','keyevent','KEYCODE_HOME');adb('shell','input','keyevent','KEYCODE_SLEEP')
 deadline=time.monotonic()+900;notice=None
 while time.monotonic()<deadline:
  # Keep only structural records belonging to our package; no other notification text is retained.
  dump=adb('shell','dumpsys','notification')
  records=[line for line in dump.splitlines() if 'NotificationRecord(' in line and 'pkg=org.motoai.runtime ' in line and re.search(r'\bid=2\b',line)]
  if records:notice=records[0];break
  time.sleep(3)
 if notice is None:raise RuntimeError('No real main-model completion notification during bounded screen-off wait')
 wake=re.search(r'mWakefulness=(\w+)',adb('shell','dumpsys','power'))
 if not wake or wake[1] not in ['Asleep','Dozing']:raise RuntimeError('Main model did not demonstrably complete with screen off')
 r['completion_wakefulness']=wake[1]
 r['main_model_screen_off_completed']=True
 adb('shell','input','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard')
 await_owner_unlock()
 time.sleep(.5);adb('shell','cmd','statusbar','expand-notifications');time.sleep(1)
 tapped=False
 for attempt in range(10):
  adb('shell','uiautomator','dump',remote);shade=ET.fromstring(adb('exec-out','cat',remote));parents={child:parent for parent in shade.iter() for child in parent}
  for title in shade.iter('node'):
   if title.get('text')!='Ответ готов':continue
   current=title
   for depth in range(16):
    if current.get('clickable')=='true' and any(re.search(r'\bMotoAI\b',n.get('text','')) for n in current.iter('node')):
     tapnode(current);tapped=True;break
    if current not in parents:break
    current=parents[current]
   if tapped:break
  if tapped:break
  time.sleep(.5)
 if not tapped:raise RuntimeError('Could not tap the exact MotoAI completion notification; no security setting changed')
 time.sleep(1)
 until(lambda:bool(nodes('Ответ завершён')) or bool(nodes('Достигнут выбранный лимит ответа')),30)
 r['actual_notification_tap_opens_answer']=True
 r['completed_status']='eog' if nodes('Ответ завершён') else 'length'
 if r['completed_status']!='eog':raise RuntimeError('Main-model answer did not reach EOS within chosen reserve')
 completed_tree=tree();parents={child:parent for parent in completed_tree.iter() for child in parent}
 footers=[n for n in completed_tree.iter('node') if n.get('text')=='Ответ завершён']
 if not any(any(re.search(r'(?<!\d)4(?!\d)',node.get('text','')) for node in parents[footer].iter('node') if node.get('class')=='android.widget.TextView') for footer in footers if footer in parents):raise RuntimeError('Expected public math answer absent from completed assistant card')
 r['visible_answer_has_expected_number']=True
 ET.ElementTree(completed_tree).write(out/'public-completed.xml')
 r['passed']=True;r['non_debug_release']=True;r['native_reply_via_actual_composer']=True;r['profile']={'context':4096,'max_tokens':2048,'transient_repack':False,'prefix_cache_mib':0}
 tap('Назад к диалогам');tap('Модели');tap(model['filename'].removesuffix('.gguf'));tap('Выгрузить')
except Exception as error:r['error']=str(error)
finally:
 # Our public test chat is RAM-only; stopping the app clears it and the engine/API/wake lease.
 try:
  adb('shell','input','keyevent','KEYCODE_WAKEUP')
  if original_notifications is not None:adb('shell','pm','grant' if original_notifications else 'revoke','org.motoai.runtime','android.permission.POST_NOTIFICATIONS')
  adb('shell','cmd','statusbar','collapse');adb('shell','am','force-stop','org.motoai.runtime');adb('shell','rm','-f',remote);adb('shell','am','start','-n','org.motoai.runtime/.ChatActivity')
 except Exception as error:r['cleanup_error']=str(error);r['passed']=False
 r['health_after']=read_health();(out/'controller.py').write_bytes(pathlib.Path(__file__).read_bytes());(out/'report.json').write_text(json.dumps(r,indent=2)+'\n');print('Results:',out.relative_to(ROOT));print('PASS' if r['passed'] else r.get('error',r.get('cleanup_error')))
raise SystemExit(0 if r['passed'] else 1)
