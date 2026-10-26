#!/usr/bin/env python3
"""Capture non-personal device capabilities; requires one authorized ADB device."""
import datetime
import json
import subprocess


def shell(command):
    result = subprocess.run(['adb', '-d', 'shell', command], capture_output=True,
                            text=True, timeout=30)
    return {'exit_code': result.returncode, 'stdout': result.stdout.strip(),
            'stderr': result.stderr.strip()}


commands = {
    'properties': 'for p in ro.product.model ro.soc.model ro.board.platform '
                  'ro.build.version.release ro.build.version.sdk ro.product.cpu.abilist '
                  'ro.build.fingerprint; do echo "$p=$(getprop "$p")"; done',
    'cpu': 'cat /proc/cpuinfo',
    'memory': 'cat /proc/meminfo',
    'pagesize': 'getconf PAGESIZE',
    'thermal': 'dumpsys thermalservice',
    'battery': 'dumpsys battery',
    'gpu': 'dumpsys SurfaceFlinger | grep "GLES:"',
    'public_vendor_libraries': 'cat /vendor/etc/public.libraries.txt',
    'storage': 'df -k /data',
    'online_cpus': 'cat /sys/devices/system/cpu/online',
    'power_save': 'settings get global low_power',
    'cpu_frequencies': 'for f in /sys/devices/system/cpu/cpufreq/policy*/scaling_cur_freq /sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq; do echo $f; cat $f; done',
    'memory_pressure': 'cat /proc/pressure/memory',
    'virtual_memory_counters': 'cat /proc/vmstat',
    'shell_allowed_cpus': 'cat /proc/self/status | grep Cpus_allowed_list',
}
print(json.dumps({'captured_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'scope': 'ADB shell snapshot; app access/performance checks are separate',
                  'observations': {key: shell(command) for key, command in commands.items()}},
                 indent=2))
