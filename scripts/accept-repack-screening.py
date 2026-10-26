#!/usr/bin/env python3
"""Freeze bounded M3 quality evidence before same-APK speed screening."""
import argparse
import hashlib
import json
import pathlib
from weight_buffer_checks import check_speed_evidence

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('model', choices=['gemma','ornith'])
for name in ('code-nll','russian-nll','completed-task','filled-context'):
    p.add_argument('--'+name,required=True,type=pathlib.Path)
p.add_argument('--output',required=True,type=pathlib.Path)
args = p.parse_args()
paths = {key:getattr(args,key).resolve() for key in ('code_nll','russian_nll','completed_task','filled_context')}
if not all(path.is_relative_to(ROOT/'bench/results') for path in paths.values()): raise RuntimeError('Evidence must be local immutable raw reports')
reports = {key:json.loads(path.read_text()) for key,path in paths.items()}
apk = hashlib.file_digest((ROOT/'app/build/outputs/apk/debug/app-debug.apk').open('rb'),'sha256').hexdigest()
model = next(m for m in json.loads((ROOT/'bench/models.lock.json').read_text())['models'] if m['id']==args.model)
config = reports['code_nll']['config']
policy = reports['code_nll'].get('candidate_policy','cpu_repack')
check_speed_evidence(reports,apk,model['sha256'],config,policy)
report = {'passed':True,'apk_sha256':apk,'model_sha256':model['sha256'],'config':config,'candidate_policy':policy,
          'scope':'Bounded same-phone loading/weight-policy screening only; not full coding/context/thermal/product acceptance',
          'checks':dict.fromkeys(paths,True),'evidence':{key:{'path':str(path.relative_to(ROOT)),
          'sha256':hashlib.sha256(path.read_bytes()).hexdigest()} for key,path in paths.items()}}
if args.output.exists(): raise RuntimeError('Do not overwrite an existing evidence lock')
args.output.write_text(json.dumps(report,indent=2)+'\n')
print('Quality screening lock:',args.output)
