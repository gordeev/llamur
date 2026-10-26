"""Read-only readiness/power screening for real phone workloads; never changes battery state."""
import hashlib
import json
import re
import subprocess

MINIMUM_START_PERCENT = 20
MINIMUM_RUNNING_PERCENT = 10


class PhoneHealthFailure(RuntimeError):
    def __init__(self, health):
        self.health = health
        super().__init__('Phone health no longer permits the workload: ' + json.dumps(health, ensure_ascii=False))


def parse_battery(output):
    def number(key):
        match = re.search(r'^\s*' + re.escape(key) + r':\s*(-?\d+)\s*$', output, re.MULTILINE)
        return int(match.group(1)) if match else None
    level, scale, status = number('level'), number('scale'), number('status')
    percent = 100 * level / scale if level is not None and scale is not None and scale > 0 and 0 <= level <= scale else None
    return {'percent': percent, 'status': status, 'charging': status in (2, 5),
            'temperature_c': number('temperature') / 10 if number('temperature') is not None else None,
            'ac_connected': bool(re.search(r'^\s*AC powered:\s*true\s*$', output, re.MULTILINE)),
            'usb_connected': bool(re.search(r'^\s*USB powered:\s*true\s*$', output, re.MULTILINE))}


def comparable_power(before, after):
    levels = [snapshot.get('percent') for snapshot in (before, after)]
    return (all(value is not None and value >= MINIMUM_START_PERCENT for value in levels) and
            all(snapshot.get('status') in (2, 3, 4, 5) for snapshot in (before, after)) and
            all(before.get(key) == after.get(key) for key in ('charging', 'ac_connected', 'usb_connected')))


def read_health():
    result = {'ready': False}
    try:
        def shell(command):
            run = subprocess.run(['adb', '-d', 'shell', command], capture_output=True, text=True, timeout=10)
            if run.returncode: raise RuntimeError(run.stderr.strip() or run.stdout.strip() or 'ADB command failed')
            return run.stdout.strip()
        boot = shell('getprop sys.boot_completed')
        result['boot_completed'] = boot == '1'
        battery = shell('dumpsys battery')
        result['battery'] = parse_battery(battery)
        try:
            identity = shell('cat /proc/sys/kernel/random/boot_id')
            result['boot_id_sha256'] = hashlib.sha256(identity.encode()).hexdigest()
        except (RuntimeError, subprocess.SubprocessError) as error:
            result['boot_id_sha256'] = None
            result['boot_identity_error'] = str(error)
        result['uptime'] = float(shell('cat /proc/uptime').split()[0])
        if not result['boot_completed']: result['reason'] = 'Android has not completed boot'
        elif result['battery']['percent'] is None: result['reason'] = 'Android battery service unavailable; system may be stopping/restarting'
        elif result['battery']['percent'] < MINIMUM_START_PERCENT:
            result['reason'] = f'Battery {result["battery"]["percent"]:g}% below {MINIMUM_START_PERCENT}% start threshold; connected power alone is not charging proof'
        else: result['ready'] = True
    except (OSError, RuntimeError, subprocess.SubprocessError, IndexError, ValueError) as error:
        result['reason'] = str(error)
    return result


def require_phone_ready():
    health = read_health()
    if not health['ready']:
        raise SystemExit('Phone workload not started; no phone state changed: ' + json.dumps(health, ensure_ascii=False))
    return health


def check_running_health(initial):
    health = read_health()
    battery = health.get('battery', {}).get('percent')
    if (not health.get('boot_completed') or battery is None or battery < MINIMUM_RUNNING_PERCENT or
        (initial.get('boot_id_sha256') is not None and health.get('boot_id_sha256') != initial.get('boot_id_sha256')) or
        (initial.get('uptime') is not None and health.get('uptime') is not None and float(health['uptime']) < float(initial['uptime']))):
        raise PhoneHealthFailure(health)
    return health
