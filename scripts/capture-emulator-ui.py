#!/usr/bin/env python3
"""Public-fixture visual review of actual emulator UI; restore system font scale."""
import datetime,json,pathlib,re,subprocess,time,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
out=ROOT/'bench/results'/('ui-visual-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
def adb(*args,raw=False):
 r=subprocess.run(['adb','-s','emulator-5580',*args],check=True,capture_output=True,timeout=30)
 return r.stdout if raw else r.stdout.decode('utf-8')
def tree():
 adb('shell','uiautomator','dump','/sdcard/motoai-public-ui.xml');return ET.fromstring(adb('exec-out','cat','/sdcard/motoai-public-ui.xml'))
def tap_node(node):
 v=list(map(int,re.findall(r'\d+',node.get('bounds'))));adb('shell','input','tap',str((v[0]+v[2])//2),str((v[1]+v[3])//2));time.sleep(.3)
def tap(text):tap_node(next(n for n in tree().iter('node') if text.casefold() in n.get('text','').casefold()))
def theme(label):tap('Настройки');tap('Тема:');tap(label);time.sleep(.5)
def composer():return next(n for n in tree().iter('node') if n.get('class')=='android.widget.EditText')
def shot(name):
 (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',raw=True));ET.ElementTree(tree()).write(out/(name+'.xml'))
r=dict(passed=False,scope='Actual public-fixture16KiB emulator UI screenshots for manual review, keyboard and font130 RAM draft. Not physical device/a11y certification or speed.')
font=adb('shell','settings','get','system','font_scale').strip()
try:
 if adb('shell','getprop','ro.boot.qemu').strip()!='1':raise RuntimeError('Not an emulator')
 path=adb('shell','pm','path','org.motoai.runtime').strip().removeprefix('package:');r['apk_sha256']=adb('shell','sha256sum',path).split()[0]
 adb('shell','am','force-stop','org.motoai.runtime');adb('shell','am','start','-n','org.motoai.runtime/.ChatActivity');time.sleep(.5)
 theme('Светлая');shot('light');theme('Тёмная');shot('dark')
 tap_node(composer());adb('shell','input','text','Public_UI_fixture');time.sleep(.5);shot('dark-keyboard')
 adb('shell','settings','put','system','font_scale','1.3');time.sleep(.8)
 if composer().get('text')!='Public_UI_fixture':raise RuntimeError('Draft lost on font-scale Activity recreation')
 tap_node(composer());time.sleep(.5);shot('dark-font130-keyboard');r['font130_draft_preserved']=True;r['passed']=True
except Exception as e:r['error']=str(e)
finally:
 adb('shell','settings','put','system','font_scale',font);adb('shell','am','force-stop','org.motoai.runtime')
 r['original_font_scale']=font;(out/'controller.py').write_bytes(pathlib.Path(__file__).read_bytes());(out/'report.json').write_text(json.dumps(r,indent=2)+'\n');print(out.relative_to(ROOT));print('PASS' if r['passed'] else r['error'])
raise SystemExit(0 if r['passed'] else 1)
