#!/usr/bin/env python3
"""Cross-CPU corpus check of a standalone, explicit patch variant; leaves APK unchanged."""
import argparse
import datetime
import hashlib
import json
import math
import pathlib
import shlex
import shutil
import subprocess
from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready
from phone_model import model_source, model_sha256

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('model', choices=['gemma', 'ornith'])
p.add_argument('--corpus', choices=['code', 'ru'], default='code')
p.add_argument('--probe-first', action='store_true', help='Observe the same prefix and first scored token; separate diagnostic request')
p.add_argument('--variant', choices=['f32-attention-scalar-math', 'f32-recurrent-scalar-math'], default='f32-attention-scalar-math')
args = p.parse_args()
lease = acquire_phone_lease()
require_phone_ready()
model = next(m for m in json.loads((ROOT / 'bench/models.lock.json').read_text())['models'] if m['id'] == args.model)
source = model_source(model['filename'])
if source['storage'] == 'app-private': raise RuntimeError('Standalone shell diagnostic requires the canonical shared model; never create a second weight copy')
if model_sha256(model['filename']) != model['sha256']: raise RuntimeError('Phone model SHA changed')
local = ROOT / 'models' / model['filename']
digest = lambda path: hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()
if digest(local) != model['sha256']: raise RuntimeError('Host model SHA changed')
host_build = ROOT / ('build/cpu-f32-math-v2-host' if args.variant == 'f32-attention-scalar-math' else 'build/cpu-recurrent-host')
phone_build = ROOT / ('build/cpu-f32-math-android' if args.variant == 'f32-attention-scalar-math' else 'build/cpu-recurrent-android')
fixture = ROOT / 'bench/fixtures/generated' / args.model / f'teacher-forcing-{args.corpus}-v1.json'
golden = json.loads(fixture.with_suffix('.golden.json').read_text())
if digest(fixture) != golden['request_sha256']: raise RuntimeError('Changed immutable fixture')
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
out = ROOT / 'bench/results' / f'cpu-variant-score-{args.model}-{args.corpus}-{stamp}'; out.mkdir(parents=True)
source_fixture = fixture
if args.probe_first:
    spec = json.loads(fixture.read_text())
    spec['config']['tensor_probe'] = True
    spec['request']['max_tokens'] = 1
    spec['request']['score_continuation_ids'] = spec['request']['score_continuation_ids'][:1]
    fixture = out / 'request-probe.json'
    fixture.write_text(json.dumps(spec, indent=2) + '\n')
series = ROOT / 'patches/cpu-numeric/series.json'
gates_path = ROOT / 'bench/logit-gates.json'; gates = json.loads(gates_path.read_text())
report = {'passed': False, 'scope': 'Experimental F32 attention + double-to-float scalar exp/sin/cos on both CPUs. Phone runs under ADB shell, not app UID/FGS: numeric diagnostic only. Not a release/default/performance acceptance.',
          'model_sha256': model['sha256'], 'request_sha256': digest(fixture), 'series_sha256': digest(series),
          'gates_sha256': digest(gates_path), 'variant': args.variant, 'model_source': source}
report['probe_first'] = args.probe_first
report['source_fixture_sha256'] = digest(source_fixture)
if args.variant == 'f32-recurrent-scalar-math':
    report['additional_controls'] = 'Explicit fused F32 SSM convolution; double-to-float scalar unary exp/sin/cos/log. No weight or KV representation changes.'
archive = out / 'executables'; archive.mkdir()
files = [host_build / 'runtime-run', phone_build / 'motoai-reference', phone_build / 'motoai-attention']
files += list((host_build / 'bin').glob('*.dylib')) + list((phone_build / 'bin').glob('*.so'))
report['executables_and_libraries'] = {str(path.relative_to(ROOT)): digest(path) for path in files if not path.is_symlink()}
for path in files:
    if not path.is_symlink():
        target = archive / ('host' if host_build in path.parents else 'phone') / path.name
        target.parent.mkdir(exist_ok=True); shutil.copyfile(path, target)
destination = '/data/local/tmp/motoai/' + args.variant
def adb(*command): return subprocess.run(['adb', '-d', *command], text=True, capture_output=True, check=True, timeout=60)
try:
    adb('shell', shlex.join(['mkdir', '-p', destination]))
    for path in [phone_build / 'motoai-reference', *list((phone_build / 'bin').glob('*.so'))]: adb('push', str(path), destination + '/' + path.name)
    adb('push', str(fixture), destination + '/request.json')
    adb('shell', shlex.join(['chmod', '755', destination + '/motoai-reference']))
    phone_command = 'LD_LIBRARY_PATH=' + shlex.quote(destination) + ' ' + shlex.join([destination + '/motoai-reference', source['path'], destination + '/request.json'])
    for name, command in [('host', [str(host_build / 'runtime-run'), str(local), str(fixture)]), ('phone', ['adb', '-d', 'shell', phone_command])]:
        run = subprocess.run(command, capture_output=True, text=True, timeout=300)
        (out / (name + '.json')).write_text(run.stdout); (out / (name + '.stderr.txt')).write_text(run.stderr)
        if run.returncode: raise RuntimeError(name + ' diagnostic failed')
    left = json.loads((out / 'host.json').read_text())['result']; right = json.loads((out / 'phone.json').read_text())['result']
    expected_ids = json.loads(fixture.read_text())['request']['score_continuation_ids']
    for result in [left, right]:
        if (result.get('mode') != 'teacher_forcing' or result.get('finish_reason') != 'scored' or
            result.get('logits_checks') != len(expected_ids) or result['trace']['prompt_ids'] != golden['expected']['prompt_ids'] or
            result['trace']['generated_ids'] != expected_ids or len(result['token_nll']) != len(expected_ids) or
            not all(math.isfinite(value) for value in result['token_nll'])): raise RuntimeError('Changed trajectory/nonfinite scoring')
    errors = [abs(a-b) for a,b in zip(left['token_nll'], right['token_nll'])]
    a = dict(zip(left['first_logits_top8']['ids'], left['first_logits_top8']['values']))
    b = dict(zip(right['first_logits_top8']['ids'], right['first_logits_top8']['values']))
    report.update(mean_absolute_token_nll_error=sum(errors)/len(errors), maximum_absolute_token_nll_error=max(errors),
                  first_top8_set_matches=a.keys()==b.keys(), first_top8_max_shared_logit_absolute_error=max((abs(a[k]-b[k]) for k in a.keys() & b.keys()), default=math.inf),
                  host_mean_nll=left['mean_nll'], phone_mean_nll=right['mean_nll'], scored_tokens=len(expected_ids))
    report['passed'] = (report['first_top8_set_matches'] and
        all(report[key] <= gates[gate] for key,gate in [('mean_absolute_token_nll_error','mean_absolute_token_nll_error_max'),
            ('maximum_absolute_token_nll_error','maximum_absolute_token_nll_error_max'),
            ('first_top8_max_shared_logit_absolute_error','first_top8_max_shared_logit_absolute_error')]))
    if args.probe_first:
        host_nodes = {node['name']: node for node in left['node_probes']}
        report['node_comparison'] = [{'name': node['name'], 'status': node['status'],
            'matches': node['status'] == 'captured' and node['name'] in host_nodes and node['fingerprint_fnv1a64'] == host_nodes[node['name']]['fingerprint_fnv1a64']}
            for node in right['node_probes']]
except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error: report['error'] = str(error)
(out / 'comparison.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k:v for k,v in report.items() if k != 'executables_and_libraries'}, indent=2)); print('Results:', out.relative_to(ROOT))
raise SystemExit(0 if report['passed'] else 1)
