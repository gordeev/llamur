import copy
import pathlib
import sys
import unittest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'scripts'))
from decode_checks import check_scheduling, decode_metrics, screen_pair

class DecodeChecks(unittest.TestCase):
    def test_distinct_pools_require_all_workers_and_caller(self):
        config=dict(threads=2,threads_batch=4,cpu_affinity=[3,4,5,6])
        data=dict(load=dict(config,explicit_threadpools=True),result=dict(cpu_scheduling=dict(caller_allowed_cpus=list(range(7)),threads=[dict(tid=i+1,cpus=[3,4,5,6]) for i in range(5)])))
        self.assertEqual(check_scheduling(data,config),list(range(7)))
        for broken in (data['result']['cpu_scheduling']['threads'][:-1], [dict(tid=1,cpus=[3,4,5,6])]*5, [dict(tid=i+1,cpus=[0,1,2]) for i in range(5)]):
            changed=copy.deepcopy(data);changed['result']['cpu_scheduling']['threads']=broken
            with self.assertRaises(ValueError): check_scheduling(changed,config)
    def test_shared_pool_count(self):
        config=dict(threads=4,threads_batch=4)
        data=dict(load=dict(threads=4,threads_batch=4,cpu_affinity=[],explicit_threadpools=True),result=dict(cpu_scheduling=dict(caller_allowed_cpus=list(range(7)),threads=[dict(tid=i+1,cpus=list(range(7))) for i in range(4)])))
        check_scheduling(data,config)
    def test_decode_counts_emitted_work(self):
        result=dict(generated_tokens=4,backend_perf=dict(decode_evals=3,decode_ms=1500),inter_token_us=[500000]*3,prefill_us=2,first_target_token_us=3)
        self.assertEqual(decode_metrics(result)['emission_tok_s'],2)
        result['backend_perf']['decode_evals']=4
        with self.assertRaises(ValueError):decode_metrics(result)
    def test_pair_rejects_unused_prime_only_if_used(self):
        power=dict(percent=80,status=4,charging=False,ac_connected=True,usb_connected=False,temperature_c=35)
        caps={'0':dict(cpus=[0,1,2],max_khz=1500000),'3':dict(cpus=[3,4,5,6],max_khz=1500000),'7':dict(cpus=[7],max_khz=3000000)}
        a=dict(allowed_cpus=list(range(7)),used_cpus=[3,4,5,6],power_before=power,power_after=power,thermal_status=0,completed_interactive=True,completed_importance=100,frequency_snapshots=[copy.deepcopy(caps),copy.deepcopy(caps)])
        b=copy.deepcopy(a);b['frequency_snapshots'][1]['7']['max_khz']=1000000
        self.assertTrue(screen_pair(a,b)['passed'])
        b['frequency_snapshots'][1]['3']['max_khz']=1000000
        self.assertFalse(screen_pair(a,b)['passed'])
    def test_pair_rejects_power_or_foreground_change(self):
        power=dict(percent=80,status=4,charging=False,ac_connected=True,usb_connected=False,temperature_c=35)
        caps={'0':dict(cpus=[0,1,2],max_khz=1500000)}
        a=dict(allowed_cpus=[0,1,2],used_cpus=[0,1,2],power_before=power,power_after=power,thermal_status=0,completed_interactive=True,completed_importance=100,frequency_snapshots=[caps,caps])
        b=copy.deepcopy(a);b['power_after']['ac_connected']=False
        self.assertFalse(screen_pair(a,b)['passed'])
        b=copy.deepcopy(a);b['completed_importance']=200
        self.assertFalse(screen_pair(a,b)['passed'])
