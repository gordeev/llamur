import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('code_evaluator', pathlib.Path(__file__).resolve().parents[1] / 'scripts/evaluate-code.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class CodeEvaluator(unittest.TestCase):
    def test_correct_add(self):
        self.assertTrue(module.evaluate('def add(a: int, b: int) -> int:\n    return a + b\n', 'add')['passed'])
    def test_wrong_add(self):
        self.assertFalse(module.evaluate('def add(a, b):\n    return a - b\n', 'add')['passed'])
    def test_thought_is_not_a_final_answer(self):
        good = '```python\ndef add(a,b):\n    return a+b\n```'
        bad = '```python\ndef add(a,b):\n    return a-b\n```'
        report = {'output': '<|channel>thought\n' + good + '<channel|>' + bad, 'result': {'finish_reason': 'eog'}}
        self.assertFalse(module.assess_report(report, 'add')['quality_passed'])
        report['output'] = '<|channel>thought\n' + good
        self.assertFalse(module.assess_report(report, 'add')['quality_passed'])
    def test_retrieval_requires_final_exact_answer_and_eos(self):
        report = {'output': 'needle_17</think>wrong', 'result': {'finish_reason': 'eog', 'trace': {'rendered_prompt': '<think>\n'}}}
        self.assertFalse(module.assess_report(report, identifier='needle_17')['quality_passed'])
        report['output'] = 'reasoning</think>needle_17'
        self.assertTrue(module.assess_report(report, identifier='needle_17')['quality_passed'])
        report['result']['finish_reason'] = 'length'
        self.assertFalse(module.assess_report(report, identifier='needle_17')['quality_passed'])
    def test_cli_rejects_incomplete_but_functionally_correct_answer(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'result.json'
            path.write_text(json.dumps({'output': '```python\ndef add(a,b):\n    return a+b\n```',
                                       'result': {'finish_reason': 'length'}}))
            run = subprocess.run([sys.executable, module.__file__, str(path), '--function', 'add'],
                                 capture_output=True, text=True, timeout=10)
            self.assertEqual(run.returncode, 1)
            assessment = json.loads((path.parent / 'quality.json').read_text())
            self.assertTrue(assessment['passed'])
            self.assertFalse(assessment['quality_passed'])
    def test_reject_import_and_attributes(self):
        for body in ['import os\n    return os.getcwd()', 'return a.__class__']:
            with self.assertRaises(module.Unsupported): module.prepare('def add(a,b):\n    ' + body, 'add')
    def test_binary_search_and_complexity(self):
        good = 'def lower_bound(values,target):\n    lo,hi=0,len(values)\n    while lo<hi:\n        mid=(lo+hi)//2\n        if values[mid]<target:\n            lo=mid+1\n        else:\n            hi=mid\n    return lo\n'
        self.assertTrue(module.evaluate(good, 'lower_bound')['passed'])
        linear = 'def lower_bound(values,target):\n    i=0\n    while i<len(values) and values[i]<target:\n        i+=1\n    return i\n'
        result = module.evaluate(linear, 'lower_bound')
        self.assertFalse(result['passed'])
        self.assertIn('complexity_reads', result)

if __name__ == '__main__': unittest.main()
