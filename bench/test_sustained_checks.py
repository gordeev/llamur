import copy
import pathlib
import sys
import unittest
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'scripts'))
from sustained_checks import summarize


class SustainedEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.spec = {'config': {'threads': 4}, 'request': {}}
        self.golden = {'expected': {'rendered_prompt': 'fixture', 'prompt_ids': [1]}, 'required_output_tokens': 128}
        self.gates = {'first_top8_set_must_match': True}
        result = dict(generated_tokens=128, finish_reason='length', trace=dict(rendered_prompt='fixture', prompt_ids=[1], generated_ids=list(range(128))),
                      cpu_workspace=dict(graph_nodes_marked=0, transient_maximum_requested_bytes=0),
                      cpu_scheduling=dict(caller_allowed_cpus=list(range(7)), threads=[dict(tid=1, cpus=list(range(7)))]),
                      inter_token_us=[750000]*127, first_target_token_us=3000000, prefill_us=2900000,
                      backend_perf=dict(decode_evals=127, decode_ms=95250))
        self.reference = copy.deepcopy(result)
        load = dict(ok=True, weight_buffers='native', all_extra_buffers_disabled=True, transient_repack=False, kv_type='f16',
                    buffer_allocations=[], threads=4, threads_batch=4, cpu_affinity=[], explicit_threadpools=False)
        data = dict(passed=True, pid=7, load=load, result=result, completed_interactive=True, completed_importance=100, completed_pss_kib=6000000)
        telemetry = dict(interactive=True, thermal_status=0, battery_scale=100, battery_level=81, battery_status=4, plugged=1)
        self.rows = [dict(kind='load', snapshot=dict(pid=7))]
        for index in range(11):
            self.rows.append(dict(kind='request', index=max(0,index-1), warmup=index==0, started_ns=index*100_000_000_000,
                                 ended_ns=(index+1)*100_000_000_000, snapshot=copy.deepcopy(data), before=copy.deepcopy(telemetry), after=copy.deepcopy(telemetry)))

    def summarize(self): return summarize(self.rows, self.spec, self.golden, self.reference, self.gates, 900)

    def test_tail_uses_only_complete_intervals(self):
        result = self.summarize()
        self.assertEqual(result['requests'], 10)
        self.assertAlmostEqual(result['emission_tok_s'], 4/3)
        self.assertAlmostEqual(result['final_five_minute_emission_tok_s'], 4/3)
        self.assertAlmostEqual(result['final_five_decode_interval_s'], 3*95.25)

    def test_trajectory_change_rejects_even_if_timing_improves(self):
        self.rows[5]['snapshot']['result']['trace']['generated_ids'][9] = -1
        with self.assertRaisesRegex(ValueError, 'Different generated work'): self.summarize()

    def test_process_death_rejects(self):
        self.rows[4]['snapshot']['pid'] = 8
        with self.assertRaisesRegex(ValueError, 'Process changed'): self.summarize()

    def test_short_duration_rejects(self):
        self.rows[-1]['ended_ns'] = self.rows[-2]['ended_ns']
        with self.assertRaises(ValueError): self.summarize()

    def test_power_change_rejects(self):
        self.rows[-1]['after']['plugged'] = 0
        with self.assertRaisesRegex(ValueError, 'Power state'): self.summarize()

    def test_index_gap_rejects(self):
        self.rows[7]['index'] += 1
        with self.assertRaisesRegex(ValueError, 'indices'): self.summarize()
