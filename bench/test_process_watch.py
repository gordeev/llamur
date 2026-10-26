import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'scripts'))
from process_watch import ProcessWatch, HarnessProcessDied


class ProcessWatchTests(unittest.TestCase):
    def test_wait_for_initial_process_then_accept_same_process(self):
        watch = ProcessWatch()
        self.assertIsNone(watch.observe(''))
        self.assertEqual(watch.observe('123\n'), 123)
        self.assertEqual(watch.observe('123\n'), 123)

    def test_death_is_failure_even_if_service_restarts(self):
        for replacement in ('', '124\n'):
            with self.subTest(replacement=replacement):
                watch = ProcessWatch()
                watch.observe('123\n')
                with self.assertRaises(HarnessProcessDied): watch.observe(replacement)

    def test_ambiguous_or_invalid_identity_is_not_accepted(self):
        for output in ('123 124', 'permission denied'):
            with self.assertRaises(RuntimeError): ProcessWatch().observe(output)
