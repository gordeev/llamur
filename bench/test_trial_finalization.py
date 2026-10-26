import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'scripts'))
from trial_finalization import finalize_trial


class TrialFinalizationTests(unittest.TestCase):
    def test_disconnected_diagnostics_preserve_workload_failure_and_attempt_stop(self):
        attempts = []
        def disconnected():
            raise subprocess.TimeoutExpired('device-after', 90)
        def wake_failed():
            attempts.append('wake')
            raise OSError('device gone')
        def stop():
            attempts.append('stop')
            return subprocess.CompletedProcess('adb', 1)
        with tempfile.TemporaryDirectory() as directory:
            out = pathlib.Path(directory)
            with self.assertRaisesRegex(ValueError, 'original process death'):
                try:
                    raise ValueError('original process death')
                finally:
                    finalize_trial(out, [('device-after', disconnected), ('wake-screen', wake_failed), ('stop-motoai', stop)],
                                   primary_error_active=sys.exc_info()[0] is not None)
            self.assertEqual(attempts, ['wake', 'stop'])
            self.assertEqual([row['action'] for row in json.loads((out / 'finalization-errors.json').read_text())],
                             ['device-after', 'wake-screen', 'stop-motoai'])

    def test_cleanup_failure_cannot_turn_collected_result_into_a_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, 'finalization failed'):
                finalize_trial(pathlib.Path(directory), [('stop-motoai', lambda: subprocess.CompletedProcess('adb', 1))])

    def test_success_does_not_create_an_error_report(self):
        with tempfile.TemporaryDirectory() as directory:
            out = pathlib.Path(directory)
            self.assertEqual(finalize_trial(out, [('stop-motoai', lambda: subprocess.CompletedProcess('adb', 0))]), [])
            self.assertFalse((out / 'finalization-errors.json').exists())
