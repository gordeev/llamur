"""Exclusive project phone workload lease, explicitly inherited by child runners."""
import atexit
import fcntl
import json
import os
import pathlib
import sys

LOCK = pathlib.Path(__file__).resolve().parents[1] / '.tools/phone-workload.lock'
FD_ENV = 'MOTOAI_PHONE_LEASE_FD'
PATH_ENV = 'MOTOAI_PHONE_LEASE_PATH'


class PhoneBusy(RuntimeError):
    pass


class PhoneLease:
    def __init__(self, path=LOCK):
        self.path = pathlib.Path(path).resolve()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.fd = -1
        inherited = os.environ.get(FD_ENV)
        if inherited is not None:
            try:
                fd = int(inherited)
                actual, expected = os.fstat(fd), self.path.stat()
                if (actual.st_dev, actual.st_ino) != (expected.st_dev, expected.st_ino) or os.environ.get(PATH_ENV) != str(self.path):
                    raise ValueError('Wrong lease file')
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.fd = fd
            except (OSError, ValueError) as error:
                raise PhoneBusy('Invalid inherited phone lease; no phone state changed') from error
        else:
            fd = os.open(self.path, os.O_RDWR | os.O_CREAT, 0o600)
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                owner = os.read(fd, 512).decode(errors='replace')
                os.close(fd)
                raise PhoneBusy('Another phone workload is active; no phone state changed. Owner: ' + owner) from error
            self.fd = fd
            os.ftruncate(fd, 0)
            os.write(fd, json.dumps({'pid': os.getpid(), 'script': pathlib.Path(sys.argv[0]).name}).encode())
        atexit.register(self.close)

    def child_options(self):
        environment = dict(os.environ, **{FD_ENV: str(self.fd), PATH_ENV: str(self.path)})
        return {'pass_fds': (self.fd,), 'env': environment}

    def close(self):
        if self.fd >= 0:
            # Do not LOCK_UN: inherited descriptors share the same lease and
            # must protect an active child even if its controller exits first.
            os.close(self.fd)
            self.fd = -1


def acquire_phone_lease():
    try:
        return PhoneLease()
    except PhoneBusy as error:
        raise SystemExit(str(error)) from error
