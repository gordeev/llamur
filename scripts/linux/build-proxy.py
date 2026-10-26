#!/usr/bin/env python3
"""Loopback-only public build-dependency relay for a Docker VM without egress."""
import hashlib
import http.server
import pathlib
import select
import shutil
import socket
import subprocess
import urllib.parse

ROOT=pathlib.Path(__file__).resolve().parents[2]
CACHE=ROOT/'.tools/linux-proxy-cache';CACHE.mkdir(parents=True,exist_ok=True)
ALLOWED={'deb.debian.org','security.debian.org','dl.google.com','maven.google.com',
         'repo.maven.apache.org','repo1.maven.org','plugins.gradle.org','plugins-artifacts.gradle.org',
         'services.gradle.org','downloads.gradle.org','github.com','objects.githubusercontent.com',
         'release-assets.githubusercontent.com','maven-central.storage-download.googleapis.com'}


class Proxy(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args): pass # No paths, headers, credentials or request bodies in logs.
    def do_CONNECT(self):
        host,separator,port=self.path.rpartition(':')
        if not separator or host not in ALLOWED or port!='443': self.send_error(403);return
        try:
            with socket.create_connection((host,443),timeout=20) as upstream:
                self.send_response(200,'Connection established');self.end_headers()
                sockets=[self.connection,upstream]
                while True:
                    ready,_,_=select.select(sockets,[],[],60)
                    if not ready:return
                    for source in ready:
                        data=source.recv(65536)
                        if not data:return
                        (upstream if source is self.connection else self.connection).sendall(data)
        except (OSError,ValueError): self.close_connection=True
    def do_GET(self): self.fetch(False)
    def do_HEAD(self): self.fetch(True)
    def fetch(self,head):
        url=urllib.parse.urlsplit(self.path)
        if url.scheme!='http' or url.hostname not in ALLOWED or url.port not in (None,80) or url.username or url.password:
            self.send_error(403);return
        # apt verifies signed repository metadata. The relay additionally fetches
        # plaintext-proxy requests over HTTPS with the host's normal CA checks.
        target=urllib.parse.urlunsplit(('https',url.netloc,url.path,url.query,''))
        key=hashlib.sha256(target.encode()).hexdigest();path=CACHE/key
        if not path.exists():
            temporary=CACHE/(key+'.part')
            completed=subprocess.run(['/usr/bin/curl','--location','--silent','--show-error','--max-time','180',
                '--output',str(temporary),'--write-out','%{http_code}',target],capture_output=True)
            code=int(completed.stdout or b'502') if completed.stdout.isdigit() else 502
            if completed.returncode or code!=200:
                temporary.unlink(missing_ok=True);self.send_error(code if 400<=code<600 else 502);return
            temporary.replace(path)
        self.send_response(200);self.send_header('Content-Length',str(path.stat().st_size));self.end_headers()
        if not head:
            try:
                with path.open('rb') as source:shutil.copyfileobj(source,self.wfile,65536)
            except (BrokenPipeError,ConnectionResetError):pass


server=http.server.ThreadingHTTPServer(('127.0.0.1',18789),Proxy)
print('Scoped public build proxy listening on127.0.0.1:18789',flush=True)
try:server.serve_forever()
except KeyboardInterrupt:pass
finally:server.server_close()
