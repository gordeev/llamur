#!/usr/bin/env python3
"""Own one phone lease; real Aider over explicit Wi-Fi or developer USB TLS, then networkless generated-code checks."""
import argparse,datetime,hashlib,json,os,pathlib,re,shutil,subprocess,time,uuid,socket,socketserver,selectors,threading,ssl,urllib.request
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready,read_health
ROOT=pathlib.Path(__file__).resolve().parents[1];p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--transport',choices=['wifi','adb-tls'],default='wifi',help='Explicit developer USB binary relay is separate from direct Wi-Fi acceptance');p.add_argument('--reuse-installation',action='store_true');p.add_argument('--apk',type=pathlib.Path,required=True);p.add_argument('--tests',type=pathlib.Path,required=True);p.add_argument('--model',choices=['ornith','gemma'],default='ornith');p.add_argument('--transient',action='store_true');a=p.parse_args()
lease=acquire_phone_lease();health=require_phone_ready();model=next(m for m in json.loads((ROOT/'bench/models.lock.json').read_text())['models'] if m['id']==a.model)
id='api-'+uuid.uuid4().hex;out=ROOT/'bench/results'/('aider-phone-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
work=out/'repo';work.mkdir();shutil.copy(ROOT/'bench/fixtures/aider/math_tools.py',work);shutil.copy(ROOT/'bench/fixtures/aider/check_result.py',work)
report=dict(passed=False,apk_sha256=hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest(),model=a.model,model_sha256=model['sha256'],health_before=health,transport=a.transport,scope='Actual pinned Aider two-turn local TLS editing via explicit transport, original GGUF; no publication/commits/cloud model')
process=None;sessionlog=None;relay=None;relayThread=None
relay_diagnostics=[]
private=ROOT/'build'/id;private.mkdir(mode=0o700)
def adb(*args,timeout=60,check=True):return subprocess.run(['adb','-d',*args],capture_output=True,text=True,timeout=timeout,check=check)
try:
 from phone_model import model_sha256
 if model_sha256(model['filename'])!=model['sha256']:raise RuntimeError('Original phone GGUF SHA differs')
 adb('shell','am','force-stop','org.motoai.runtime')
 if not a.reuse_installation:
  adb('install','-r',str(a.apk),timeout=300);adb('install','-t','-r',str(a.tests),timeout=300)
 for package,source in [('org.motoai.runtime',a.apk),('org.motoai.runtime.test',a.tests)]:
  installed_path=adb('shell','pm','path',package).stdout.strip().removeprefix('package:')
  if adb('shell','sha256sum',installed_path).stdout.split()[0]!=hashlib.file_digest(source.open('rb'),'sha256').hexdigest():raise RuntimeError('Installed app/test APK differs')
 sessionlog=(out/'instrumentation.txt').open('w')
 process=subprocess.Popen(['adb','-d','shell','am','instrument','-w','-r','-e','scope','api-session','-e','runId',id,'-e','model',model['filename'],'-e','transient',str(a.transient).lower(),'-e','cache','true','org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation'],stdout=sessionlog,stderr=subprocess.STDOUT)
 end=time.monotonic()+180;server=None
 while time.monotonic()<end:
  if process.poll() is not None:raise RuntimeError('API setup exited; inspect instrumentation')
  raw=adb('exec-out','run-as','org.motoai.runtime','cat','files/'+id+'-ready.json',check=False)
  if raw.returncode==0:
   try:server=json.loads(raw.stdout);break
   except json.JSONDecodeError:pass
  time.sleep(.5)
 if not server or not server.get('lan') or not server['url'].startswith('https://'):raise RuntimeError('No verified LAN/TLS API setup')
 cert=private/'phone-api.pem';cert.write_text(server['certificate']);cert.chmod(0o600)
 from urllib.parse import urlparse
 target=urlparse(server['url']);ip=target.hostname;port=target.port
 if not re.fullmatch(r'[0-9.]+',ip) or port!=18080:raise RuntimeError('Unexpected test endpoint')
 # Both paths keep end-to-end TLS to the Android API; the relay never sees plaintext.
 # No automatic fallback between transports, no global proxy/VPN/router changes.
 report['server_url']=server['url'];report['tls_fingerprint']=server['fingerprint']
 trust=ssl.create_default_context(cafile=str(cert))
 class Relay(socketserver.BaseRequestHandler):
  def handle(self):
   self.request.settimeout(None)
   if a.transport=='wifi':
    with socket.create_connection((ip,port),timeout=20) as remote:
     remote.settimeout(None)
     with selectors.DefaultSelector() as selector:
      selector.register(self.request,selectors.EVENT_READ,remote);selector.register(remote,selectors.EVENT_READ,self.request)
      while True:
       for key,_ in selector.select(timeout=10):
        try:
         data=key.fileobj.recv(65536)
         if not data:return
         key.data.sendall(data)
        except OSError:return
   else:
    # -T disables PTY transformations; shell-v2 provides binary duplex stdin/out.
    # The standard phone tool connects only to this phone's selected Wi-Fi API IP.
    remote=subprocess.Popen(['adb','-d','shell','-T','toybox','nc','-n','-w','20',ip,str(port)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=0)
    traffic={'client_tls_bytes':0,'phone_tls_bytes':0}
    try:
     with selectors.DefaultSelector() as selector:
      selector.register(self.request,selectors.EVENT_READ,'client');selector.register(remote.stdout,selectors.EVENT_READ,'phone')
      while True:
       for key,_ in selector.select(timeout=10):
        try:
         if key.data=='client':
          data=self.request.recv(65536)
          if not data:return
          traffic['client_tls_bytes']+=len(data)
          pending=memoryview(data)
          while pending:
           n=remote.stdin.write(pending)
           if not n:return
           pending=pending[n:]
         else:
          data=remote.stdout.read(65536)
          if not data:return
          traffic['phone_tls_bytes']+=len(data)
          self.request.sendall(data)
        except (OSError,ValueError):return
    finally:
     remote.stdin.close();remote.stdout.close()
     if remote.poll() is None:
      remote.terminate()
      try:remote.wait(timeout=5)
      except subprocess.TimeoutExpired:remote.kill();remote.wait(timeout=5)
     # Only the fixed nc process' own error and TLS byte counts, never traffic or credentials.
     error=remote.stderr.read(2048).decode('utf-8',errors='replace');remote.stderr.close()
     relay_diagnostics.append(dict(traffic,returncode=remote.returncode,error=error))
 class LocalRelay(socketserver.ThreadingTCPServer):daemon_threads=True;allow_reuse_address=False
 relay=LocalRelay(('127.0.0.1',0),Relay);relayPort=relay.server_address[1]
 relayThread=threading.Thread(target=relay.serve_forever,daemon=True);relayThread.start()
 discovery=server['url'] if a.transport=='wifi' else 'https://127.0.0.1:'+str(relayPort)+'/v1'
 request=urllib.request.Request(discovery+'/models',headers={'Authorization':'Bearer '+server['token']})
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=trust))
 with opener.open(request,timeout=20) as response:
  if response.status!=200:raise RuntimeError('Explicit transport TLS model discovery failed')
 report['tls_discovery_passed']=True
 cache=private/'tiktoken-cache';shutil.copytree(ROOT/'.tools/tiktoken-cache',cache)
 profile=private/'client.sb'
 profile.write_text('(version 1)\n(allow default)\n(deny file-write*)\n(allow file-write* (subpath '+json.dumps(str(out))+') (subpath '+json.dumps(str(private))+') (literal "/dev/null"))\n(deny network-outbound)\n(allow network-outbound (remote tcp "localhost:'+str(relayPort)+'"))\n')
 env={k:v for k,v in os.environ.items() if k in ['PATH','LANG','LC_ALL','TMPDIR','SYSTEMROOT']}
 env.update(OPENAI_API_BASE='https://127.0.0.1:'+str(relayPort)+'/v1',OPENAI_API_KEY=server['token'],SSL_CERT_FILE=str(cert),REQUESTS_CA_BUNDLE=str(cert),MOTOAI_API_SESSION=uuid.uuid4().hex,LITELLM_LOCAL_MODEL_COST_MAP='True',DO_NOT_TRACK='1',NO_COLOR='1',PYTHONDONTWRITEBYTECODE='1',PYTHONNOUSERSITE='1',TIKTOKEN_CACHE_DIR=str(cache))
 cmd=['/usr/bin/sandbox-exec','-f',str(profile),str(ROOT/'.tools/aider-env/bin/python'),str(ROOT/'scripts/aider-fixture-client.py'),'--model',model['filename'],'--output',str(out)]
 with (out/'aider.txt').open('w') as log:r=subprocess.run(cmd,cwd=work,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=3600)
 if r.returncode:raise RuntimeError('Aider client failed; inspect retained public-fixture client log')
 report['client']=json.loads((out/'client-report.json').read_text())
 check=subprocess.run(['docker','--context','desktop-linux','run','--rm','--network','none','--read-only','--memory','256m','--pids-limit','64','--cpus','1','--tmpfs','/tmp:rw,nosuid,size=16m','-v',str(work)+':/fixture:ro','-w','/fixture','motoai-linux-builder:20261026','python3','-I','-c','import sys;sys.path.insert(0,"/fixture");exec(open("/fixture/check_result.py").read())'],capture_output=True,text=True,timeout=60)
 (out/'code-check.txt').write_text(check.stdout+check.stderr)
 if check.returncode:raise RuntimeError('Aider-generated code failed bounded networkless regression cases')
 report['passed']=report['client']['passed'] is True;report['tls_verification']=True;report['network_scope']=('client sandbox only localhost opaque TLS relay, fixed to Wi-Fi phone:18080; direct Wi-Fi IP hostname/certificate verified separately' if a.transport=='wifi' else 'Explicit developer USB adb shell -T/nc binary relay, fixed to selected phone Wi-Fi API:18080. End-to-end TLS with trusted exported CA and127.0.0.1 SAN; no direct LAN reachability claim or disabled certificate verification');report['code_checks_passed']=True
except Exception as error:report['error']=str(error)
finally:
 adb('shell','run-as','org.motoai.runtime','touch','files/'+id+'.stop',check=False)
 if process:
  try:process.wait(timeout=30)
  except subprocess.TimeoutExpired:process.terminate();adb('shell','am','force-stop','org.motoai.runtime',check=False)
 if sessionlog:sessionlog.close()
 raw=adb('exec-out','run-as','org.motoai.runtime','cat','files/'+id+'-report.json',check=False)
 if raw.returncode==0:
  (out/'session-report.json').write_text(raw.stdout)
  if not json.loads(raw.stdout).get('passed'):report['passed']=False
 adb('shell','run-as','org.motoai.runtime','rm','-f','files/'+id+'-ready.json','files/'+id+'.stop',check=False)
 if relay:relay.shutdown();relay.server_close()
 if relay_diagnostics:report['relay_diagnostics']=relay_diagnostics
 shutil.rmtree(private);report['health_after']=read_health()
 for name in ['scripts/run-aider-phone-check.py','scripts/aider-fixture-client.py']:(out/pathlib.Path(name).name).write_bytes((ROOT/name).read_bytes())
 (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print('Results:',out.relative_to(ROOT),flush=True);print('PASS' if report['passed'] else report.get('error','FAIL'),flush=True)
raise SystemExit(not report['passed'])
