#!/usr/bin/env python3
"""Freeze public request bytes and tokenizer/template traces from pinned GGUF vocabularies."""
import argparse
import hashlib
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('model', choices=['gemma', 'ornith'])
p.add_argument('--host-runtime',type=pathlib.Path,default=ROOT/'build/core-host/runtime-run')
p.add_argument('--synthetic', action='store_true')
p.add_argument('--only', nargs='+', help='Prepare only these fixture ids; retain other frozen files')
args = p.parse_args()
model = next(m for m in json.loads((ROOT / 'bench/models.lock.json').read_text())['models'] if m['id'] == args.model)
local_model = ROOT / 'models' / model['filename']
if local_model.stat().st_size != model['bytes'] or hashlib.file_digest(local_model.open('rb'), 'sha256').hexdigest() != model['sha256']:
    raise RuntimeError('Local model does not match the lock')
catalog = json.loads((ROOT / 'bench/fixtures/requests.json').read_text())
directory = ROOT / 'bench/fixtures/generated' / args.model
directory.mkdir(parents=True, exist_ok=True)
entries = [f for f in catalog['fixtures'] if args.model in f.get('models', ['gemma', 'ornith'])]
if args.synthetic:
    entries += [dict(id=f'pp{n}', kind='synthetic_prefill', target_prompt_tokens=n,
                     messages=[dict(role='system', content='You are a helpful assistant.'), dict(role='user', content='')])
                for n in [128, 512, 2048, 4096, 8064]]
if args.synthetic:
    entries.append(dict(id='needle-7680', kind='context_quality', target_prompt_tokens=7680, max_tokens=512,
                        synthetic_prefix='Remember this identifier for later: motoai_x7r4_n9q2.\nUnrelated filler follows:\n',
                        synthetic_suffix='\nWhat is the identifier from the beginning? Return that exact identifier.',
                        expected_identifier='motoai_x7r4_n9q2',
                        messages=[dict(role='system', content='You are a helpful assistant.'), dict(role='user', content='')]))
if args.synthetic:
    entries += [dict(id=f'pp{n}-diag', kind='synthetic_prefill', target_prompt_tokens=n,
                     max_tokens=1, diagnostics=True, check_logits=True,
                     messages=[dict(role='system', content='You are a helpful assistant.'), dict(role='user', content='')])
                for n in [128, 512, 2048, 4096, 8064]]
if args.synthetic:
    entries.append(dict(id='pp8064-full-diag', kind='synthetic_prefill', target_prompt_tokens=8064,
                        max_tokens=128, diagnostics=True, check_logits=True,
                        messages=[dict(role='system', content='You are a helpful assistant.'), dict(role='user', content='')]))
if args.synthetic:
    entries.append(dict(id='needle-7680-diag', kind='context_quality', target_prompt_tokens=7680, max_tokens=512,
                        diagnostics=True, check_logits=True,
                        synthetic_prefix='Remember this identifier for later: motoai_x7r4_n9q2.\nUnrelated filler follows:\n',
                        synthetic_suffix='\nWhat is the identifier from the beginning? Return that exact identifier.',
                        expected_identifier='motoai_x7r4_n9q2',
                        messages=[dict(role='system', content='You are a helpful assistant.'), dict(role='user', content='')]))
index_path = directory / 'index.json'
index = json.loads(index_path.read_text()) if args.only and index_path.exists() else []
if args.only:
    entries = [f for f in entries if f['id'] in args.only]
    if {f['id'] for f in entries} != set(args.only): p.error('Unknown fixture id')
for fixture in entries:
    request = dict(catalog['defaults'], messages=fixture['messages'])
    request['max_tokens'] = fixture.get('max_tokens', 128)
    for key in ['diagnostics', 'check_logits', 'chat', 'raw_prompt']:
        if key in fixture: request[key] = fixture[key]
    spec = dict(config=dict(catalog['config'], **fixture.get('config', {})), request=request)
    if 'target_prompt_tokens' in fixture: spec['target_prompt_tokens'] = fixture['target_prompt_tokens']
    for key in ['synthetic_prefix', 'synthetic_middle', 'synthetic_suffix', 'teacher_forcing_text']:
        if key in fixture: spec[key] = fixture[key]
    path = directory / (fixture['id'] + '.json')
    path.write_text(json.dumps(spec, indent=2, ensure_ascii=False) + '\n')
    result = subprocess.run([str(args.host_runtime.resolve()), str(ROOT / 'models' / model['filename']), str(path), '--inspect'],
                            capture_output=True, text=True, timeout=90)
    (ROOT / '.tools' / f'fixture-{args.model}-{fixture["id"]}.stderr.txt').write_text(result.stderr)
    response = json.loads(result.stdout)
    if fixture['kind'] == 'expected_rejection':
        if response.get('ok') or response.get('kind') != fixture['expected_kind'] or fixture['expected_message'] not in response.get('message', ''):
            raise RuntimeError(f'Expected rejection did not match: {fixture["id"]}: {response}')
    elif result.returncode != 0 or not response.get('ok'):
        raise RuntimeError(f'Inspection failed: {fixture["id"]}: {response}')
    if 'normalized_spec' in response:
        spec = response.pop('normalized_spec')
        path.write_text(json.dumps(spec, indent=2, ensure_ascii=False) + '\n')
    golden = {'model_sha256': model['sha256'], 'upstream_commit': json.loads((ROOT / 'upstream.lock.json').read_text())['commit'],
              'request_sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
              'kind': fixture['kind'], 'expected_identifier': fixture.get('expected_identifier'), 'expected': response.get('trace', response)}
    if 'expected_identifiers' in fixture: golden['expected_identifiers'] = fixture['expected_identifiers']
    if 'required_output_tokens' in fixture:
        golden['required_output_tokens'] = fixture['required_output_tokens']
    (directory / (fixture['id'] + '.golden.json')).write_text(json.dumps(golden, indent=2, ensure_ascii=False) + '\n')
    index = [entry for entry in index if entry['id'] != fixture['id']]
    index.append(dict(id=fixture['id'], kind=fixture['kind'], request=str(path.relative_to(ROOT)),
                      sha256=golden['request_sha256'], prompt_tokens=len(response.get('trace', {}).get('prompt_ids', []))))
    print(f'{args.model}/{fixture["id"]}: {index[-1]["prompt_tokens"]} prompt tokens; {"rejected as expected" if fixture["kind"] == "expected_rejection" else "OK"}', flush=True)
(directory / 'index.json').write_text(json.dumps(index, indent=2) + '\n')
