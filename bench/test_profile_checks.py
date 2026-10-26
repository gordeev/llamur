import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'scripts'))
from profile_checks import elf_build_id, check_recorded_build_ids, record_statistics


class ProfileChecks(unittest.TestCase):
    def test_missing_or_ambiguous_elf_identity_rejected(self):
        self.assertEqual(elf_build_id('Build ID: ABC123'), 'abc123')
        for notes in ('no notes', 'Build ID: ab\nBuild ID: cd'):
            with self.assertRaises(ValueError):
                elf_build_id(notes)

    def test_sampled_backend_must_match_verified_apk(self):
        libraries = {'libggml-cpu-test.so': {'build_id': 'ab' * 20}}
        record = 'build_id 0x' + 'ab' * 20 + '\n    filename /data/app/test/lib/arm64/libggml-cpu-test.so\n'
        self.assertEqual(list(check_recorded_build_ids(record, libraries, '/data/app/test')),
                         ['libggml-cpu-test.so'])
        for invalid in (record.replace('0xab', '0xff', 1),
                        record.replace('/test/', '/other/'),
                        record.replace('libggml-cpu-test.so', 'libunknown.so')):
            with self.assertRaises(ValueError):
                check_recorded_build_ids(invalid, libraries, '/data/app/test')

    def test_loss_accounting_cannot_silently_default_to_zero(self):
        record = 'record_stat = sample_record_count=500,kernelspace_lost_records=1,userspace_lost_samples=2,userspace_lost_non_samples=3'
        values = record_statistics(record)
        self.assertEqual(values['sample_record_count'], 500)
        self.assertEqual(values['kernelspace_lost_records'], 1)
        self.assertEqual(values['userspace_lost_samples'], 2)
        for invalid in ('', 'record_stat = sample_record_count=500'):
            with self.assertRaises(ValueError):
                record_statistics(invalid)


if __name__ == '__main__':
    unittest.main()
