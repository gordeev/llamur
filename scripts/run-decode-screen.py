#!/usr/bin/env python3
"""Serial same-APK CPU scheduling screening; preserve failed/confounded pairs."""
import argparse
import copy
import datetime
import hashlib
import json
import pathlib
import random
import re
import statistics
import subprocess
import sys

from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready, read_health, parse_battery
from phone_model import model_sha256
from frequency_caps import POLICY_COMMAND, parse_policies, parse_legacy_caps
from weight_buffer_checks import check_result, compare_results
from decode_checks import check_scheduling, decode_metrics, screen_pair

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk',type=pathlib.Path,default=ROOT/'app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--profiles', type=pathlib.Path, default=ROOT/'bench/decode-profiles-20261025.json')
    parser.add_argument('--fixture', default='decode64-short-v1')
    parser.add_argument('--repeat', type=int, default=1)
    parser.add_argument('--only', nargs='+')
    parser.add_argument('--strict-speed', action='store_true', help='Require all five or more measured pairs to pass condition gates')
    parser.add_argument('--transient', action='store_true', help='Use bounded prefill workspace in both arms; must fit the existing profile')
    args = parser.parse_args()
    if not 1 <= args.repeat <= 10 or args.strict_speed and args.repeat < 5:
        parser.error('Strict speed needs >=5 measured pairs; screening repeat range1..10')
    lease = acquire_phone_lease()
    health = require_phone_ready()
    profiles = json.loads(args.profiles.read_text())
    candidates = profiles['candidates']
    if args.only:
        candidates = [item for item in candidates if item['id'] in args.only]
        if {item['id'] for item in candidates} != set(args.only): parser.error('Unknown candidate')
    if not candidates or len({item['id'] for item in [profiles['baseline'],*candidates]}) != len(candidates)+1:
        parser.error('Empty or duplicate profiles')
    for item in [profiles['baseline'],*candidates]:
        if set(item)-{'id','threads','threads_batch','cpu_affinity'}: parser.error('Unknown scheduling profile key')
    model = next(item for item in json.loads((ROOT/'bench/models.lock.json').read_text())['models'] if item['id']=='ornith')
    if model_sha256(model['filename']) != model['sha256']: raise RuntimeError('Phone model SHA differs')
    apk = args.apk.resolve()
    if not apk.is_relative_to(ROOT): parser.error('Workspace APK only')
    apk_sha = hashlib.file_digest(apk.open('rb'),'sha256').hexdigest()
    out = ROOT/'bench/results'/('decode-screen-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    out.mkdir(parents=True)
    report = dict(passed=False, scope='Bounded same-APK CPU scheduling screen; original GGUF, quantization and F16 KV; no M3/default/endurance/product promotion',
                  strict_speed=args.strict_speed, apk_sha256=apk_sha, model_sha256=model['sha256'], initial_health=health,
                  fixture=args.fixture, profiles=profiles, transient_repack=args.transient, quality=[], pairs=[], default_promoted=False)
    for source in ['run-decode-screen.py','decode_checks.py','weight_buffer_checks.py','frequency_caps.py','phone_health.py','phone_model.py']:
        directory=out/'sources'; directory.mkdir(exist_ok=True)
        (directory/source).write_bytes((ROOT/'scripts'/source).read_bytes())
    (out/'profiles.json').write_bytes(args.profiles.read_bytes())
    gates=json.loads((ROOT/'bench/logit-gates.json').read_text()); (out/'numeric-gates.json').write_text(json.dumps(gates,indent=2)+'\n')
    sequence=0
    def persist(): (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    def frozen(name):
        path=ROOT/'bench/fixtures/generated/ornith'/(name+'.json')
        golden=json.loads(path.with_suffix('.golden.json').read_text())
        if hashlib.sha256(path.read_bytes()).hexdigest()!=golden['request_sha256']: raise RuntimeError('Frozen request changed')
        (out/(name+'.source.json')).write_bytes(path.read_bytes())
        (out/(name+'.golden.json')).write_bytes(path.with_suffix('.golden.json').read_bytes())
        return json.loads(path.read_text()),golden
    def run(profile,name):
        nonlocal sequence
        sequence+=1
        spec,golden=frozen(name)
        spec['config'].update({key:value for key,value in profile.items() if key!='id'})
        spec['config'].update(load_mode='mmap',weight_buffers='native',transient_repack=args.transient)
        request=out/f'{sequence:03d}-{profile["id"]}-{name}.json';request.write_text(json.dumps(spec,indent=2)+'\n')
        print(f'RUN {sequence} {profile["id"]} {name}',flush=True)
        completed=subprocess.run([sys.executable,'scripts/run-app-smoke.py','ornith','--service','--apk',str(apk),'--request',str(request.relative_to(ROOT)),
                                  '--record-device','--timeout','1200'],cwd=ROOT,capture_output=True,text=True,timeout=1440,**lease.child_options())
        (out/(request.stem+'.log')).write_text(completed.stdout+'\n'+completed.stderr)
        match=re.search(r'^Results: (bench/results/[^\n]+)$',completed.stdout,re.MULTILINE)
        record=dict(profile=profile['id'],request=str(request.relative_to(ROOT)),passed=False)
        report.setdefault('trials',[]).append(record);persist()
        if not match: raise RuntimeError('No raw trial artifact; inspect '+request.stem)
        artifact=ROOT/match[1];record['artifact']=str(artifact.relative_to(ROOT));persist()
        if completed.returncode: raise RuntimeError('Phone trial failed: '+str(artifact.relative_to(ROOT)))
        manifest=json.loads((artifact/'manifest.json').read_text())
        if manifest['apk_sha256']!=apk_sha or manifest['request_spec']!=spec or (artifact/'invalidated.json').exists(): raise RuntimeError('APK/request identity differs')
        data=json.loads((artifact/'result.json').read_text())
        result=check_result(data,golden,'native',spec['request'],transient_repack=args.transient)
        allowed=check_scheduling(data,spec['config'])
        for key in ('context','batch','micro_batch','load_mode'):
            if data['load'][key]!=spec['config'][key]: raise RuntimeError('Load geometry differs: '+key)
        before,after=(json.loads((artifact/f'device-{when}.json').read_text())['observations'] for when in ('before','after'))
        record.update(passed=True,allowed_cpus=allowed,used_cpus=profile.get('cpu_affinity') or allowed,
                      power_before=parse_battery(before['battery']['stdout']),power_after=parse_battery(after['battery']['stdout']),
                      thermal_status=data.get('thermal_status'),completed_interactive=data.get('completed_interactive'),
                      completed_importance=data.get('completed_importance'),loaded_pss_kib=data.get('loaded_pss_kib'),
                      completed_pss_kib=data.get('completed_pss_kib'),process_status=data.get('process_status'),
                      cpu_scheduling=result['cpu_scheduling'],load_us=data['load']['load_us'],cpu_workspace=result.get('cpu_workspace'))
        record['frequency_snapshots']=[]
        for observation in (before,after):
            caps=parse_legacy_caps(observation.get('cpu_frequencies',{}).get('stdout',''))
            record['frequency_snapshots'].append({key:{'cpus':value['cpus'],'max_khz':caps.get(key)} for key,value in topology.items()})
        if golden['kind']=='decode_performance':
            record.update(decode_metrics(result));record['client_to_first_piece_us']=data['client_to_first_piece_us']
        persist()
        return record,result
    try:
        text=subprocess.check_output(['adb','-d','shell',POLICY_COMMAND],text=True,timeout=15)
        (out/'cpu-policy-topology.txt').write_text(text);topology=parse_policies(text)
        # Re-score both corpora at every prospective config before timing it.
        quality_reference={}
        for name in ('teacher-forcing-code-prefill-v2','teacher-forcing-ru-prefill-v2'):
            record,result=run(profiles['baseline'],name);quality_reference[name]=result
            report['quality'].append(dict(profile=profiles['baseline']['id'],fixture=name,reference=record['artifact'],passed=True));persist()
        repeated_work=None
        for candidate in candidates:
            for name,reference in quality_reference.items():
                record,result=run(candidate,name)
                comparison=compare_results(reference,result,gates,require_ids=True)
                report['quality'].append(dict(profile=candidate['id'],fixture=name,artifact=record['artifact'],comparison=comparison,passed=comparison['numeric_passed']));persist()
                if not comparison['numeric_passed']: raise RuntimeError('Unchanged NLL/top8 quality gates failed')
            for trial in range(1+args.repeat):
                pair=dict(candidate=candidate['id'],warmup=trial==0,trials={},passed=False)
                report['pairs'].append(pair);persist()
                order=['baseline','candidate'] if trial%2==0 else ['candidate','baseline'];pair['order']=order
                results={}
                for role in order:
                    record,result=run(profiles['baseline'] if role=='baseline' else candidate,args.fixture)
                    pair['trials'][role]=record;results[role]=result;persist()
                comparison=compare_results(results['baseline'],results['candidate'],gates,require_ids=True)
                pair['comparison']=comparison
                if not comparison['numeric_passed']: raise RuntimeError('Unchanged decode numeric gates failed')
                work=(results['baseline']['trace']['generated_ids'],results['baseline']['finish_reason'])
                if repeated_work is not None and work!=repeated_work: raise RuntimeError('Repeated greedy work differs')
                repeated_work=work
                a,b=(pair['trials'][role] for role in ('baseline','candidate'))
                pair['conditions']=screen_pair(a,b)
                pair['ratios']={key:b[key]/a[key] for key in ('backend_decode_tok_s','emission_tok_s','first_half_emission_tok_s','second_half_emission_tok_s')}
                pair['ratios'].update(prefill=a['prefill_us']/b['prefill_us'],ttft=a['ttft_us']/b['ttft_us'],client_first_piece=a['client_to_first_piece_us']/b['client_to_first_piece_us'])
                pair['passed']=comparison['numeric_passed'] and (pair['conditions']['passed'] or pair['warmup']);persist()
                print('PAIR',candidate['id'],'warmup' if pair['warmup'] else 'measured',pair['ratios'],'conditions',pair['conditions']['passed'],flush=True)
                if args.strict_speed and not pair['warmup'] and not pair['conditions']['passed']:
                    raise RuntimeError('Measured pair is confounded; no strict speed acceptance')
        report['summaries']={}
        for candidate in candidates:
            rows=[row for row in report['pairs'] if row['candidate']==candidate['id'] and not row['warmup']]
            summary=dict(measured_pairs=len(rows),all_conditions_passed=all(row['conditions']['passed'] for row in rows),
                         numeric_passed=all(row['comparison']['numeric_passed'] for row in rows),ratios={})
            for key in rows[0]['ratios']:
                values=[row['ratios'][key] for row in rows]
                item=dict(values=values,median=statistics.median(values),minimum=min(values),maximum=max(values))
                if len(values)>=5:
                    rng=random.Random(20261025);boot=sorted(statistics.median(rng.choices(values,k=len(values))) for _ in range(10000))
                    item['bootstrap_median_ci95']=[boot[249],boot[9749]]
                summary['ratios'][key]=item
            summary['timing_qualified']=args.strict_speed and summary['all_conditions_passed'] and summary['numeric_passed']
            summary['profiles']={role:{key:statistics.median(row['trials'][role][key] for row in rows) for key in ('backend_decode_tok_s','emission_tok_s','prefill_us','ttft_us','client_to_first_piece_us','completed_pss_kib')} for role in ('baseline','candidate')}
            report['summaries'][candidate['id']]=summary
        report['passed']=True
    except (OSError,ValueError,KeyError,TypeError,RuntimeError,subprocess.SubprocessError,KeyboardInterrupt) as error:
        report['error']=str(error) or type(error).__name__
    finally:
        report['final_health']=read_health();persist()
        print('Results:',out.relative_to(ROOT),flush=True)
        print('PASS' if report['passed'] else report.get('error','FAIL'),flush=True)
    return 0 if report['passed'] else 1

if __name__=='__main__': raise SystemExit(main())
