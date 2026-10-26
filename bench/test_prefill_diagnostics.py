import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location('prefill_diagnostics', pathlib.Path(__file__).resolve().parents[1]/'scripts/prefill_diagnostics.py')
diagnostics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnostics)


class PrefillDiagnosticsTests(unittest.TestCase):
    def row(self, token, seconds, cpu_ms):
        return {'phase':'prefill','completed':token,'service_elapsed_us':seconds*1000000,
                'cpu_ms':cpu_ms,'process_status':'VmSwap:\t 16 kB'}

    def test_stat_command_parentheses_and_units(self):
        self.assertEqual(diagnostics.faults('123 (a tricky) name) S 0 0 0 0 0 0 17 0 3 0'),
                         {'minor_faults':17,'major_faults':3})
        self.assertEqual(diagnostics.key_values('VmRSS: 12 kB\nread_bytes: 55'),{'VmRSS':12288,'read_bytes':55})

    def test_missing_counters_and_incomplete_boundary_remain_explicit(self):
        result=diagnostics.prefill_observations([self.row(0,0,0),self.row(512,10,39000),self.row(1024,21,82500)])
        self.assertEqual(result['observed_to_token'],1024)
        self.assertAlmostEqual(result['process_cpu_seconds_per_wall_second'],82.5/21)
        self.assertIsNone(result['major_faults'])
        self.assertIsNone(result['read_bytes'])
        self.assertEqual(result['sampled_peak_process_swap_bytes'],16384)

    def test_duplicate_boundary_and_counter_reset(self):
        result=diagnostics.prefill_observations([self.row(0,0,0),self.row(512,10,39000),self.row(512,11,40000)])
        self.assertEqual(len(result['intervals']),1)
        self.assertEqual(result['observed_wall_s'],11)
        with self.assertRaises(ValueError):
            diagnostics.prefill_observations([self.row(0,10,39000),self.row(512,9,40000)])
