import importlib.util
import os
import pathlib
import subprocess
import sys
import tempfile
import time
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1] / 'scripts'
spec = importlib.util.spec_from_file_location('phone_lease', SCRIPTS / 'phone_lease.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
CHILD = '''
import pathlib,sys,time
sys.path.insert(0,sys.argv[1])
from phone_lease import PhoneLease,PhoneBusy
try: lease=PhoneLease(pathlib.Path(sys.argv[2]))
except PhoneBusy: raise SystemExit(23)
print('acquired',flush=True)
time.sleep(float(sys.argv[3]))
'''


class PhoneLeaseTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = pathlib.Path(self.directory.name) / 'lease'

    def tearDown(self):
        self.directory.cleanup()

    def child(self, **options):
        return subprocess.run([sys.executable, '-c', CHILD, str(SCRIPTS), str(self.path), '0'],
                              capture_output=True, text=True, timeout=5, **options)

    def test_competitor_rejected_before_work(self):
        lease = module.PhoneLease(self.path)
        try:
            result = self.child()
            self.assertEqual(result.returncode, 23)
            self.assertEqual(result.stdout, '')
        finally: lease.close()
        self.assertEqual(self.child().returncode, 0)

    def test_inherited_child_does_not_unlock_parent(self):
        lease = module.PhoneLease(self.path)
        try:
            self.assertEqual(self.child(**lease.child_options()).returncode, 0)
            self.assertEqual(self.child().returncode, 23)
        finally: lease.close()

    def test_child_protects_after_parent_closes(self):
        lease = module.PhoneLease(self.path)
        process = subprocess.Popen([sys.executable, '-c', CHILD, str(SCRIPTS), str(self.path), '0.4'],
                                   stdout=subprocess.PIPE, text=True, **lease.child_options())
        self.assertEqual(process.stdout.readline().strip(), 'acquired')
        lease.close()
        self.assertEqual(self.child().returncode, 23)
        process.wait(timeout=5)
        process.stdout.close()
        self.assertEqual(self.child().returncode, 0)

    def test_invalid_inherited_descriptor_rejected(self):
        self.path.touch()
        environment = dict(os.environ, MOTOAI_PHONE_LEASE_FD='99999', MOTOAI_PHONE_LEASE_PATH=str(self.path))
        self.assertEqual(self.child(env=environment).returncode, 23)
