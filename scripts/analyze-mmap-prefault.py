#!/usr/bin/env python3
"""Summarize immutable native prefault-control trials, including long-run caveats."""
import argparse
import hashlib
import json
import pathlib

from phone_health import comparable_power
from prefill_diagnostics import prefill_observations, key_values, faults

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('controller',type=pathlib.Path)
p.add_argument('--output',type=pathlib.Path,required=True)
p.add_argument('--long-only',action='store_true',help='Analyze completed long evidence only; controller and short-speed acceptance remain incomplete')
args = p.parse_args()
if args.output.exists(): raise RuntimeError('Preserve existing analysis; select a new output')
controller=json.loads(args.controller.read_text())
if (not args.long_only and controller.get('passed') is not True) or controller.get('candidate_policy')!='native_no_prefault':
    raise RuntimeError('Controller is incomplete or for another control')
report={'passed':True,'scope':'Bounded loading control only; long quality-pair timings are descriptive and individually screened, not five-pair/endurance speed acceptance. CPU time includes barriers/spin; swap counters alone do not establish stall time or causality.',
        'long_only':args.long_only,'controller_passed':controller.get('passed') is True,
        'controller':str(args.controller),'controller_sha256':hashlib.sha256(args.controller.read_bytes()).hexdigest(),
        'apk_sha256':controller['apk_sha256'],'models':{}}
for model,entry in controller['models'].items():
    path=ROOT/entry['evidence']['filled_context'];long=json.loads(path.read_text())
    if long['apk_sha256']!=controller['apk_sha256'] or not long['passed'] or long['candidate_policy']!='native_no_prefault':
        raise RuntimeError('Long-pair identity/quality mismatch')
    item={'long_report':str(path.relative_to(ROOT)),'long_report_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'pairs':[]}
    repeated=None
    for pair in long['pairs']:
        row={'order':pair['order'],'quality_comparison':pair['comparison'],'trials':{}}
        reasons=[]
        allocation_plan=None
        for role,trial in pair['trials'].items():
            artifact=ROOT/trial['artifact'];data=json.loads((artifact/'result.json').read_text())
            if data['load']['weight_buffers']!='native' or data['load'].get('mmap_prefault') is not (role=='native'):
                raise RuntimeError('Not the requested native mmap control')
            actual_plan=sorted((v['buffer'],v['model_bytes'],v['context_bytes'],v['compute_bytes']) for v in data['load']['buffer_allocations'])
            if allocation_plan is not None and actual_plan!=allocation_plan:
                raise RuntimeError('Native allocation plan changed across prefault control')
            allocation_plan=actual_plan
            ids=data['result']['trace']['generated_ids']
            if repeated is not None and ids!=repeated: raise RuntimeError('Different repeated generated work')
            repeated=ids
            progress=[json.loads(line) for line in (artifact/'progress.jsonl').read_text().splitlines()]
            observation=prefill_observations(progress)
            prefills=[v for v in progress if v.get('phase')=='prefill' and v.get('completed') is not None]
            decodes=[v for v in progress if v.get('phase')=='decode' and v.get('completed') is not None]
            bridge=None
            if prefills and decodes and prefills[-1]['completed']<trial['prompt_tokens']:
                start,end=prefills[-1],decodes[0]
                bridge={'scope':'Missing final prefill boundary bridged to first observed decode snapshot; includes indicated generated tokens, not pure prefill timing.',
                        'from_prompt_token':start['completed'],'prompt_tokens':trial['prompt_tokens'],
                        'includes_generated_tokens':end['completed'],
                        'wall_s':(end['service_elapsed_us']-start['service_elapsed_us'])/1e6,
                        'process_cpu_s':(end['cpu_ms']-start['cpu_ms'])/1000}
                for key,parser,source in [('read_bytes',key_values,'process_io'),('major_faults',faults,'process_stat')]:
                    a,b=parser(start.get(source)),parser(end.get(source))
                    bridge[key]=b[key]-a[key] if key in a and key in b else None
                    if bridge[key] is not None and bridge[key]<0: raise RuntimeError('Reset tail counters')
            row['trials'][role]={'artifact':trial['artifact'],'load_s':trial['load_us']/1e6,'prefill_s':trial['prefill_us']/1e6,
                'prompt_tokens':trial['prompt_tokens'],'generated_tokens':trial['generated_tokens'],'finish_reason':trial['finish_reason'],
                'loaded_pss_kib':trial['loaded_pss_kib'],'completed_pss_kib':trial['completed_pss_kib'],
                'load_memory_watch':trial['load_memory_watch'],'end_process_memory_bytes':key_values(trial['process_status']),
                'sampled_peak_swap_bytes':trial['sampled_peak_swap_bytes'],'prefill_observations':observation,
                'final_prefill_decode_bridge':bridge}
        a,b=(pair['trials'][k] for k in ('native','cpu_repack'))
        if not all(t['comparable_power'] and t['thermal_status'] is not None and t['thermal_status']<2 for t in (a,b)) or not comparable_power(a['power_before'],b['power_after']):
            reasons.append('Power/thermal state not comparable')
        temperatures=[t['power_before'].get('temperature_c') for t in (a,b)]
        if None in temperatures or abs(temperatures[0]-temperatures[1])>2: reasons.append('Pair starting battery temperatures differ by more than2C')
        if any(a[key] is None or a[key]!=b[key] for key in ('completed_interactive','completed_importance')):
            reasons.append('Screen/process importance not comparable')
        row['starting_temperatures_c']=temperatures;row['timing_comparable']=not reasons;row['timing_exclusions']=reasons
        row['native_allocation_plans_identical']=True
        row['descriptive_prefill_ratio_native_over_demand']=a['prefill_us']/b['prefill_us']
        item['pairs'].append(row)
    if args.long_only:
        item['short_speed_status']='Not assessed by this long-only analysis'
        report['models'][model]=item
        continue
    speed_path=ROOT/entry['speed'];speed=json.loads(speed_path.read_text())
    if not speed['passed'] or speed['apk_sha256']!=controller['apk_sha256'] or speed['candidate_policy']!='native_no_prefault':
        raise RuntimeError('Short-speed identity/quality mismatch')
    item['short_speed_report']=str(speed_path.relative_to(ROOT))
    item['short_speed_sha256']=hashlib.sha256(speed_path.read_bytes()).hexdigest()
    item['short_profiles']=speed['profiles']
    item['short_paired_ratios']={key:speed[key] for key in ('emission_speed_ratio','client_first_piece_speed_ratio','native_load_plus_ttft_speed_ratio')}
    report['models'][model]=item
args.output.write_text(json.dumps(report,indent=2)+'\n')
print('Analysis:',args.output)
