"""Always attempt each trial cleanup; preserve the original workload failure."""
import json


def finalize_trial(out, actions, primary_error_active=False):
    errors = []
    for name, action in actions:
        try:
            result = action()
            if result is not None and getattr(result, 'returncode', 0) != 0:
                raise RuntimeError(f'Command exited with status {result.returncode}')
        except Exception as error:
            errors.append({'action': name, 'error': str(error), 'kind': type(error).__name__})
    if errors:
        (out / 'finalization-errors.json').write_text(json.dumps(errors, indent=2) + '\n')
        if not primary_error_active:
            raise RuntimeError('Trial finalization failed; inspect finalization-errors.json')
    return errors
