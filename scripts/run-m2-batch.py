#!/usr/bin/env python3
"""Run one serial batch, honoring an optional session stop boundary between every fixture."""
from phone_lease import acquire_phone_lease
import argparse
import datetime
import json
import pathlib
import re
import subprocess
import sys
from zoneinfo import ZoneInfo

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--cutoff', help='Optional HH:MM Europe/Belgrade stop boundary')
p.add_argument('--timeout', type=int, default=1500, help='Explicit per-trial watchdog, including long prefill')
args = p.parse_args()
if not 1 <= args.timeout <= 1800: p.error('Timeout must be1..1800 seconds')
lease = acquire_phone_lease()
if args.cutoff and not re.fullmatch(r'(?:[01][0-9]|2[0-3]):[0-5][0-9]', args.cutoff): p.error('Invalid cutoff')
# Quality and actual long-context coverage take priority over collecting more short timings.
jobs = [(model, fixture) for fixture in ['unicode-code', 'multi-turn', 'literal-control',
        'add-quality', 'binary-search-quality-2048', 'pp128-diag', 'pp512-diag', 'pp2048-diag', 'pp4096-diag', 'pp8064-diag', 'needle-7680-diag']
        for model in ['gemma', 'ornith']]
log_directory = ROOT / '.tools/m2-batch'
log_directory.mkdir(exist_ok=True)
for model, fixture in jobs:
    now = datetime.datetime.now(ZoneInfo('Europe/Belgrade'))
    if args.cutoff and (now.hour, now.minute) >= tuple(map(int, args.cutoff.split(':'))):
        print('Session cutoff: batch paused between fixtures.', flush=True)
        break
    command = [sys.executable, 'scripts/benchmark.py', model, fixture, '--repeat', '1', '--warmup', '0', '--timeout', str(args.timeout)]
    if args.cutoff: command += ['--cutoff', args.cutoff]
    run = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=args.timeout + 240, **lease.child_options())
    (log_directory / f'{model}-{fixture}.log').write_text(run.stdout + '\n' + run.stderr)
    print(run.stdout.strip(), flush=True)
    if run.returncode != 0:
        print(f'Batch stopped at failed fixture: {model}/{fixture}', flush=True)
        raise SystemExit(1)
    if 'Cutoff reached: no new series started.' in run.stdout:
        print('Batch paused at explicit cutoff.', flush=True)
        break
    match = re.search(r'^Series: (bench/results/[^\n]+)$', run.stdout, re.MULTILINE)
    if not match: raise RuntimeError('Benchmark returned no series artifact')
    series = ROOT / match.group(1)
    summary = json.loads((series / 'summary.json').read_text())
    if summary.get('paused_at_cutoff'):
        print('Batch paused at explicit cutoff; series is incomplete.', flush=True)
        break
    # benchmark.py now enforces final-answer/EOS quality before marking a
    # declared quality/retrieval trial passed. Do not rerun or override it here.
    if not summary.get('accepted'): raise RuntimeError('Benchmark did not accept its requested trials')
