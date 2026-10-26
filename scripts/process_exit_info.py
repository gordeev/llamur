"""Collect delayed Android exit records without confusing an earlier process death."""
import re
import time


def matching_exit_record(output, pid, process_name):
    if pid is None:
        return None
    for block in re.split(r'(?=\s*ApplicationExitInfo #\d+:)', output):
        identity = re.search(r'\bpid=(\d+)\b', block)
        process = re.search(r'\bprocess=(\S+)', block)
        reason = re.search(r'\breason=(\d+)\s+\(([^)]+)\)', block)
        if identity and process and reason and int(identity[1]) == pid and process[1] == process_name:
            return {'pid': pid, 'process': process_name, 'reason_code': int(reason[1]),
                    'reason': reason[2], 'raw': block.strip()}
    return None


def collect_exit_info(read, save, pid, process_name, attempts=6, delay=0.5, sleep=time.sleep):
    """read must have a bounded subprocess timeout; retain every snapshot, even failures."""
    latest = None
    for index in range(attempts):
        result = read()
        save(index, result)
        if result.returncode == 0:
            latest = matching_exit_record(result.stdout, pid, process_name)
            if latest is not None:
                break
        # Missing identity cannot be resolved by waiting; still preserve one dump.
        if pid is None or index + 1 == attempts:
            break
        sleep(delay)
    return latest
