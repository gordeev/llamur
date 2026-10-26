#!/usr/bin/env python3
"""Run installed official F-Droid scanner offline against one workspace APK."""
import argparse,copy,hashlib,json,logging,pathlib
from fdroidserver import common,scanner
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,required=True);a=p.parse_args()
assert all(f.resolve().is_relative_to(ROOT) for f in [a.apk,a.out]);a.out.mkdir(exist_ok=False)
cache=ROOT/'.tools/fdroid-scanner-cache';common.config=copy.deepcopy(common.default_config);common.config.update(sdk_path=str(ROOT/'.tools/android-sdk'),dexdump=str(ROOT/'.tools/android-sdk/build-tools/36.1.0/dexdump'),java_paths={'17':str(ROOT/'.tools/jdk17/Contents/Home')},cachedir_scanner=str(cache),scanner_signature_sources=['suss'],refresh_scanner=False);common.options=argparse.Namespace(verbose=False,quiet=True)
def offline(self):raise RuntimeError('Offline scan requires existing valid cached signatures')
scanner.SignatureDataController.fetch_signatures_from_web=offline;logging.getLogger().setLevel(logging.CRITICAL)
r=dict(passed=False,scope='Official F-Droid2.4.5 APK scan with existing validated cached SUSS, offline. Not source/build/metadata/inclusion certification.',apk_sha256=hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest(),signature_sha256=hashlib.file_digest((cache/'suss.json').open('rb'),'sha256').hexdigest())
try:
 tool=scanner._get_tool();r['error_code_signature_count']=len(tool.regexs['err_code_signatures']);assert r['error_code_signature_count']>0;r['fatal_count']=scanner.scan_binary(str(a.apk));r['passed']=r['fatal_count']==0
except Exception as e:r['error']=str(e)
(a.out/'controller.py').write_bytes(pathlib.Path(__file__).read_bytes());(a.out/'report.json').write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r));raise SystemExit(not r['passed'])
