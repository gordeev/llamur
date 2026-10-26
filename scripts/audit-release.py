#!/usr/bin/env python3
"""Audit one actual CPU APK: assets/notices, ABI, ELF16KiB and ZIP alignment."""
import argparse,hashlib,json,pathlib,struct,subprocess,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
r=dict(passed=False,scope='Actual APK packaging/ELF/notices/manifest audit. Does not certify app correctness, model quality, cryptographic provenance or F-Droid inclusion.',apk_sha256=hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest())
try:
 with zipfile.ZipFile(a.apk) as z:
  names=z.namelist();r['entries']=names
  if len(names)!=len(set(names)):raise RuntimeError('Duplicate ZIP entries')
  if any(n.lower().endswith(('.gguf','.keystore','.jks','.pem')) for n in names):raise RuntimeError('Unexpected model/key/certificate asset')
  if any(word in n.lower() for n in names for word in ['libqnn','hexagon','libopencl.so','libvulkan.so']):raise RuntimeError('Unexpected proprietary/system backend packaging')
  notices={}
  for source in sorted((ROOT/'app/src/main/assets/licenses').glob('*')):
   if source.is_file():
    name='assets/licenses/'+source.name
    if z.read(name)!=source.read_bytes():raise RuntimeError('Notice differs: '+name)
    notices[name]=hashlib.sha256(z.read(name)).hexdigest()
  if not notices:raise RuntimeError('Missing notices')
  r['notices_sha256']=notices;r['native_libraries']={}
  for name in names:
   if not name.endswith('.so'):continue
   if not name.startswith('lib/arm64-v8a/'):raise RuntimeError('Unexpected native ABI')
   b=z.read(name)
   if b[:6]!=b'\x7fELF\x02\x01' or struct.unpack_from('<H',b,18)[0]!=183:raise RuntimeError('Not little-endian ELF64 AArch64')
   offset=struct.unpack_from('<Q',b,32)[0];size,count=struct.unpack_from('<HH',b,54);aligns=[]
   for i in range(count):
    t,flags,off,va,pa,filesz,memsz,alignment=struct.unpack_from('<IIQQQQQQ',b,offset+i*size)
    if t==1:
     if alignment<16384 or alignment&(alignment-1) or off%16384!=va%16384:raise RuntimeError('ELF16KiB alignment failed: '+name)
     aligns.append(alignment)
   if not aligns:raise RuntimeError('Missing load segments')
   r['native_libraries'][name]=dict(sha256=hashlib.sha256(b).hexdigest(),load_alignments=aligns,zip_compression=z.getinfo(name).compress_type)
  if not r['native_libraries']:raise RuntimeError('No native CPU runtime')
 tools=ROOT/'.tools/android-sdk/build-tools/36.1.0'
 for label,command in [('zipalign',[str(tools/'zipalign'),'-c','-P','16','-v','4',str(a.apk)]),('badging',[str(tools/'aapt2'),'dump','badging',str(a.apk)])]:
  result=subprocess.run(command,capture_output=True,text=True,timeout=60);(a.out/(label+'.txt')).write_text(result.stdout+result.stderr)
  if result.returncode:raise RuntimeError(label+' failed')
  if label=='badging':
   if "package: name='org.motoai.runtime'" not in result.stdout or "native-code: 'arm64-v8a'" not in result.stdout:raise RuntimeError('Package/ABI differs')
   if "launchable-activity: name='org.motoai.runtime.ChatActivity'" not in result.stdout:raise RuntimeError('Chat launcher missing')
   if "application-debuggable" in result.stdout:raise RuntimeError('Release is debuggable')
   r['permissions']=[line.split("'")[1] for line in result.stdout.splitlines() if line.startswith('uses-permission:')]
   expected={'android.permission.FOREGROUND_SERVICE','android.permission.FOREGROUND_SERVICE_SPECIAL_USE','android.permission.POST_NOTIFICATIONS','android.permission.WAKE_LOCK','android.permission.INTERNET','android.permission.ACCESS_NETWORK_STATE','android.permission.FOREGROUND_SERVICE_DATA_SYNC'}
   if set(r['permissions'])!=expected:raise RuntimeError('Unexpected manifest permissions')
 r['passed']=True
except Exception as error:r['error']=str(error)
finally:
 (a.out/'controller.py').write_bytes(pathlib.Path(__file__).read_bytes());(a.out/'report.json').write_text(json.dumps(r,indent=2)+'\n');print('PASS' if r['passed'] else r['error'])
raise SystemExit(0 if r['passed'] else 1)
