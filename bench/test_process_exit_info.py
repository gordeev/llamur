import pathlib
import sys
import types
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'scripts'))
from process_exit_info import collect_exit_info, matching_exit_record


def record(pid, process='org.motoai.runtime:inference', reason='3 (LOW_MEMORY)'):
    return f'  ApplicationExitInfo #0:\n timestamp=2026-10-25 19:00:21 pid={pid} realUid=10778\n process={process} reason={reason} status=0\n'


class ProcessExitInfoTests(unittest.TestCase):
    def test_requires_exact_pid_and_process(self):
        text = record(114210) + record(11421, process='org.motoai.runtime') + record(11421)
        match = matching_exit_record(text, 11421, 'org.motoai.runtime:inference')
        self.assertEqual(match['reason_code'], 3)
        self.assertEqual(match['reason'], 'LOW_MEMORY')
        self.assertIsNone(matching_exit_record(record(114210), 11421, 'org.motoai.runtime:inference'))
        self.assertIsNone(matching_exit_record(text, None, 'org.motoai.runtime:inference'))

    def test_delayed_record_preserves_each_snapshot(self):
        snapshots = iter([record(10238, reason='10 (USER REQUESTED)'), record(11421)])
        saved, delays = [], []
        result = collect_exit_info(lambda: types.SimpleNamespace(returncode=0, stdout=next(snapshots)),
                                   lambda i, r: saved.append((i, r.stdout)), 11421,
                                   'org.motoai.runtime:inference', sleep=delays.append)
        self.assertEqual(result['reason_code'], 3)
        self.assertEqual(len(saved), 2)
        self.assertEqual(delays, [0.5])

    def test_missing_record_is_unknown_not_low_memory(self):
        saved = []
        result = collect_exit_info(lambda: types.SimpleNamespace(returncode=1, stdout=''),
                                   lambda i, r: saved.append(i), 11421,
                                   'org.motoai.runtime:inference', attempts=3, sleep=lambda _: None)
        self.assertIsNone(result)
        self.assertEqual(saved, [0, 1, 2])
