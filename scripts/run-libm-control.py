#!/usr/bin/env python3
"""Host-only component control; never modifies the phone/APK or scores a task."""
import datetime
import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--comparison', default='bench/results/score-gemma-teacher-forcing-code-probe-v1-20261024T195743Z/comparison.json')
args = p.parse_args()
sources = [ROOT / 'bench/results/math-libm-20261024' / name
           for name in ['phone-10000-256.json', 'phone-1000000-512.json']]
fixture = ROOT / 'bench/fixtures/generated/gemma/teacher-forcing-code-probe-v1.json'
# Resolve the immutable app reference from the full-prefix comparison.
comparison = ROOT / args.comparison
reference = json.loads(comparison.read_text())
if reference['model'] != 'gemma' or reference['fixture'] != 'teacher-forcing-code-probe-v1':
    raise RuntimeError('This component map is only a Gemma5-token code-prefix control')
app = ROOT / reference['app_artifact']
if (app / 'invalidated.json').exists(): raise RuntimeError('Invalidated phone reference')
phone = json.loads((app / 'result.json').read_text())['result']
if len(phone['trace']['prompt_ids']) != 5: raise RuntimeError('Component positions cover only0..4')
manifest = json.loads((app / 'manifest.json').read_text())
spec = json.loads(fixture.read_text())
if spec != manifest['request_spec']: raise RuntimeError('Changed prefix/config')
spec['request']['max_tokens'] = 1
spec['request']['score_continuation_ids'] = spec['request']['score_continuation_ids'][:1]
rows = {}
for path in sources:
    for row in json.loads(path.read_text())['values']:
        key = int(row['theta_bits'], 16)
        value = (int(row['sin_bits'], 16), int(row['cos_bits'], 16))
        if key in rows and rows[key] != value: raise RuntimeError('Conflicting measured trig rows')
        rows[key] = value
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
out = ROOT / 'bench/results' / ('host-libm-control-' + stamp)
out.mkdir(parents=True)
(out / 'trig-table.h').write_text('struct TrigRow { uint32_t angle, sine, cosine; };\nstatic constexpr TrigRow trig_rows[] = {\n' +
    ''.join(f'{{0x{key:08x}U,0x{value[0]:08x}U,0x{value[1]:08x}U}},\n' for key, value in sorted(rows.items())) + '};\n')
(out / 'request.json').write_text(json.dumps(spec, indent=2) + '\n')
library = out / 'trig-control.dylib'
subprocess.run(['clang++', '-std=c++17', '-O2', '-dynamiclib', '-Wall', '-Wextra', '-Werror',
                '-I', str(out), str(ROOT / 'native-runtime/tools/trig-interpose.cpp'), '-o', str(library)], check=True)
runner = ROOT / 'build/reference-host/runtime-run'
model = ROOT / 'models' / manifest['model']['filename']
if hashlib.file_digest(model.open('rb'), 'sha256').hexdigest() != manifest['model']['sha256']:
    raise RuntimeError('Local model differs')
environment = dict(os.environ, DYLD_INSERT_LIBRARIES=str(library))
run = subprocess.run([str(runner), str(model), str(out / 'request.json')], env=environment,
                     capture_output=True, text=True, timeout=300)
(out / 'host.json').write_text(run.stdout)
(out / 'host.stderr.txt').write_text(run.stderr)
report = {'scope': 'Only first scored token after the original5-token Gemma prefix. Measured Android float trig replaces host trig at exact input bits. |angle|<2^-13 uses correctly-rounded binary32 identities sin(x)=x/cos(x)=1; other unmeasured angles abort. No phone/runtime change, no generated quality or speed claim.',
          'passed': False, 'app_artifact': str(app.relative_to(ROOT)), 'comparison': str(comparison.relative_to(ROOT)),
          'apk_sha256': manifest['apk_sha256'], 'unique_measured_angles': len(rows),
          'host_runner_sha256': hashlib.file_digest(runner.open('rb'), 'sha256').hexdigest(),
          'interposer_sha256': hashlib.file_digest(library.open('rb'), 'sha256').hexdigest(),
          'interposer_source_sha256': hashlib.file_digest((ROOT / 'native-runtime/tools/trig-interpose.cpp').open('rb'), 'sha256').hexdigest(),
          'request_sha256': hashlib.file_digest((out / 'request.json').open('rb'), 'sha256').hexdigest(),
          'component_inputs': {str(path.relative_to(ROOT)): hashlib.file_digest(path.open('rb'), 'sha256').hexdigest() for path in sources},
          'returncode': run.returncode}
if run.returncode == 0:
    result = json.loads(run.stdout)['result']
    counters = re.search(r'MOTOAI_TRIG_CONTROL sin=(\d+) cos=(\d+) pair=(\d+) tiny=(\d+)', run.stderr)
    report['interposition_calls'] = list(map(int, counters.groups())) if counters else None
    report['first_token_nll_error'] = abs(result['token_nll'][0] - phone['token_nll'][0])
    report['top8_exact'] = result['first_logits_top8'] == phone['first_logits_top8']
    host_nodes = {node['name']: node for node in result['node_probes']}
    report['node_fingerprint_mismatches'] = [node['name'] for node in phone['node_probes']
        if node['status'] == 'captured' and (node['name'] not in host_nodes or
            node['fingerprint_fnv1a64'] != host_nodes[node['name']]['fingerprint_fnv1a64'])]
    report['passed'] = bool(counters and sum(report['interposition_calls']) > 0 and report['top8_exact'] and
                            report['first_token_nll_error'] <= 1e-6 and not report['node_fingerprint_mismatches'])
(out / 'control.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
print('Results:', out.relative_to(ROOT))
raise SystemExit(0 if report['passed'] else 1)
