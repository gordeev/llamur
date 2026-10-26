#!/usr/bin/env python3
"""Evaluate public pure-Python coding fixtures in a bounded subprocess.

AST restrictions deliberately limit supported solutions; an unsupported solution
is reported as a harness limitation rather than an incorrect model answer.
"""
import argparse
import ast
import bisect
import json
import math
import pathlib
import random
import re
import subprocess
import sys

ALLOWED = (ast.Module, ast.FunctionDef, ast.arguments, ast.arg, ast.Return, ast.Expr, ast.Constant,
           ast.Assign, ast.AnnAssign, ast.AugAssign, ast.Name, ast.Load, ast.Store, ast.List, ast.Tuple,
           ast.Subscript, ast.Slice, ast.Call, ast.keyword, ast.BinOp, ast.Add, ast.Sub, ast.Mult,
           ast.FloorDiv, ast.Mod, ast.Div, ast.UnaryOp, ast.USub, ast.UAdd, ast.Not, ast.If, ast.IfExp,
           ast.While, ast.For, ast.Break, ast.Continue, ast.Compare, ast.Eq, ast.NotEq, ast.Lt, ast.LtE,
           ast.Gt, ast.GtE, ast.Is, ast.IsNot, ast.In, ast.NotIn, ast.BoolOp, ast.And, ast.Or,
           ast.Pass, ast.Assert, ast.Raise)
SAFE = {'len': len, 'range': range, 'min': min, 'max': max, 'abs': abs, 'sum': sum,
        'int': int, 'list': list, 'tuple': tuple, 'bool': bool, 'float': float,
        'isinstance': isinstance, 'TypeError': TypeError, 'ValueError': ValueError,
        'AssertionError': AssertionError}

class Unsupported(ValueError):
    pass

def prepare(code, function):
    if len(code.encode()) > 32768: raise Unsupported('Code exceeds limit')
    tree = ast.parse(code)
    # Executing only definitions avoids model-provided print/demo statements.
    definitions = [node for node in tree.body if isinstance(node, ast.FunctionDef)]
    if not any(node.name == function for node in definitions): raise Unsupported('Required function missing')
    for node in definitions:
        if node.decorator_list: raise Unsupported('Decorators are not supported')
        node.returns = None
        for argument in [*node.args.posonlyargs, *node.args.args, *node.args.kwonlyargs]: argument.annotation = None
        if node.args.vararg: node.args.vararg.annotation = None
        if node.args.kwarg: node.args.kwarg.annotation = None
    module = ast.Module(body=definitions, type_ignores=[])
    nodes = list(ast.walk(module))
    if len(nodes) > 4096: raise Unsupported('AST exceeds limit')
    functions = {node.name for node in definitions}
    for node in nodes:
        if not isinstance(node, ALLOWED): raise Unsupported(f'Unsupported syntax: {type(node).__name__}')
        if isinstance(node, ast.Name) and node.id.startswith('__'): raise Unsupported('Private names are not supported')
        if isinstance(node, ast.FunctionDef) and node.name.startswith('__'): raise Unsupported('Private function name')
        if isinstance(node, ast.Call) and (not isinstance(node.func, ast.Name) or node.func.id not in SAFE.keys() | functions):
            raise Unsupported('Unsupported function call')
        if isinstance(node, ast.Constant) and isinstance(node.value, int) and abs(node.value) > 1000000:
            raise Unsupported('Oversized literal')
    return ast.fix_missing_locations(module)

def check_function(function, name):
    rng = random.Random(20261024)
    if name == 'add':
        cases = [(a, b) for a in [-10**12, -1, 0, 1, 10**12] for b in [-10**12, -1, 0, 1, 10**12]]
        cases += [(rng.randint(-10**9, 10**9), rng.randint(-10**9, 10**9)) for _ in range(128)]
        for a, b in cases:
            value = function(a, b)
            if type(value) is not int or value != a + b: return {'passed': False, 'counterexample': [a, b], 'expected': a + b, 'actual': repr(value)}
        return {'passed': True, 'cases': len(cases)}
    cases = [([], 0), ([1], 0), ([1], 1), ([1], 2), ([1, 1, 1], 1), ([1, 1, 1], 2)]
    for _ in range(128):
        values = sorted(rng.randint(-100, 100) for _ in range(rng.randrange(100)))
        cases.append((values, rng.randint(-150, 150)))
    for values, target in cases:
        value = function(values, target); expected = bisect.bisect_left(values, target)
        if type(value) is not int or value != expected: return {'passed': False, 'counterexample': [values, target], 'expected': expected, 'actual': repr(value)}
    class CountedSequence:
        def __init__(self, values): self.values = values; self.reads = 0
        def __len__(self): return len(self.values)
        def __getitem__(self, index):
            if isinstance(index, slice): raise AssertionError('Slicing violates tested constant-space contract')
            self.reads += 1
            return self.values[index]
    reads = []
    for size in [1, 16, 128, 4096]:
        for target in [-1, size, 2 * size]:
            values = list(range(0, 2 * size, 2)); sequence = CountedSequence(values)
            value = function(sequence, target)
            if value != bisect.bisect_left(values, target): return {'passed': False, 'complexity_case_wrong': size}
            bound = 2 * math.ceil(math.log2(size + 1)) + 3
            if sequence.reads > bound: return {'passed': False, 'complexity_reads': sequence.reads, 'bound': bound, 'size': size}
            reads.append(sequence.reads)
    return {'passed': True, 'cases': len(cases), 'complexity_reads': reads}

def worker(payload):
    import resource
    resource.setrlimit(resource.RLIMIT_CPU, (2, 2))
    resource.setrlimit(resource.RLIMIT_FSIZE, (0, 0))
    try: resource.setrlimit(resource.RLIMIT_DATA, (256 * 1024 * 1024, 256 * 1024 * 1024))
    except (ValueError, OSError): pass
    name = payload['function']; tree = prepare(payload['code'], name)
    namespace = {'__builtins__': SAFE}
    exec(compile(tree, '<public-model-fixture>', 'exec'), namespace, namespace)
    return check_function(namespace[name], name)

def evaluate(code, function):
    try: prepare(code, function)
    except (Unsupported, SyntaxError, ValueError) as error:
        return {'passed': False, 'classification': 'harness_unsupported', 'error': str(error)}
    try:
        result = subprocess.run([sys.executable, str(pathlib.Path(__file__).resolve()), '--worker'],
                                input=json.dumps({'code': code, 'function': function}), capture_output=True, text=True, timeout=4)
        if result.returncode != 0: return {'passed': False, 'classification': 'execution_failed', 'exit_code': result.returncode, 'stderr': result.stderr[-1000:]}
        return json.loads(result.stdout)
    except subprocess.TimeoutExpired:
        return {'passed': False, 'classification': 'timeout'}

def final_answer(report):
    """Known pinned fixture formats only; never score a code block inside thought."""
    text = report.get('output', '')
    prompt = report.get('result', {}).get('trace', {}).get('rendered_prompt', '')
    if text.startswith('<|channel>thought\n'):
        _, separator, final = text.partition('<channel|>')
        return (final, 'gemma-thought-closed') if separator else ('', 'unclosed-thought')
    if prompt.endswith('<think>\n') or text.startswith('<think>'):
        _, separator, final = text.partition('</think>')
        return (final, 'qwen-think-closed') if separator else ('', 'unclosed-thought')
    return text, 'no-open-reasoning-marker'

def assess_report(report, function=None, identifier=None):
    text, channel = final_answer(report)
    if report.get('result', {}).get('mode') == 'teacher_forcing':
        assessment = {'passed': False, 'classification': 'not_generation'}
    elif identifier is not None:
        assessment = {'passed': text.strip() in (identifier, '`' + identifier + '`'),
                      'identifier_in_final_answer': identifier in text, 'expected_identifier': identifier}
    else:
        blocks = re.findall(r'```(?:python|py)?\s*\n(.*?)```', text, re.DOTALL | re.IGNORECASE)
        candidates = [code for code in blocks if re.search(r'\bdef\s+' + re.escape(function) + r'\s*\(', code)]
        assessment = evaluate(candidates[-1], function) if candidates else {'passed': False, 'classification': 'no_complete_final_code_block'}
    assessment['answer_channel'] = channel
    assessment['finish_reason'] = report.get('result', {}).get('finish_reason')
    assessment['task_completed'] = assessment['finish_reason'] == 'eog'
    assessment['quality_passed'] = assessment['passed'] and assessment['task_completed']
    return assessment

if __name__ == '__main__':
    if sys.argv[1:] == ['--worker']:
        try: print(json.dumps(worker(json.load(sys.stdin))))
        except BaseException as error: print(json.dumps({'passed': False, 'error': str(error)}))
        raise SystemExit(0)
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('result', type=pathlib.Path)
    target = p.add_mutually_exclusive_group(required=True)
    target.add_argument('--function', choices=['add', 'lower_bound'])
    target.add_argument('--identifier', help='Require this exact retrieval answer in the final channel')
    args = p.parse_args()
    report = json.loads(args.result.read_text())
    assessment = assess_report(report, args.function, args.identifier)
    destination = args.result.parent / 'quality.json'
    destination.write_text(json.dumps(assessment, indent=2) + '\n')
    print(json.dumps(assessment, indent=2))
    raise SystemExit(0 if assessment['quality_passed'] else 1)
