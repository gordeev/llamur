"""GPU storage/trajectory checks without weakening the existing CPU-only gate."""
from weight_buffer_checks import check_model_result_trace


def check_gpu_result(data, golden, config, request):
    load=data['load']
    for key in ('backend','gpu_layers','context','threads','batch','micro_batch','load_mode','mmap_prefault'):
        if load.get(key)!=config[key]:raise ValueError('GPU load configuration differs: '+key)
    if data.get('passed') is not True or load.get('weight_buffers')!='native' or load.get('all_extra_buffers_disabled') is not True or load.get('kv_type')!='f16':
        raise ValueError('GPU/native/F16 policy not confirmed')
    if load.get('transient_repack') or load.get('prefix_cache_mib',0):raise ValueError('Unqualified mixed optimization in GPU screening')
    gpu=0
    for item in load['buffer_allocations']:
        name=item['buffer']
        if item['model_bytes'] and name not in ('CPU','CPU_Mapped','OpenCL'):
            raise ValueError('Unexpected model buffer: '+name)
        if name=='OpenCL':gpu+=item['model_bytes']
    plan=data['load_admission'].get('gpu_plan',{})
    if gpu<=0 or not plan.get('no_alloc') or abs(gpu-plan.get('gpu_model_bytes',0))>1024*1024:
        raise ValueError('Actual GPU model allocation not established or exceeds preflight plan')
    if not data['load_admission'].get('accepted'):raise ValueError('GPU memory preflight refused')
    return check_model_result_trace(data['result'],golden,request)
