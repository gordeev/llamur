#!/usr/bin/env python3
"""Predeclared one-load 4/7/7/4 endurance, >=15min per arm, no lucky short retries."""
import datetime
import hashlib
import json
import pathlib
import subprocess
import sys
import time
import uuid

from phone_lease import acquire_phone_lease
from phone_health import require_phone_ready, check_running_health, comparable_power, read_health
from frequency_caps import cpu_set
from phone_model import model_sha256
from sustained_checks import summarize

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    lease = acquire_phone_lease()
    initial = require_phone_ready()
    out = ROOT/'bench/results'/('sustained-decode-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    out.mkdir()
    apk = ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    tests = ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    frozen = ROOT/'bench/fixtures/generated/ornith/decode128-short-v1.json'
    golden = json.loads(frozen.with_suffix('.golden.json').read_text())
    spec = json.loads(frozen.read_text())
    assert hashlib.sha256(frozen.read_bytes()).hexdigest() == golden['request_sha256']
    reference_dir = ROOT/'bench/results/app-service-ornith-20261025T223113Z-6578ad6b'
    reference = json.loads((reference_dir/'result.json').read_text())
    gates = json.loads((ROOT/'bench/logit-gates.json').read_text())
    report = dict(passed=False, default_promoted=False, protocol=dict(order=[4,7,7,4], seconds_per_arm=900,
        cooling_target_c=37, maximum_cooling_s=1800, start_temperature_tolerance_c=1,
        excluded_warmups_per_arm=1, final_window_s=300, minimum_gain_both_orders=1.03,
        adaptation='Natural OEM adaptation retained, no forced clocks; start caps must match. No pruning/retries.',
        qualification='Two independent order controls on this fixture, no bootstrap/general architecture claim'),
        initial_health=initial, apk_sha256=hashlib.file_digest(apk.open('rb'),'sha256').hexdigest(),
        test_apk_sha256=hashlib.file_digest(tests.open('rb'),'sha256').hexdigest(), arms=[])
    def persist(): (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    def adb(*args, timeout=30, check=True):
        return subprocess.run(['adb','-d',*args],capture_output=True,text=True,timeout=timeout,check=check)
    for source in ['scripts/run-sustained-decode.py','scripts/sustained_checks.py',
                   'app/src/androidTest/java/org/motoai/runtime/SustainedChecks.kt']:
        (out/pathlib.Path(source).name).write_bytes((ROOT/source).read_bytes())
    (out/'reference-result.json').write_text(json.dumps(reference,indent=2)+'\n')
    (out/'golden.json').write_text(json.dumps(golden,indent=2)+'\n')
    (out/'numeric-gates.json').write_text(json.dumps(gates,indent=2)+'\n')
    persist()
    try:
        installed=adb('shell','pm','path','org.motoai.runtime').stdout.strip()
        if not installed.startswith('package:') or '\n' in installed: raise RuntimeError('Expected single APK')
        if adb('shell','sha256sum',installed[8:]).stdout.split()[0] != report['apk_sha256']:
            raise RuntimeError('Installed/local APK differ')
        if json.loads((reference_dir/'manifest.json').read_text())['apk_sha256'] != report['apk_sha256']:
            raise RuntimeError('Same-APK reference required')
        if model_sha256('Ornith-1.5-9B-Q4_K_M.gguf') != golden['model_sha256']:
            raise RuntimeError('Model checksum differs')
        adb('install','-t','-r',str(tests),timeout=60)
        for index,threads in enumerate(report['protocol']['order']):
            adb('shell','am','force-stop','org.motoai.runtime')
            cooling_start=time.monotonic(); cooling=[]
            while True:
                health=check_running_health(initial); cooling.append(health)
                temp=health['battery']['temperature_c']
                if temp is not None and temp<=37: break
                if time.monotonic()-cooling_start>1800: raise RuntimeError('Cooling target not reached; no condition override')
                print('COOLING',index, temp, flush=True); time.sleep(10)
            identifier='sustained-'+uuid.uuid4().hex
            arm=dict(id=identifier,threads=threads,health_before=health,cooling=cooling,passed=False)
            report['arms'].append(arm);persist()
            request=json.loads(json.dumps(spec))
            request['config'].update(load_mode='mmap',weight_buffers='native',transient_repack=False,threads=threads)
            if threads==7: request['config']['threads_batch']=4
            local=out/(identifier+'.json'); local.write_text(json.dumps(request,indent=2)+'\n')
            staging='/data/local/tmp/'+identifier+'.json'
            adb('push',str(local),staging)
            adb('shell','run-as','org.motoai.runtime','mkdir','-p','files/bench')
            adb('shell','run-as','org.motoai.runtime','cp',staging,'files/bench/'+local.name)
            adb('shell','rm','-f',staging)
            print('ARM',index,threads,identifier,flush=True)
            try:
                # Instrumentation controls the separate actual foreground service,
                # retaining its model/context across all complete requests.
                completed=adb('shell','am','instrument','-w','-r','-e','scope','sustained','-e','seconds','900',
                              '-e','runId',identifier,'-e','requestFile',local.name,
                              'org.motoai.runtime.test/org.motoai.runtime.RuntimeInstrumentation',timeout=1500)
                (out/(identifier+'-instrumentation.txt')).write_text(completed.stdout+'\n'+completed.stderr)
            finally:
                for suffix in ('.jsonl','-report.json'):
                    pulled=adb('exec-out','run-as','org.motoai.runtime','cat','files/'+identifier+suffix,check=False)
                    (out/(identifier+suffix)).write_text(pulled.stdout)
                arm['health_after']=check_running_health(initial);persist()
                adb('shell','am','force-stop','org.motoai.runtime',check=False)
            data=json.loads((out/(identifier+'-report.json')).read_text())
            if data.get('passed') is not True: raise RuntimeError('Arm failed: '+str(data.get('error')))
            rows=[json.loads(line) for line in (out/(identifier+'.jsonl')).read_text().splitlines()]
            arm['summary']=summarize(rows,request,golden,reference['result'],gates,900)
            arm['passed']=True;persist()
            print('ARM COMPLETE',index,arm['summary'],flush=True)
        arms=report['arms']; starts=[arm['health_before']['battery'] for arm in arms]
        conditions=all(comparable_power(starts[0],power) for power in starts)
        conditions &= max(p['temperature_c'] for p in starts)-min(p['temperature_c'] for p in starts)<=1
        starts_caps=[json.loads((out/(arm['id']+'.jsonl')).read_text().splitlines()[0])['telemetry']['policies'] for arm in arms]
        relevant=[{key:(value['related_cpus'],value['scaling_max_freq']) for key,value in caps.items()
                   if cpu_set(value['related_cpus']) & set(range(7))} for caps in starts_caps]
        conditions &= bool(relevant[0]) and all(cap==relevant[0] for cap in relevant)
        ratios=[]
        for a,b in ((arms[0],arms[1]),(arms[3],arms[2])):
            ratios.append({key:b['summary'][key]/a['summary'][key] for key in ('emission_tok_s','final_five_minute_emission_tok_s')})
        report.update(passed=True, matched_start_conditions=conditions, paired_ratios=ratios,
                      speed_candidate_qualified=conditions and all(value>=1.03 for ratio in ratios for value in ratio.values()))
    except Exception as error:
        report['error']=str(error)
    finally:
        adb('shell','am','force-stop','org.motoai.runtime',check=False)
        report['health_final']=read_health()
        if not report['health_final'].get('ready'): report['passed']=False
        persist();print('Results:',out.relative_to(ROOT),flush=True)
    return 0 if report['passed'] else 1


if __name__=='__main__': sys.exit(main())
