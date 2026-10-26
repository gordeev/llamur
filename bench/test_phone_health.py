import importlib.util
import pathlib
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('phone_health', pathlib.Path(__file__).resolve().parents[1] / 'scripts/phone_health.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PhoneHealthTests(unittest.TestCase):
    def test_charge_limit_transition_excludes_speed_comparison(self):
        before = module.parse_battery('level: 71\nscale: 100\nstatus: 2\nAC powered: true\n')
        after = module.parse_battery('level: 81\nscale: 100\nstatus: 4\nAC powered: true\n')
        self.assertFalse(module.comparable_power(before, after))
        self.assertTrue(module.comparable_power(after, after))

    def test_power_source_change_and_unknown_or_low_battery_exclude_speed(self):
        before = module.parse_battery('level: 50\nscale: 100\nstatus: 3\nAC powered: false\n')
        for output in ['level: 60\nscale: 100\nstatus: 3\nAC powered: true\n',
                       'level: 4\nscale: 100\nstatus: 3\n', 'level: 50\nscale: 100\n', "Can't find service: battery"]:
            self.assertFalse(module.comparable_power(before, module.parse_battery(output)))

    def test_connected_power_is_not_charging_proof(self):
        battery = module.parse_battery('AC powered: true\n USB powered: false\n level: 4\n scale: 100\n status: 4\n temperature: 360\n')
        self.assertEqual(battery['percent'], 4)
        self.assertTrue(battery['ac_connected'])
        self.assertFalse(battery['charging'])

    def test_missing_service_is_unknown_not_full(self):
        self.assertIsNone(module.parse_battery("Can't find service: battery")['percent'])

    def test_low_battery_refuses_before_any_mutation(self):
        responses = [subprocess.CompletedProcess([], 0, value, '') for value in
                     ['1', 'level: 4\nscale: 100\nstatus: 4', 'random-boot-id', '100.0 90.0']]
        with patch.object(module.subprocess, 'run', side_effect=responses) as run:
            with self.assertRaises(SystemExit): module.require_phone_ready()
            self.assertEqual([call.args[0][3] for call in run.call_args_list],
                             ['getprop sys.boot_completed', 'dumpsys battery', 'cat /proc/sys/kernel/random/boot_id', 'cat /proc/uptime'])

    def test_boot_not_ready_and_transport_failure(self):
        with patch.object(module.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'no devices found')):
            self.assertFalse(module.read_health()['ready'])

    def test_ready_snapshot(self):
        responses = [subprocess.CompletedProcess([], 0, value, '') for value in
                     ['1', 'level: 70\nscale: 100\nstatus: 2', 'random-boot-id', '100.0 90.0']]
        with patch.object(module.subprocess, 'run', side_effect=responses):
            self.assertTrue(module.require_phone_ready()['ready'])

    def test_stop_running_before_critical_battery_or_reboot(self):
        initial = {'boot_id_sha256': 'boot-one'}
        for state in [{'boot_completed': True, 'battery': {'percent': 4}, 'boot_id_sha256': 'boot-one'},
                      {'boot_completed': True, 'battery': {'percent': 60}, 'boot_id_sha256': 'boot-two'},
                      {'boot_completed': False}]:
            with patch.object(module, 'read_health', return_value=state):
                with self.assertRaises(module.PhoneHealthFailure): module.check_running_health(initial)
