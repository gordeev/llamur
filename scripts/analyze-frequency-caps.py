#!/usr/bin/env python3
"""Post-hoc ceiling audit; preserve original trials and statistics, no subset wins."""
import argparse
import hashlib
import json
import pathlib
import re
from frequency_caps import cpu_set, parse_legacy_caps, screen_caps

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('reports', type=pathlib.Path, nargs='+')
p.add_argument('--topology', type=pathlib.Path, default=ROOT/'bench/cpu-policy-topology-20261025.json')
p.add_argument('--output', type=pathlib.Path, required=True)
args = p.parse_args()
if args.output.exists(): raise RuntimeError('Preserve existing audits; select another output')
topology = json.loads(args.topology.read_text())
boot = topology['health'].get('boot_id_sha256')
out = {'passed': True, 'scope': 'Post-hoc boundary frequency-cap audit, not a newly preregistered speed test. No trials removed or ratios recomputed after filtering. Equal boundary caps do not prove equal clocks or unchanged caps inside a request.',
       'topology': str(args.topology), 'topology_sha256': hashlib.sha256(args.topology.read_bytes()).hexdigest(), 'reports': []}
for path in args.reports:
    data = json.loads(path.read_text())
    if data.get('passed') is not True: raise RuntimeError('Only completed passing reports can be audited')
    entry = {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'apk_sha256': data['apk_sha256'], 'pairs': []}
    for pair in data['pairs']:
        snapshots = []
        used_sets = []
        reasons = []
        row = {'warmup': pair['warmup'], 'order': pair['order'], 'trials': {}}
        for role, trial in pair['trials'].items():
            artifact = (ROOT/trial['artifact']).resolve()
            if not artifact.is_relative_to(ROOT/'bench/results'): raise RuntimeError('Artifact outside results')
            manifest = json.loads((artifact/'manifest.json').read_text())
            if manifest['apk_sha256'] != data['apk_sha256']: raise RuntimeError('APK identity mismatch')
            if boot is None or manifest.get('device_health_before', {}).get('boot_id_sha256') != boot:
                reasons.append('Topology/manifest boot not confirmed equal')
            status = trial.get('process_status')
            status_source = 'comparison trial'
            if not status:
                result_path = artifact/'result.json'
                status = json.loads(result_path.read_text()).get('process_status', '')
                status_source = 'preserved result.json; older comparison omitted this field'
            match = re.search(r'^Cpus_allowed_list:\s*([^\n]+)', status, re.MULTILINE)
            used = cpu_set(match[1]) if match else set()
            used_sets.append(used)
            item = {'artifact': trial['artifact'], 'allowed_cpus': sorted(used), 'allowed_cpus_source': status_source}
            for when in ('before', 'after'):
                device_path = artifact/('device-'+when+'.json')
                device = json.loads(device_path.read_text())['observations']['cpu_frequencies']
                caps = parse_legacy_caps(device['stdout']) if device['exit_code'] == 0 else {}
                snapshot = {key: {'cpus': value['cpus'], 'max_khz': caps.get(key)} for key, value in topology['policies'].items()}
                snapshots.append(snapshot)
                item[when] = caps
                item[when+'_device_sha256'] = hashlib.sha256(device_path.read_bytes()).hexdigest()
            row['trials'][role] = item
        if not used_sets or any(used != used_sets[0] for used in used_sets): reasons.append('Allowed CPU sets differ')
        if used_sets and snapshots:
            for snapshot in snapshots:
                reasons += screen_caps(snapshots[0], snapshot, used_sets[0])['reasons']
        else: reasons.append('Missing CPU snapshots')
        row['boundary_caps_comparable'] = not reasons
        row['reasons'] = sorted(set(reasons))
        entry['pairs'].append(row)
    measured = [r for r in entry['pairs'] if not r['warmup']]
    entry['measured_pairs'] = len(measured)
    entry['measured_pairs_with_equal_boundary_caps'] = sum(r['boundary_caps_comparable'] for r in measured)
    entry['all_measured_boundary_caps_comparable'] = bool(measured) and all(r['boundary_caps_comparable'] for r in measured)
    out['reports'].append(entry)
args.output.write_text(json.dumps(out, indent=2)+'\n')
print('Frequency audit:', args.output)
for entry in out['reports']: print(entry['path'], entry['measured_pairs_with_equal_boundary_caps'], '/', entry['measured_pairs'])
