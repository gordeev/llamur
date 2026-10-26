"""Interpret optional chunk-boundary counters; missing observations are never zero."""
import re


def key_values(text):
    values = {}
    for line in (text or '').splitlines():
        match = re.fullmatch(r'([A-Za-z_]+):\s*(\d+)(?:\s+(kB))?\s*', line)
        if match:
            values[match[1]] = int(match[2]) * (1024 if match[3] else 1)
    return values


def faults(text):
    # comm can contain spaces/parentheses. The fields after its last ')' start
    # with state (field3); minflt/majflt are fields10/12, respectively.
    if not text or ')' not in text:
        return {}
    fields = text.rsplit(')', 1)[1].split()
    try:
        return {'minor_faults': int(fields[7]), 'major_faults': int(fields[9])}
    except (IndexError, ValueError):
        return {}


def prefill_observations(rows):
    snapshots = {}
    all_memory = []
    for row in rows:
        all_memory.append(key_values(row.get('process_status')))
        if row.get('phase') == 'prefill' and isinstance(row.get('completed'), int) and row.get('cpu_ms') is not None:
            snapshots[row['completed']] = row
    ordered = [snapshots[key] for key in sorted(snapshots)]
    intervals = []
    for a, b in zip(ordered, ordered[1:]):
        wall_us = b['service_elapsed_us'] - a['service_elapsed_us']
        cpu_ms = b['cpu_ms'] - a['cpu_ms']
        if wall_us <= 0 or cpu_ms < 0:
            raise ValueError('Non-monotonic prefill counters')
        row = {'from_token': a['completed'], 'to_token': b['completed'],
               'wall_s': wall_us / 1e6, 'process_cpu_s': cpu_ms / 1000,
               'process_cpu_seconds_per_wall_second': cpu_ms * 1000 / wall_us}
        for name, parser, key in [('minor_faults', faults, 'process_stat'), ('major_faults', faults, 'process_stat'),
                                  ('read_bytes', key_values, 'process_io')]:
            start, end = parser(a.get(key)), parser(b.get(key))
            if name in start and name in end:
                delta = end[name] - start[name]
                if delta < 0: raise ValueError('Reset process counters: ' + name)
                row[name] = delta
        row['end_process_memory_bytes'] = key_values(b.get('process_status'))
        row['end_system_memory_bytes'] = key_values(b.get('system_memory'))
        intervals.append(row)
    wall = sum(row['wall_s'] for row in intervals)
    cpu = sum(row['process_cpu_s'] for row in intervals)
    summary = {'scope': 'Observed prefill intervals only; boundary polling can miss the final chunk. CPU time includes busy-wait/barriers and does not identify the hot operation. VmSwap is process accounting, not measured disk-wait time.',
               'observed_from_token': ordered[0]['completed'] if ordered else None,
               'observed_to_token': ordered[-1]['completed'] if ordered else None,
               'observed_wall_s': wall, 'observed_process_cpu_s': cpu,
               'process_cpu_seconds_per_wall_second': cpu / wall if wall else None,
               'intervals': intervals}
    for key in ('minor_faults','major_faults','read_bytes'):
        summary[key] = sum(row[key] for row in intervals) if intervals and all(key in row for row in intervals) else None
    swaps = [item['VmSwap'] for item in all_memory if 'VmSwap' in item]
    summary['sampled_peak_process_swap_bytes'] = max(swaps) if swaps else None
    return summary
