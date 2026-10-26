#!/usr/bin/env python3
"""Vocabulary-only properties plus exact frozen prompt IDs for both locked models."""
import argparse
import datetime
import hashlib
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--build-dir', type=pathlib.Path, default=ROOT/'build/literal-chat-host')
args = parser.parse_args()
build = args.build_dir.resolve()
if not build.is_relative_to(ROOT/'build'): parser.error('Expected workspace host build')
out = ROOT/'bench/results'/('literal-tokenization-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
out.mkdir()
report = dict(passed=False, scope='Vocabulary/Jinja only; no generated-token/quality/speed promotion', models={})
try:
    subprocess.run(['python3','scripts/prepare-cpu-variant.py','f32-recurrent-literal-chat'],cwd=ROOT,check=True,capture_output=True)
    cache=(build/'CMakeCache.txt').read_text()
    if 'MOTOAI_RUNTIME_VARIANT:STRING=f32-recurrent-literal-chat' not in cache: raise RuntimeError('Wrong prepared variant')
    with (out/'build.log').open('w') as log:
        subprocess.run([str(ROOT/'.tools/cmake-3.31.6/bin/cmake'),'--build',str(build),'--target','motoai-tokenizer-tests','--parallel','4'],stdout=log,stderr=subprocess.STDOUT,check=True)
    binary=build/'motoai-tokenizer-tests'
    report['binary_sha256']=hashlib.file_digest(binary.open('rb'),'sha256').hexdigest()
    report['source_sha256']=hashlib.sha256((ROOT/'native-runtime/tests/tokenizer-test.cpp').read_bytes()).hexdigest()
    (out/'tokenizer-test.cpp').write_bytes((ROOT/'native-runtime/tests/tokenizer-test.cpp').read_bytes())
    for model in json.loads((ROOT/'bench/models.lock.json').read_text())['models']:
        name=model['id']; rows=[]
        for path in sorted((ROOT/'bench/fixtures/generated'/name).glob('*.json')):
            if path.name.endswith('.golden.json'): continue
            golden=path.with_suffix('.golden.json')
            if not golden.exists(): continue
            data=json.loads(golden.read_text())
            if hashlib.sha256(path.read_bytes()).hexdigest()!=data['request_sha256']: raise RuntimeError('Fixture changed: '+path.name)
            rows.append(dict(name=path.name,spec=json.loads(path.read_text()),golden=data))
        fixture=out/(name+'-fixtures.json');fixture.write_text(json.dumps(rows,ensure_ascii=False)+'\n')
        local=ROOT/'models'/model['filename']
        actual=hashlib.file_digest(local.open('rb'),'sha256').hexdigest()
        if actual!=model['sha256']: raise RuntimeError('Host model SHA differs')
        completed=subprocess.run([str(binary),str(local),str(fixture)],capture_output=True,text=True,timeout=180)
        (out/(name+'.json')).write_text(completed.stdout)
        (out/(name+'.stderr')).write_text(completed.stderr)
        data=json.loads(completed.stdout);report['models'][name]=dict(result=data,model_sha256=actual,
            fixture_bundle_sha256=hashlib.sha256(fixture.read_bytes()).hexdigest())
        if completed.returncode or not data['passed']: raise RuntimeError('Tokenization failed: '+name)
    report['passed']=True
except Exception as error: report['error']=str(error)
finally:
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Results:',out.relative_to(ROOT),flush=True)
    print('PASS' if report['passed'] else report.get('error','FAIL'))
raise SystemExit(0 if report['passed'] else 1)
