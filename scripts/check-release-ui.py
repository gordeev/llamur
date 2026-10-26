#!/usr/bin/env python3
"""Actual non-debug release UI load/send on the public16KiB emulator; no run-as."""
import argparse,datetime,hashlib,json,pathlib,re,subprocess,time,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1];p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=pathlib.Path,required=True);a=p.parse_args()
if not a.apk.resolve().is_relative_to(ROOT):p.error('Workspace APK required')
out=ROOT/'bench/results'/('release-ui-emulator-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir();r=dict(passed=False,apk_sha256=hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest(),scope='Actual non-debug Linux release UI, real16KiB ARM64 emulator, pre-existing public test-only tiny GGUF,2K context/16-token reserve. Actual API base URL button copy+paste. No adb run-as, phone/main-model quality or speed claim.')
def adb(*args,raw=False):
 q=subprocess.run(['adb','-s','emulator-5580',*args],check=True,capture_output=True,timeout=60);return q.stdout if raw else q.stdout.decode()
def tree():adb('shell','uiautomator','dump','/sdcard/motoai-public-release.xml');return ET.fromstring(adb('exec-out','cat','/sdcard/motoai-public-release.xml'))
def nodes(label):return [n for n in tree().iter('node') if label.casefold() in (n.get('text','')+' '+n.get('content-desc','')).casefold()]
def tapnode(n):
 x,y,xx,yy=map(int,re.findall(r'\d+',n.get('bounds')));adb('shell','input','tap',str((x+xx)//2),str((y+yy)//2))
def tap(label):
 if label=='Модели':tap('Настройки');label='Модели на телефоне'
 candidates=nodes(label);exact=[n for n in candidates if n.get('text','').casefold()==label.casefold()];tapnode((exact or candidates)[0]);time.sleep(.3)
def until(condition):
 end=time.monotonic()+60
 while not condition():
  if time.monotonic()>end:raise RuntimeError('UI deadline')
  time.sleep(.2)
try:
 if adb('shell','getprop','ro.boot.qemu').strip()!='1' or adb('shell','getconf','PAGE_SIZE').strip()!='16384':raise RuntimeError('Wrong emulator')
 adb('shell','am','force-stop','org.motoai.runtime');adb('install','-r',str(a.apk))
 path=adb('shell','pm','path','org.motoai.runtime').strip().removeprefix('package:')
 if adb('shell','sha256sum',path).split()[0]!=r['apk_sha256']:raise RuntimeError('Installed APK differs')
 package=adb('shell','dumpsys','package','org.motoai.runtime');(out/'package.txt').write_text(package)
 flags=re.findall(r'flags=\[([^\]]*)\]',package)
 if any('DEBUGGABLE' in v.split() for v in flags):raise RuntimeError('Release is debuggable')
 adb('shell','am','start','-n','org.motoai.runtime/.ChatActivity');until(lambda:bool(nodes('Новый диалог')))
 tap('Новый диалог');tap('Модель нового диалога');tap('stories260K');tap('Создать')
 root=tree();bar=next(n for n in root.iter('node') if n.get('class')=='android.widget.SeekBar');x,y,xx,yy=map(int,re.findall(r'\d+',bar.get('bounds')));adb('shell','input','tap',str(x+4),str((y+yy)//2))
 edit=next(n for n in tree().iter('node') if n.get('class')=='android.widget.EditText');tapnode(edit)
 adb('shell','input','keyevent','KEYCODE_MOVE_END','KEYCODE_DEL','KEYCODE_DEL','KEYCODE_DEL','KEYCODE_DEL','KEYCODE_DEL');adb('shell','input','text','16');adb('shell','input','keyevent','KEYCODE_BACK')
 tap('Загрузить')
 edit=next(n for n in tree().iter('node') if n.get('class')=='android.widget.EditText');tapnode(edit);adb('shell','input','text','Once_upon_a_time');until(lambda:any(n.get('content-desc')=='Отправить' and n.get('enabled')=='true' for n in tree().iter('node')));(out/'keyboard.png').write_bytes(adb('exec-out','screencap','-p',raw=True));tap('Отправить')
 until(lambda:bool(nodes(' · лимит')))
 (out/'completed.png').write_bytes(adb('exec-out','screencap','-p',raw=True));ET.ElementTree(tree()).write(out/'completed.xml')
 tap('Назад к диалогам');tap('Локальный API');tap('Запустить API');until(lambda:bool(nodes('Копировать base URL')))
 tap('Копировать base URL');tap('Диалоги');tap('Once_upon_a_time')
 edit=next(n for n in tree().iter('node') if n.get('class')=='android.widget.EditText');tapnode(edit)
 adb('shell','input','keyevent','KEYCODE_PASTE')
 until(lambda:any(n.get('text')=='http://127.0.0.1:8080/v1' for n in tree().iter('node') if n.get('class')=='android.widget.EditText'))
 r['base_url_copy_and_actual_paste']=True
 adb('shell','input','keyevent','KEYCODE_BACK');tap('Назад к диалогам');tap('Локальный API');tap('Остановить API')
 tap('Модели');tap('Тестовая модель: stories260K');tap('Выгрузить')
 tap('Настройки');tap('Подключения по API');tap('Добавить подключение')
 if not all(nodes(label) for label in ['OpenRouter','OpenAI','Anthropic','Другой']):raise RuntimeError('Provider presets missing from actual release UI')
 tap('OpenRouter');until(lambda:bool(nodes('API key')))
 if not nodes('https://openrouter.ai/api/v1'):raise RuntimeError('Wrong OpenRouter endpoint preset')
 (out/'provider-form.png').write_bytes(adb('exec-out','screencap','-p',raw=True));tap('Отмена');r['provider_presets_and_form']=True
 adb('shell','am','force-stop','org.motoai.runtime')
 r['passed']=True;r['non_debug_release']=True;r['native_reply_via_actual_composer']=True
except Exception as e:
 r['error']=str(e)
 try:
  ET.ElementTree(tree()).write(out/'failure.xml');(out/'failure.png').write_bytes(adb('exec-out','screencap','-p',raw=True))
 except Exception:pass
finally:
 try:adb('shell','am','force-stop','org.motoai.runtime')
 except Exception as error:r['cleanup_error']=str(error);r['passed']=False
 (out/'controller.py').write_bytes(pathlib.Path(__file__).read_bytes());(out/'report.json').write_text(json.dumps(r,indent=2)+'\n');print(out.relative_to(ROOT));print('PASS' if r['passed'] else r['error'])
raise SystemExit(0 if r['passed'] else 1)
