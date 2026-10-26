import importlib.util
import hashlib
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('fetch_model',
    Path(__file__).resolve().parents[1] / 'scripts/fetch-model.py')
fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch)


class ModelIntegrity(unittest.TestCase):
    def test_correct_bytes_accepted_and_corruption_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'model.gguf'
            path.write_bytes(b'GGUF-reference')
            model = {'bytes': path.stat().st_size,
                     'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
            fetch.verify(path, model)
            path.write_bytes(b'GGUF-corrupted')
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                fetch.verify(path, model)

    def test_partial_file_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'model.gguf.part'
            path.write_bytes(b'GGUF')
            with self.assertRaisesRegex(ValueError, 'size'):
                fetch.verify(path, {'bytes': 100, 'sha256': 'unused'})
