import importlib.util
import struct
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('inspect_gguf',
    Path(__file__).resolve().parents[1] / 'scripts/inspect-gguf.py')
gguf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gguf)


def string(value):
    data = value.encode()
    return struct.pack('<Q', len(data)) + data


class MetadataInspection(unittest.TestCase):
    def inspect(self, content):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'model.gguf'
            path.write_bytes(content)
            return gguf.inspect(path)

    def test_metadata_without_tensor_payload(self):
        content = b'GGUF' + struct.pack('<IQQ', 3, 1, 1)
        content += string('general.architecture') + struct.pack('<I', 8) + string('gemma4')
        content += string('weight') + struct.pack('<IQIQ', 1, 256, 12, 0)
        result = self.inspect(content)
        self.assertEqual(result['metadata']['general.architecture'], 'gemma4')
        self.assertEqual(result['tensor_type_counts'], {12: 1})
        self.assertFalse(result['tensor_data_verified'])

    def test_truncated_header_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Truncated'):
            self.inspect(b'GGUF\x03')

    def test_invalid_magic_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Not a'):
            self.inspect(b'NOPE')

    def test_huge_string_length_rejected_before_allocation(self):
        content = b'GGUF' + struct.pack('<IQQQ', 3, 0, 1, 2**63)
        with self.assertRaisesRegex(ValueError, 'limit'):
            self.inspect(content)

    def test_duplicate_keys_rejected(self):
        item = string('general.name') + struct.pack('<I', 8) + string('model')
        content = b'GGUF' + struct.pack('<IQQ', 3, 0, 2) + item + item
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.inspect(content)


if __name__ == '__main__':
    unittest.main()
