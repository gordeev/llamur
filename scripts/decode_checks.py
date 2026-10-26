"""Fail-closed CPU scheduling and paired decode evidence, independent of runner I/O."""
import math
import statistics
from phone_health import comparable_power
from frequency_caps import screen_caps


def check_scheduling(data, config):
    load = data['load']
    threads = config['threads']
    batch = config.get('threads_batch', 0) or threads
    mask = config.get('cpu_affinity', [])
    explicit = bool(config.get('threads_batch', 0) or mask)
    if (load.get('threads') != threads or load.get('threads_batch') != batch or
            load.get('cpu_affinity') != mask or load.get('explicit_threadpools') is not explicit):
        raise ValueError('Runtime scheduling configuration differs')
    evidence = data['result']['cpu_scheduling']
    allowed = evidence['caller_allowed_cpus']
    rows = evidence['threads']
    expected = 1 + ((threads - 1 + (batch - 1 if threads != batch else 0)) if explicit else 0)
    if (not allowed or len(set(allowed)) != len(allowed) or len(rows) != expected or
            len({row['tid'] for row in rows}) != len(rows) or not set(mask) <= set(allowed)):
        raise ValueError('CPU caller/worker evidence missing or ambiguous')
    for row in rows:
        if not row['cpus'] or set(row['cpus']) != set(mask or allowed):
            raise ValueError('Observed CPU affinity differs')
    return allowed


def decode_metrics(result):
    count = result['generated_tokens']
    perf = result['backend_perf']
    intervals = result['inter_token_us']
    if (count < 3 or len(intervals) != count - 1 or perf['decode_evals'] != count - 1 or
            any(not math.isfinite(x) or x <= 0 for x in intervals) or perf['decode_ms'] <= 0):
        raise ValueError('Incomplete decode work or emission timing')
    half = len(intervals) // 2
    return dict(backend_decode_tok_s=perf['decode_evals'] * 1000 / perf['decode_ms'],
                emission_tok_s=len(intervals) * 1e6 / sum(intervals),
                first_half_emission_tok_s=half * 1e6 / sum(intervals[:half]),
                second_half_emission_tok_s=(len(intervals)-half) * 1e6 / sum(intervals[half:]),
                median_inter_token_us=statistics.median(intervals),
                maximum_inter_token_us=max(intervals), prefill_us=result['prefill_us'],
                ttft_us=result['first_target_token_us'])


def screen_pair(a, b):
    reasons = []
    if a['allowed_cpus'] != b['allowed_cpus']: reasons.append('Caller allowed CPUs differ')
    if not all(comparable_power(row['power_before'], row['power_after']) for row in (a,b)) or not comparable_power(a['power_before'], b['power_after']):
        reasons.append('Power state differs')
    temps = [row['power_before']['temperature_c'] for row in (a,b)]
    if None in temps or abs(temps[0]-temps[1]) > 2: reasons.append('Starting battery temperatures differ by >2C')
    if any(row.get('thermal_status') is None or row['thermal_status'] >= 2 for row in (a,b)):
        reasons.append('Thermal pressure or unknown status')
    for key in ('completed_interactive', 'completed_importance'):
        if a.get(key) is None or a[key] != b.get(key): reasons.append('Foreground state differs: '+key)
    snapshots = [*a['frequency_snapshots'], *b['frequency_snapshots']]
    frequencies = [screen_caps(snapshots[0], snapshot, set(a['used_cpus']) | set(b['used_cpus'])) for snapshot in snapshots]
    if not all(row['boundary_caps_comparable'] for row in frequencies): reasons.append('Relevant boundary CPU frequency caps differ or unavailable')
    return {'passed': not reasons, 'reasons': reasons, 'frequency_screens': frequencies,
            'scope': 'Boundary conditions only; no proof of constant actual clock residency or cold cache'}
