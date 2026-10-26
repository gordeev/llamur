import copy
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location('weight_buffer_checks', pathlib.Path(__file__).resolve().parents[1] / 'scripts/weight_buffer_checks.py')
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


class WeightBufferChecksTests(unittest.TestCase):
    def setUp(self):
        self.result = {'trace': {'rendered_prompt': 'prefix', 'prompt_ids': [1], 'generated_ids': [2, 3]},
                       'generated_tokens': 2, 'logits_checks': 2, 'mode': 'teacher_forcing', 'finish_reason': 'scored',
                       'scored_tokens': 2, 'token_nll': [0.5, 0.6],
                       'first_logits_top8': {'ids': list(range(8)), 'values': [float(x) for x in range(8)]}}
        self.golden = {'expected': self.result['trace']}
        self.request = {'score_continuation_ids': [2, 3], 'check_logits': True}
        self.report = {'passed': True, 'load': {'ok': True, 'kv_type': 'f16', 'weight_buffers': 'cpu_repack', 'all_extra_buffers_disabled': False,
                       'buffer_allocations': [{'buffer': 'CPU_REPACK', 'model_bytes': 100}]}, 'result': self.result}
        self.gates = {'mean_absolute_token_nll_error_max': 0.02, 'maximum_absolute_token_nll_error_max': 0.1,
                      'first_top8_max_shared_logit_absolute_error': 0.1, 'first_top8_set_must_match': True}

    def test_identical_score_passes(self):
        checks.check_result(self.report, self.golden, 'cpu_repack', self.request)
        self.assertTrue(checks.compare_results(self.result, self.result, self.gates)['numeric_passed'])

    def test_prefault_control_requires_actual_native_policy_and_confirmation(self):
        data = copy.deepcopy(self.report)
        data['load'].update(weight_buffers='native', all_extra_buffers_disabled=True,
                            buffer_allocations=[{'buffer':'CPU_Mapped','model_bytes':100}])
        for observed in (None, True):
            data['load']['mmap_prefault'] = observed
            with self.assertRaises(ValueError): checks.check_result(data,self.golden,'native',self.request,mmap_prefault=False)
        data['load']['mmap_prefault'] = False
        checks.check_result(data,self.golden,'native',self.request,mmap_prefault=False)
        data['load']['buffer_allocations'][0]['buffer'] = 'CPU_REPACK'
        with self.assertRaises(ValueError): checks.check_result(data,self.golden,'native',self.request,mmap_prefault=False)

    def test_selective_policy_must_be_explicitly_confirmed(self):
        data = copy.deepcopy(self.report)
        data['load']['weight_buffers'] = 'cpu_repack_q6'
        with self.assertRaises(ValueError): checks.check_result(data,self.golden,'cpu_repack_q6',self.request)
        data['load']['repack_type_filter'] = 'q6_K'
        checks.check_result(data,self.golden,'cpu_repack_q6',self.request)

    def test_transient_requires_native_weights_explicit_policy_and_bounded_scratch(self):
        data = copy.deepcopy(self.report)
        self.golden['expected']['prompt_ids'] = list(range(32))
        data['result']['trace']['prompt_ids'] = list(range(32))
        data['load'].update(weight_buffers='native', all_extra_buffers_disabled=True,
                            buffer_allocations=[{'buffer':'CPU_Mapped','model_bytes':100}],
                            transient_repack=True, cpu_workspace_reserve_bytes=81*1024*1024)
        data['result']['cpu_workspace'] = {'allocated_bytes': 40*1024*1024,
                                          'transient_maximum_requested_bytes': 39*1024*1024,
                                          'graph_nodes_marked': 100}
        checks.check_result(data,self.golden,'native',self.request,transient_repack=True)
        for field, value in [('allocated_bytes',0),('allocated_bytes',1024*1024),('allocated_bytes',82*1024*1024),
                             ('transient_maximum_requested_bytes',81*1024*1024),('graph_nodes_marked',0)]:
            invalid = copy.deepcopy(data)
            invalid['result']['cpu_workspace'][field] = value
            with self.assertRaises(ValueError):
                checks.check_result(invalid,self.golden,'native',self.request,transient_repack=True)
        data['load']['transient_repack'] = False
        with self.assertRaises(ValueError):
            checks.check_result(data,self.golden,'native',self.request,transient_repack=True)
        with self.assertRaises(ValueError):
            checks.check_result(data,self.golden,'native',self.request,transient_repack=False)
        data['result']['cpu_workspace'].update(graph_nodes_marked=0, transient_maximum_requested_bytes=0)
        checks.check_result(data,self.golden,'native',self.request,transient_repack=False)

    def test_minimum_rows_keeps_tiny_fresh_trial_native(self):
        data = copy.deepcopy(self.report)
        data['load'].update(weight_buffers='native', all_extra_buffers_disabled=True,
                            buffer_allocations=[{'buffer':'CPU_Mapped','model_bytes':100}],
                            transient_repack=True, transient_min_rows=24, cpu_workspace_reserve_bytes=81*1024*1024)
        data['result']['cpu_workspace'] = {'allocated_bytes':1024*1024,
                                          'transient_maximum_requested_bytes':43*1024*1024, 'graph_nodes_marked':100}
        checks.check_result(data,self.golden,'native',self.request,transient_repack=True)
        data['result']['cpu_workspace']['allocated_bytes'] = 43*1024*1024
        with self.assertRaises(ValueError): checks.check_result(data,self.golden,'native',self.request,transient_repack=True)

    def test_silent_native_fallback_and_lossy_buffer_rejected(self):
        for buffer in ['CPU', 'CPU_KLEIDIAI']:
            data = copy.deepcopy(self.report)
            data['load']['buffer_allocations'][0]['buffer'] = buffer
            with self.assertRaises(ValueError): checks.check_result(data, self.golden, 'cpu_repack', self.request)

    def test_q4_policy_requires_its_own_filter(self):
        data = copy.deepcopy(self.report)
        data['load']['weight_buffers'] = 'cpu_repack_q4'
        for filter_name in (None, 'q6_K', 'all_supported'):
            data['load']['repack_type_filter'] = filter_name
            with self.assertRaises(ValueError): checks.check_result(data,self.golden,'cpu_repack_q4',self.request)
        data['load']['repack_type_filter'] = 'q4_K'
        checks.check_result(data,self.golden,'cpu_repack_q4',self.request)

    def test_missing_finite_scan_and_nonfinite_nll_rejected(self):
        for key,value in [('logits_checks',1), ('token_nll',[0.5,float('nan')]), ('scored_tokens',1)]:
            data = copy.deepcopy(self.report); data['result'][key] = value
            with self.assertRaises(ValueError): checks.check_result(data,self.golden,'cpu_repack',self.request)

    def test_numeric_regression_fails_original_gate(self):
        candidate = copy.deepcopy(self.result); candidate['token_nll'][0] += 0.05
        self.assertFalse(checks.compare_results(self.result,candidate,self.gates)['numeric_passed'])
        candidate = copy.deepcopy(self.result); candidate['first_logits_top8']['values'][0] += 0.11
        self.assertFalse(checks.compare_results(self.result,candidate,self.gates)['numeric_passed'])
        candidate = copy.deepcopy(self.result); candidate['first_logits_top8']['ids'][0] = 99
        self.assertFalse(checks.compare_results(self.result,candidate,self.gates)['numeric_passed'])

    def test_changed_work_refuses_speed_comparison(self):
        candidate = copy.deepcopy(self.result); candidate['trace']['generated_ids'][0] = 99
        with self.assertRaises(ValueError): checks.compare_results(self.result,candidate,self.gates,require_ids=True)

    def test_incomplete_context_and_changed_prompt_rejected(self):
        golden = dict(self.golden, required_output_tokens=128)
        with self.assertRaises(ValueError): checks.check_result(self.report,golden,'cpu_repack',self.request)
        data = copy.deepcopy(self.report); data['result']['trace']['prompt_ids'] = [9]
        with self.assertRaises(ValueError): checks.check_result(data,self.golden,'cpu_repack',self.request)

    def test_speed_requires_all_completed_matching_quality_evidence(self):
        names = {'code_nll':'teacher-forcing-code-v1','russian_nll':'teacher-forcing-ru-v1',
                 'completed_task':'binary-search-quality-4096-mmap-v1','filled_context':'pp8064-full-mmap-v1'}
        reports = {name:{'passed':True,'apk_sha256':'apk','model_sha256':'model','config':{'context':8192},'fixture':fixture,
                   'pairs':[{'passed':True,'comparison':{'numeric_passed':True},'trials':{'cpu_repack':{'quality':{'quality_passed':True}}}}]}
                   for name,fixture in names.items()}
        checks.check_speed_evidence(reports,'apk','model',{'context':8192})
        for name in names:
            changed = copy.deepcopy(reports); changed[name]['passed'] = False
            with self.assertRaises(ValueError): checks.check_speed_evidence(changed,'apk','model',{'context':8192})
        with self.assertRaises(ValueError): checks.check_speed_evidence(reports,'different-apk','model',{'context':8192})
        with self.assertRaises(ValueError): checks.check_speed_evidence(reports,'apk','model',{'context':4096})
        with self.assertRaises(ValueError): checks.check_speed_evidence(reports,'apk','model',{'context':8192},'cpu_repack_q6')
        with self.assertRaises(ValueError): checks.check_speed_evidence(reports,'apk','model',{'context':8192},'native_no_prefault')
        demand = copy.deepcopy(reports)
        for item in demand.values(): item['candidate_policy'] = 'native_no_prefault'
        checks.check_speed_evidence(demand,'apk','model',{'context':8192},'native_no_prefault')
        demand['filled_context']['candidate_policy'] = 'cpu_repack'
        with self.assertRaises(ValueError): checks.check_speed_evidence(demand,'apk','model',{'context':8192},'native_no_prefault')
        changed = copy.deepcopy(reports); changed['completed_task']['pairs'][0]['trials']['cpu_repack']['quality']['quality_passed'] = False
        with self.assertRaises(ValueError): checks.check_speed_evidence(changed,'apk','model',{'context':8192})
