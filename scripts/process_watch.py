"""Fail a trial when its observed inference process disappears or is replaced."""


class HarnessProcessDied(RuntimeError):
    pass


class ProcessWatch:
    def __init__(self):
        self.pid = None

    def observe(self, output):
        values = output.split()
        if any(not value.isdecimal() for value in values) or len(values) > 1:
            raise RuntimeError('Ambiguous inference process identity: ' + repr(output))
        current = int(values[0]) if values else None
        if self.pid is None:
            self.pid = current
        elif current != self.pid:
            raise HarnessProcessDied(f'Inference process {self.pid} exited; observed replacement {current}. Request is not replayed.')
        return current
