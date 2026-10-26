import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'scripts'))
from frequency_caps import cpu_set, parse_policies, parse_legacy_caps, screen_caps


class FrequencyCaps(unittest.TestCase):
    def test_cpu_lists_and_bad_ranges(self):
        self.assertEqual(cpu_set('0-2, 4 6'), {0, 1, 2, 4, 6})
        for value in ('3-1', '-1', '0-1024', 'unknown'):
            with self.assertRaises(ValueError): cpu_set(value)

    def test_policy_reads_and_missing_values(self):
        r = parse_policies('/sys/devices/system/cpu/cpufreq/policy3\n3 4 5 6\n1286400\n')
        self.assertEqual(r['3'], {'cpus': [3, 4, 5, 6], 'max_khz': 1286400})
        self.assertIsNone(parse_policies('/sys/devices/system/cpu/cpufreq/policy3\n')['3']['max_khz'])
        self.assertIsNone(parse_legacy_caps('/policy3/scaling_max_freq\nPermission denied\n')['3'])

    def test_unused_policy_does_not_invalidate_allowed_cpus(self):
        a = {'3': {'cpus': [3, 4], 'max_khz': 1000}, '7': {'cpus': [7], 'max_khz': 3000}}
        b = {'3': {'cpus': [3, 4], 'max_khz': 1000}, '7': {'cpus': [7], 'max_khz': 2000}}
        self.assertTrue(screen_caps(a, b, {3, 4})['boundary_caps_comparable'])
        b['3']['max_khz'] = 900
        self.assertFalse(screen_caps(a, b, {3, 4})['boundary_caps_comparable'])

    def test_unknown_topology_or_cap_never_equals_zero(self):
        a = {'0': {'cpus': [0, 1], 'max_khz': None}}
        self.assertFalse(screen_caps(a, a, {0, 1})['boundary_caps_comparable'])
        self.assertFalse(screen_caps({}, {}, {0})['boundary_caps_comparable'])


if __name__ == '__main__': unittest.main()
