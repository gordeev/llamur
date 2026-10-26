import importlib.util
import pathlib
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('phone_model', pathlib.Path(__file__).resolve().parents[1] / 'scripts/phone_model.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PhoneModelTests(unittest.TestCase):
    def test_exec_out_missing_can_return_zero(self):
        response = subprocess.CompletedProcess([], 0, 'cat: files/models/a.gguf.shared.json: No such file or directory\n', '')
        with patch.object(module.subprocess, 'run', return_value=response):
            self.assertEqual(module.model_source('a.gguf')['path'], 'files/models/a.gguf')

    def test_shared_registry_takes_precedence(self):
        response = subprocess.CompletedProcess([], 0, '{"uri":"content://media/1","diagnostic_file_path":"/storage/emulated/0/Download/MotoAI/Models/a.gguf"}', '')
        with patch.object(module.subprocess, 'run', return_value=response):
            self.assertEqual(module.model_source('a.gguf')['storage'], 'MediaStore Downloads/MotoAI/Models')

    def test_transport_error_never_falls_back(self):
        response = subprocess.CompletedProcess([], 1, '', 'device offline')
        with patch.object(module.subprocess, 'run', return_value=response):
            with self.assertRaises(RuntimeError): module.model_source('a.gguf')

    def test_registry_path_and_filename_are_bounded(self):
        response = subprocess.CompletedProcess([], 0, '{"uri":"content://media/1","diagnostic_file_path":"/data/other/a.gguf"}', '')
        with patch.object(module.subprocess, 'run', return_value=response):
            with self.assertRaises(RuntimeError): module.model_source('a.gguf')
            with self.assertRaises(ValueError): module.model_source('../a.gguf')
