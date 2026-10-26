"""CPU policy boundary snapshots. Equal caps do not prove equal running clocks."""
import re

POLICY_COMMAND = 'for d in /sys/devices/system/cpu/cpufreq/policy*; do echo "$d"; cat "$d/related_cpus"; cat "$d/scaling_max_freq"; done'


def cpu_set(value):
    cpus = set()
    for token in re.split(r'[\s,]+', value.strip()):
        if not token: continue
        if not re.fullmatch(r'\d+(?:-\d+)?', token): raise ValueError('Invalid CPU list')
        pair = [int(v) for v in token.split('-')]
        start, end = pair[0], pair[-1]
        if not 0 <= start <= end < 1024: raise ValueError('Invalid CPU range')
        cpus.update(range(start, end + 1))
    return cpus


def parse_policies(text):
    pieces = re.split(r'(?m)^/sys/devices/system/cpu/cpufreq/policy(\d+)\s*$', text)
    result = {}
    for i in range(1, len(pieces), 2):
        policy = pieces[i]
        if policy in result: raise ValueError('Duplicate CPU policy')
        lines = pieces[i + 1].strip().splitlines()
        try:
            cpus = sorted(cpu_set(lines[0]))
            cap = int(lines[1])
            if not cpus or cap <= 0: raise ValueError('Invalid policy')
            result[policy] = {'cpus': cpus, 'max_khz': cap}
        except (IndexError, ValueError):
            result[policy] = {'cpus': None, 'max_khz': None}
    return result


def parse_legacy_caps(text):
    result = {}
    for policy, value in re.findall(r'/policy(\d+)/scaling_max_freq\r?\n([^\r\n]*)', text):
        if policy in result: raise ValueError('Duplicate CPU cap')
        result[policy] = int(value) if value.isdigit() and int(value) > 0 else None
    return result


def screen_caps(before, after, used_cpus):
    used = set(used_cpus)
    covered = set()
    reasons = []
    selected = []
    if not used: reasons.append('Missing allowed CPU set')
    for policy, a in before.items():
        if a['cpus'] is None: continue
        members = set(a['cpus'])
        if not members & used: continue
        covered.update(members & used)
        selected.append(policy)
        b = after.get(policy)
        if b is None or b['cpus'] != a['cpus'] or a['max_khz'] is None or b['max_khz'] is None:
            reasons.append('Missing/changed policy topology or cap: ' + policy)
        elif a['max_khz'] != b['max_khz']:
            reasons.append('Frequency ceiling changed: ' + policy)
    if covered != used: reasons.append('CPU policy coverage incomplete')
    return {'boundary_caps_comparable': not reasons, 'used_cpus': sorted(used),
            'selected_policies': selected, 'reasons': reasons,
            'scope': 'Boundary caps only, not actual clock residency or proof of unchanged caps inside the request.'}
