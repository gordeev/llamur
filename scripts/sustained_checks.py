"""Sustained public-fixture analysis; natural OEM adaptation is an outcome, not pruned."""
import math
from decode_checks import check_scheduling, decode_metrics
from weight_buffer_checks import check_result, compare_results


def summarize(rows, spec, golden, reference, gates, seconds):
    loads = [row for row in rows if row['kind'] == 'load']
    requests = [row for row in rows if row['kind'] == 'request']
    if len(loads) != 1 or not requests or not requests[0]['warmup'] or any(r['warmup'] for r in requests[1:]):
        raise ValueError('Exactly one load/excluded warmup required')
    measured = requests[1:]
    if len(measured) < 10: raise ValueError('Too few measured complete requests')
    start, end = measured[0]['started_ns'], measured[-1]['ended_ns']
    if end - start < seconds * 1e9: raise ValueError('Sustained interval incomplete')
    # Native intervals start at the first sampled token. Keep only complete
    # emission intervals whose two endpoints fall inside the final five minutes.
    cutoff = end - 300 * 1e9
    count = elapsed_us = tail_count = tail_us = 0
    pid = loads[0]['snapshot']['pid']
    allowed = None
    memory = []
    previous_end = 0
    power = None
    for index, row in enumerate(requests):
        if row['started_ns'] < previous_end or row['index'] != max(0, index-1):
            raise ValueError('Requests overlap or indices are incomplete')
        previous_end = row['ended_ns']
        data = row['snapshot']
        if data['pid'] != pid or row['ended_ns'] <= row['started_ns']:
            raise ValueError('Process changed or invalid clock interval')
        result = check_result(data, golden, 'native', spec['request'], transient_repack=False)
        cpus = check_scheduling(data, spec['config'])
        if allowed is not None and allowed != cpus: raise ValueError('Allowed CPUs changed')
        allowed = cpus
        comparison = compare_results(reference, result, gates, require_ids=True)
        if not comparison['numeric_passed']: raise ValueError('Quality failed')
        decode_metrics(result)
        if data.get('completed_interactive') is not True or data.get('completed_importance') != 100:
            raise ValueError('Screen/foreground conditions changed')
        for telemetry in (row['before'], row['after']):
            if telemetry['interactive'] is not True or telemetry['thermal_status'] is None or telemetry['thermal_status'] >= 3:
                raise ValueError('Screen off or severe thermal pressure')
            if (telemetry['battery_scale'] <= 0 or telemetry['battery_level'] * 100 < 10 * telemetry['battery_scale']):
                raise ValueError('Battery below threshold')
        current_power = [(item['battery_status'], item['plugged']) for item in (row['before'], row['after'])]
        if power is None: power = current_power[0]
        if any(item != power for item in current_power): raise ValueError('Power state changed inside sustained arm')
        if row['warmup']: continue
        memory.append(data['completed_pss_kib'])
        t = row['started_ns'] + result['first_target_token_us'] * 1000
        for interval in result['inter_token_us']:
            if not math.isfinite(interval) or interval <= 0: raise ValueError('Invalid token interval')
            next_t = t + interval * 1000
            count += 1; elapsed_us += interval
            if t >= cutoff and next_t <= end:
                tail_count += 1; tail_us += interval
            t = next_t
    if tail_count < 20 or tail_us <= 0: raise ValueError('Missing final-five-minute decode coverage')
    return dict(passed=True, requests=len(measured), allowed_cpus=allowed, pid=pid,
                duration_s=(end-start)/1e9, emission_tok_s=count*1e6/elapsed_us,
                final_five_minute_emission_tok_s=tail_count*1e6/tail_us,
                decode_interval_s=elapsed_us/1e6, final_five_decode_interval_s=tail_us/1e6,
                pss_first_kib=memory[0], pss_last_kib=memory[-1], pss_peak_kib=max(memory),
                scope='Repeated same public 128-token request, one loaded model; no whole-model/context/battery-release qualification')
