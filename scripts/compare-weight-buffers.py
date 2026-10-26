#!/usr/bin/env python3
"""Serial same-APK native/repack screening; preserve every failure and raw trial."""
import argparse
import copy
import datetime
import hashlib
import importlib.util
import json
import pathlib
import re
import random
import statistics
import subprocess
import sys

from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready, comparable_power, parse_battery
from phone_model import model_sha256
from weight_buffer_checks import check_result, compare_results, check_speed_evidence
from prefill_diagnostics import prefill_observations
from frequency_caps import POLICY_COMMAND, parse_policies, parse_legacy_caps, screen_caps, cpu_set

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('model', choices=['gemma', 'ornith'])
p.add_argument('fixture')
p.add_argument('--repeat', type=int, default=1)
p.add_argument('--warmup', type=int, default=0)
p.add_argument('--timeout', type=int, default=1800)
p.add_argument('--quality-gate', type=pathlib.Path, help='Required before speed screening; exact APK/model/config evidence')
p.add_argument('--reference-artifact', type=pathlib.Path, help='Quality only: reuse an immutable corrected native CPU trial; never reuse timings')
p.add_argument('--require-identical', action='store_true', help='Also require all generated IDs and finish reason to match')
p.add_argument('--candidate-policy', choices=['cpu_repack','cpu_repack_q6','cpu_repack_q4','native_no_prefault','native_transient_repack'], default='cpu_repack')
p.add_argument('--plan-lock', type=pathlib.Path, help='Explicit no_alloc packed-allocation plan for selective policy')
args = p.parse_args()
demand = args.candidate_policy == 'native_no_prefault'
transient = args.candidate_policy == 'native_transient_repack'
native_control = demand or transient
if not 1 <= args.repeat <= 10 or not 0 <= args.warmup <= 1: p.error('Invalid trial count')
if args.candidate_policy in ('cpu_repack_q6','cpu_repack_q4') and not args.plan_lock: p.error('Selective policy requires --plan-lock')
plan_path = args.plan_lock or ROOT/'bench/m3-cpu-repack-preflight-20261025.json'
if native_control and args.plan_lock: p.error('Native-buffer controls do not use a packed-model allocation plan')
plan = None if native_control else json.loads(plan_path.read_text())
lease = acquire_phone_lease()
require_phone_ready()
fixture = ROOT / 'bench/fixtures/generated' / args.model / (args.fixture + '.json')
golden_path = fixture.with_suffix('.golden.json')
golden = json.loads(golden_path.read_text())
if hashlib.sha256(fixture.read_bytes()).hexdigest() != golden['request_sha256']: raise RuntimeError('Frozen fixture changed')
base = json.loads(fixture.read_text())
base['config']['load_mode'] = 'mmap'
base['config'].pop('weight_buffers', None)
base['config'].pop('mmap_prefault', None)
base['config'].pop('transient_repack', None)
prefill_only = golden['kind'] == 'prefill_performance'
performance = golden['kind'] in ('performance', 'prefill_performance')
if performance and (args.repeat < 5 or args.warmup != 1):
    p.error('Speed screening requires one warmup and at least five complete pairs')
if prefill_only and (base['request']['max_tokens'] != 1 or golden.get('required_output_tokens') != 1):
    raise RuntimeError('Prefill-only performance requires exactly one output token')
speed_metrics = ['client_first_piece_speed_ratio','ttft_speed_ratio','prefill_speed_ratio','native_load_plus_ttft_speed_ratio']
if not prefill_only: speed_metrics += ['decode_speed_ratio','emission_speed_ratio']
if args.reference_artifact and (performance or args.repeat != 1 or args.warmup):
    p.error('Reused reference is allowed only for one quality/context check, never speed')
model = next(m for m in json.loads((ROOT/'bench/models.lock.json').read_text())['models'] if m['id'] == args.model)
apk_sha = hashlib.file_digest((ROOT/'app/build/outputs/apk/debug/app-debug.apk').open('rb'), 'sha256').hexdigest()
if performance:
    if not args.quality_gate: p.error('Speed screening requires --quality-gate')
    gate = json.loads(args.quality_gate.read_text())
    if gate.get('candidate_policy','cpu_repack') != args.candidate_policy: raise RuntimeError('Quality gate is for another candidate policy')
    if (gate.get('passed') is not True or gate.get('apk_sha256') != apk_sha or gate.get('model_sha256') != model['sha256'] or
        gate.get('config') != base['config'] or not all(gate.get('checks', {}).get(k) is True for k in ('code_nll', 'russian_nll', 'completed_task', 'filled_context'))):
        raise RuntimeError('Quality gate does not qualify this APK/model/config')
    evidence = {}
    for key,item in gate['evidence'].items():
        path = (ROOT/item['path']).resolve()
        if not path.is_relative_to(ROOT/'bench/results') or hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise RuntimeError('Quality evidence changed')
        evidence[key] = json.loads(path.read_text())
    check_speed_evidence(evidence,apk_sha,model['sha256'],base['config'],args.candidate_policy)
if model_sha256(model['filename']) != model['sha256']: raise RuntimeError('Phone GGUF SHA mismatch')
out = ROOT/'bench/results'/f'weight-buffers-{args.model}-{args.fixture}-{datetime.datetime.now(datetime.timezone.utc):%Y%m%dT%H%M%SZ}'
out.mkdir(parents=True)
report = {'passed': False, 'scope': ('Candidate quality vs preserved corrected native artifact; identical inputs/config; no timing comparison'
          if args.reference_artifact else 'Same-APK bounded weight/loading screening; not whole-model/sustained acceptance'),
          'apk_sha256': apk_sha, 'model_sha256': model['sha256'], 'config': base['config'], 'fixture': args.fixture,
          'candidate_policy':args.candidate_policy, 'plan_lock_sha256':None if native_control else hashlib.sha256(plan_path.read_bytes()).hexdigest(),
          'frequency_gate_scope':'All measured performance pairs require equal relevant boundary caps; warmup snapshots are diagnostic and excluded from statistics',
          'trial_roles': {'native':'native upstream prefault', 'cpu_repack':'native demand paging'} if demand else {'native':'native', 'cpu_repack':args.candidate_policy},
          'source_request_sha256': golden['request_sha256'], 'golden_sha256': hashlib.sha256(golden_path.read_bytes()).hexdigest(),
          'gates_sha256': hashlib.sha256((ROOT/'bench/logit-gates.json').read_bytes()).hexdigest(), 'pairs': []}
gates = json.loads((ROOT/'bench/logit-gates.json').read_text())
evaluator_spec = importlib.util.spec_from_file_location('evaluator', ROOT/'scripts/evaluate-code.py')
evaluator = importlib.util.module_from_spec(evaluator_spec); evaluator_spec.loader.exec_module(evaluator)
snapshot = out/'sources'; snapshot.mkdir()
for name in ['compare-weight-buffers.py', 'weight_buffer_checks.py', 'prefill_diagnostics.py', 'evaluate-code.py', 'phone_health.py', 'phone_model.py', 'frequency_caps.py']:
    (snapshot/name).write_bytes((ROOT/'scripts'/name).read_bytes())
(out/'source-fixture.json').write_bytes(fixture.read_bytes())
(out/'source-golden.json').write_bytes(golden_path.read_bytes())
(out/'gates.json').write_bytes((ROOT/'bench/logit-gates.json').read_bytes())
if not native_control: (out/'packed-plan-lock.json').write_bytes(plan_path.read_bytes())
if performance: (out/'quality-gate.json').write_bytes(args.quality_gate.read_bytes())
try:
    if performance:
        policy_snapshot = subprocess.check_output(['adb','-d','shell',POLICY_COMMAND], text=True, timeout=15)
        (out/'cpu-policy-topology.txt').write_text(policy_snapshot)
        topology = parse_policies(policy_snapshot)
    reference_data = None
    repeated_work = None
    if args.reference_artifact:
        reference_path = args.reference_artifact.resolve()
        if not reference_path.is_relative_to(ROOT/'bench/results') or (reference_path/'invalidated.json').exists():
            raise RuntimeError('Invalid reference artifact')
        manifest = json.loads((reference_path/'manifest.json').read_text())
        expected = copy.deepcopy(manifest['request_spec'])
        expected['config'].pop('weight_buffers', None)
        if 'mmap_prefault' in expected['config']:
            expected['config'].pop('mmap_prefault')
            report['reference_loading_adaptation'] = 'Explicit mmap_prefault ignored for quality-only reference matching; never reuse its timings'
        if manifest['model']['sha256'] != model['sha256'] or expected != base:
            raise RuntimeError('Reused reference model/request/config differs')
        reference_data = json.loads((reference_path/'result.json').read_text())
        load = reference_data['load']
        if load.get('all_extra_buffers_disabled') is not True or load.get('kv_type') != 'f16' or load.get('runtime_variant') not in ('f32-recurrent-scalar-math','f32-recurrent-repack-stream','f32-recurrent-repack-order','f32-recurrent-native-demand'):
            raise RuntimeError('Unqualified reference arithmetic or weight policy')
        # Older no-extra-buffer reports predate the explicit enum. Preserve raw
        # bytes and mark this local schema adaptation, never rewrite the trial.
        load.setdefault('weight_buffers','native')
        report['reference_artifact'] = str(reference_path.relative_to(ROOT))
        report['reference_apk_sha256'] = manifest['apk_sha256']
        report['reference_source_sha256'] = {}
        for name in ('manifest.json','result.json'):
            contents = (reference_path/name).read_bytes()
            (out/('reference-'+name)).write_bytes(contents)
            report['reference_source_sha256'][name] = hashlib.sha256(contents).hexdigest()
    for trial in range(args.warmup + args.repeat):
        pair = {'warmup': trial < args.warmup, 'trials': {}, 'passed': False}
        report['pairs'].append(pair)
        results = {}
        order = ['native', 'cpu_repack'] if trial % 2 == 0 else ['cpu_repack', 'native']
        if reference_data is not None:
            order = ['cpu_repack']
            results['native'] = check_result(reference_data,golden,'native',base['request'])
            pair['trials']['native'] = {'artifact':report['reference_artifact'],'reused_quality_reference':True}
        pair['order'] = order
        for policy in order:
            effective_policy = 'native' if policy == 'native' or native_control else args.candidate_policy
            spec = copy.deepcopy(base); spec['config']['weight_buffers'] = effective_policy
            if demand: spec['config']['mmap_prefault'] = policy == 'native'
            if transient: spec['config']['transient_repack'] = policy != 'native'
            path = out/f'request-{trial}-{policy}.json'; path.write_text(json.dumps(spec, indent=2)+'\n')
            print(args.model, args.fixture, trial, policy, flush=True)
            run = subprocess.run([sys.executable, 'scripts/run-app-smoke.py', args.model, '--service', '--request',
                str(path.relative_to(ROOT)), '--record-device', '--timeout', str(args.timeout)], cwd=ROOT,
                capture_output=True, text=True, timeout=args.timeout+240, **lease.child_options())
            (out/f'{trial}-{policy}.log').write_text(run.stdout+'\n'+run.stderr)
            match = re.search(r'^Results: (bench/results/[^\n]+)$', run.stdout, re.MULTILINE)
            if not match: raise RuntimeError('No raw trial artifact; inspect log')
            artifact = ROOT/match.group(1)
            record = {'artifact': str(artifact.relative_to(ROOT))}; pair['trials'][policy] = record
            if run.returncode: raise RuntimeError('APK trial failed: '+str(artifact.relative_to(ROOT)))
            manifest = json.loads((artifact/'manifest.json').read_text())
            if manifest['apk_sha256'] != apk_sha or manifest['request_spec'] != spec or (artifact/'invalidated.json').exists():
                raise RuntimeError('APK/request identity mismatch or invalidated trial')
            data = json.loads((artifact/'result.json').read_text())
            results[policy] = check_result(data, golden, effective_policy, base['request'],
                mmap_prefault=(policy == 'native') if demand else None,
                transient_repack=(policy != 'native') if transient else None)
            for key in ['context','threads','batch','micro_batch','load_mode']:
                if data['load'][key] != spec['config'][key]: raise RuntimeError('Loaded configuration differs: '+key)
            if policy == 'cpu_repack' and not native_control:
                expected_packed = plan['models'][args.model]['packed_model_bytes']
                if sum(b['model_bytes'] for b in data['load']['buffer_allocations'] if b['buffer']=='CPU_REPACK') != expected_packed:
                    raise RuntimeError('Actual packed allocation differs from pinned metadata plan')
            before, after = (json.loads((artifact/f'device-{when}.json').read_text())['observations'] for when in ('before','after'))
            powers = [parse_battery(item['battery']['stdout']) for item in (before, after)]
            intervals = results[policy]['inter_token_us']
            if len(intervals) != results[policy]['generated_tokens']-1 or any(x <= 0 for x in intervals):
                raise RuntimeError('Missing/invalid actual token emission intervals')
            def swap_bytes(status):
                match = re.search(r'^VmSwap:\s*(\d+)\s+kB',status or '',re.MULTILINE)
                return int(match.group(1))*1024 if match else None
            sampled_swaps = [swap_bytes(data.get('process_status'))]
            progress_rows = []
            progress_path = artifact/'progress.jsonl'
            if progress_path.exists():
                for line in progress_path.read_text().splitlines():
                    progress_row = json.loads(line)
                    progress_rows.append(progress_row)
                    sampled_swaps.append(swap_bytes(progress_row.get('process_status')))
            sampled_swaps = [value for value in sampled_swaps if value is not None]
            record.update(power_before=powers[0], power_after=powers[1], comparable_power=comparable_power(*powers),
                completed_interactive=data.get('completed_interactive'), completed_importance=data.get('completed_importance'),
                process_status=data.get('process_status'), end_swap_bytes=swap_bytes(data.get('process_status')),
                sampled_peak_swap_bytes=max(sampled_swaps) if sampled_swaps else None,
                prompt_tokens=results[policy]['prompt_tokens'], generated_tokens=results[policy]['generated_tokens'],
                finish_reason=results[policy]['finish_reason'], logits_checks=results[policy].get('logits_checks'),
                thermal_status=data.get('thermal_status'), loaded_pss_kib=data.get('loaded_pss_kib'), completed_pss_kib=data.get('completed_pss_kib'),
                admission=data.get('load_admission'), load_memory_watch=data.get('load_memory_watch'),
                load_us=data['load']['load_us'], ttft_us=results[policy]['first_target_token_us'],
                client_to_first_piece_us=data['client_to_first_piece_us'], service_request_us=data['service_compute_wall_us'],
                emission_tok_s=len(intervals)*1e6/sum(intervals) if intervals else None,
                native_load_plus_ttft_us=data['load']['load_us']+results[policy]['first_target_token_us'],
                prefill_us=results[policy]['prefill_us'], decode_us=results[policy]['decode_us'],
                backend_decode_tok_s=(results[policy]['backend_perf']['decode_evals']*1000/results[policy]['backend_perf']['decode_ms']
                    if results[policy]['backend_perf']['decode_evals'] > 0 and results[policy]['backend_perf']['decode_ms'] > 0 else None),
                cpu_workspace=results[policy].get('cpu_workspace'))
            if performance:
                record['frequency_snapshots'] = []
                for observation in (before, after):
                    caps = parse_legacy_caps(observation.get('cpu_frequencies', {}).get('stdout',''))
                    record['frequency_snapshots'].append({key:{'cpus':value['cpus'],'max_khz':caps.get(key)} for key,value in topology.items()})
                allowed = re.search(r'^Cpus_allowed_list:\s*(.+)$',data.get('process_status',''),re.MULTILINE)
                record['allowed_cpus'] = sorted(cpu_set(allowed[1])) if allowed else []
            record['prefill_observations'] = prefill_observations(progress_rows)
            if golden['kind'] in ('quality', 'context_quality'):
                target = {'identifier':golden['expected_identifier']} if golden['kind'] == 'context_quality' else {'function':'lower_bound' if 'binary-search' in args.fixture else 'add'}
                assessment = evaluator.assess_report(data, **target)
                record['quality'] = assessment
                if not assessment['quality_passed']: raise RuntimeError('Completed-answer quality gate failed')
            (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
        pair['comparison'] = compare_results(results['native'], results['cpu_repack'], gates, require_ids=performance or args.require_identical)
        if not pair['comparison']['numeric_passed']: raise RuntimeError('Unchanged numeric gates failed')
        if performance:
            a,b = (pair['trials'][k] for k in ('native','cpu_repack'))
            work = (results['native']['trace']['generated_ids'],results['native']['finish_reason'])
            if repeated_work is not None and work != repeated_work:
                raise RuntimeError('Repeated greedy output differs across pairs')
            repeated_work = work
            if not all(r['comparable_power'] and r['thermal_status'] is not None and r['thermal_status'] < 2 for r in (a,b)) or not comparable_power(a['power_before'],b['power_after']):
                raise RuntimeError('Pair power/thermal conditions are not comparable')
            if any(a[key] is None or a[key] != b[key] for key in ['completed_interactive','completed_importance']):
                raise RuntimeError('Pair screen/process-importance states differ')
            temperatures = [r['power_before'].get('temperature_c') for r in (a,b)]
            if None in temperatures or abs(temperatures[0]-temperatures[1]) > 2:
                raise RuntimeError('Pair starting battery temperatures differ by more than2C')
            if a['allowed_cpus'] != b['allowed_cpus']: raise RuntimeError('Pair allowed CPUs differ')
            pair['frequency_screens'] = [screen_caps(a['frequency_snapshots'][0], snapshot, a['allowed_cpus'])
                for snapshot in [*a['frequency_snapshots'],*b['frequency_snapshots']]]
            pair['frequency_caps_comparable'] = all(item['boundary_caps_comparable'] for item in pair['frequency_screens'])
            if not pair['warmup'] and not pair['frequency_caps_comparable']:
                raise RuntimeError('Relevant frequency ceilings changed or are unknown; preserve failed pair, no speed acceptance')
            if not prefill_only:
                if any(r[key] is None or r[key] <= 0 for r in (a,b) for key in ('backend_decode_tok_s','emission_tok_s')):
                    raise RuntimeError('No measured decode/emission work')
                pair['decode_speed_ratio'] = b['backend_decode_tok_s']/a['backend_decode_tok_s']
                pair['emission_speed_ratio'] = b['emission_tok_s']/a['emission_tok_s']
            pair['client_first_piece_speed_ratio'] = a['client_to_first_piece_us']/b['client_to_first_piece_us']
            pair['ttft_speed_ratio'] = a['ttft_us']/b['ttft_us']
            pair['prefill_speed_ratio'] = a['prefill_us']/b['prefill_us']
            pair['native_load_plus_ttft_speed_ratio'] = a['native_load_plus_ttft_us']/b['native_load_plus_ttft_us']
        pair['passed'] = True
        (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
        print('Pair PASS', pair['comparison'], flush=True)
    report['passed'] = True
    if performance:
        for metric in speed_metrics:
            values = [row[metric] for row in report['pairs'] if not row['warmup']]
            report[metric] = {'values':values,'median':statistics.median(values),'minimum':min(values),'maximum':max(values)}
            if len(values) >= 5:
                rng = random.Random(20261025)
                bootstrap = sorted(statistics.median(rng.choices(values,k=len(values))) for _ in range(10000))
                report[metric]['bootstrap_median_ci95'] = [bootstrap[249],bootstrap[9749]]
        report['profiles'] = {}
        for policy in ['native','cpu_repack']:
            rows = [pair['trials'][policy] for pair in report['pairs'] if not pair['warmup']]
            keys = ['load_us','ttft_us','client_to_first_piece_us','service_request_us','prefill_us','native_load_plus_ttft_us','completed_pss_kib']
            if not prefill_only: keys += ['backend_decode_tok_s','emission_tok_s']
            report['profiles'][policy] = {key:statistics.median(row[key] for row in rows) for key in keys}
except (OSError,ValueError,KeyError,TypeError,RuntimeError,subprocess.SubprocessError) as error:
    report['passed'] = False
    report['error'] = str(error)
finally:
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Results:',out.relative_to(ROOT),flush=True)
    print('PASS' if report['passed'] else report.get('error','FAIL'),flush=True)
raise SystemExit(0 if report['passed'] else 1)
