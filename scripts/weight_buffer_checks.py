"""Fail-closed checks for native/CPU_REPACK experiments, independent of ADB."""
import math


def check_speed_evidence(reports, apk, model, config, candidate_policy='cpu_repack'):
    expected = {'code_nll': {'teacher-forcing-code-v1','teacher-forcing-code-prefill-v2'}, 'russian_nll': {'teacher-forcing-ru-v1','teacher-forcing-ru-prefill-v2'},
                'completed_task': {'binary-search-quality-4096-mmap-v1'},
                'filled_context': {'pp8064-full-mmap-v1','needle-7680-exact-mmap-v2'}}
    if reports.keys() != expected.keys(): raise ValueError('Missing quality evidence')
    for name, report in reports.items():
        pairs = report.get('pairs', [])
        if (report.get('passed') is not True or report.get('error') or report.get('candidate_policy','cpu_repack') != candidate_policy or report.get('apk_sha256') != apk or
            report.get('model_sha256') != model or report.get('config') != config or
            report.get('fixture') not in expected[name] or not pairs or
            not all(pair.get('passed') is True and pair.get('comparison',{}).get('numeric_passed') is True for pair in pairs)):
            raise ValueError('Invalid quality evidence: '+name)
        if name == 'completed_task' and not all(pair['trials']['cpu_repack'].get('quality',{}).get('quality_passed') is True for pair in pairs):
            raise ValueError('Completed task did not pass functional checks')


def check_result(report, golden, policy, request, mmap_prefault=None, transient_repack=None):
    load = report['load']
    if report.get('passed') is not True or load.get('ok') is not True or load.get('weight_buffers') != policy:
        raise ValueError('Runtime did not confirm the requested weight policy')
    if load.get('all_extra_buffers_disabled') != (policy == 'native'):
        raise ValueError('Extra-buffer policy differs')
    if mmap_prefault is not None and load.get('mmap_prefault') is not mmap_prefault:
        raise ValueError('Runtime did not confirm the requested mmap prefault policy')
    if transient_repack is not None:
        if load.get('transient_repack') is not transient_repack:
            raise ValueError('Runtime did not confirm explicit transient workspace policy')
        if transient_repack:
            workspace = report.get('result', {}).get('cpu_workspace', {})
            minimum_rows = load.get('transient_min_rows', 4)
            if type(minimum_rows) is not int or not 4 <= minimum_rows <= 128:
                raise ValueError('Invalid transient minimum-row policy')
            if (policy != 'native' or load.get('cpu_workspace_reserve_bytes') != 81*1024*1024 or
                not 0 < workspace.get('allocated_bytes', 0) <= 81*1024*1024 or
                not 0 < workspace.get('transient_maximum_requested_bytes', 0) <= 80*1024*1024 or
                workspace.get('graph_nodes_marked', 0) <= 0):
                raise ValueError('Missing or unbounded transient workspace evidence')
            # Counters include reservation graphs. Require actual scratch large
            # enough for the packed source, so reservation-only marking plus a
            # small native workspace cannot masquerade as an exercised path.
            # Pinned eligibility: K<=16384,N<=512; Q8_K is292B per256 values.
            maximum_activation_bytes = 16384 * 512 // 256 * 292
            applied_expected = len(golden['expected']['prompt_ids']) >= minimum_rows
            if applied_expected and workspace['allocated_bytes'] < workspace['transient_maximum_requested_bytes'] - maximum_activation_bytes:
                raise ValueError('Only graph reservation observed; actual packed workspace not established')
            if not applied_expected and workspace['allocated_bytes'] > maximum_activation_bytes + 1024*1024:
                raise ValueError('Fresh tiny-prompt trial unexpectedly allocated packed scratch')
        else:
            workspace = report.get('result', {}).get('cpu_workspace', {})
            if workspace.get('graph_nodes_marked') != 0 or workspace.get('transient_maximum_requested_bytes') != 0:
                raise ValueError('Native control unexpectedly marked transient operations')
    if load.get('kv_type') != 'f16': raise ValueError('KV representation changed')
    packed = 0
    for item in load['buffer_allocations']:
        if item['buffer'] == 'CPU_REPACK': packed += item['model_bytes']
        elif item['model_bytes'] and item['buffer'] not in ('CPU', 'CPU_Mapped'):
            raise ValueError('Unapproved model buffer: ' + item['buffer'])
    if (packed > 0) != (policy != 'native'):
        raise ValueError('Packed allocation absent or unexpectedly enabled')
    if policy == 'cpu_repack_q6' and load.get('repack_type_filter') != 'q6_K':
        raise ValueError('Selective Q6_K policy not confirmed')
    if policy == 'cpu_repack_q4' and load.get('repack_type_filter') != 'q4_K':
        raise ValueError('Selective Q4_K policy not confirmed')
    return check_model_result_trace(report['result'], golden, request)


def check_model_result_trace(result, golden, request):
    trace = result['trace']
    if any(trace[key] != golden['expected'][key] for key in ('rendered_prompt', 'prompt_ids')):
        raise ValueError('Template or prompt IDs differ from frozen fixture')
    if len(trace['generated_ids']) != result['generated_tokens'] or not trace['generated_ids']:
        raise ValueError('Missing generated/scored token trace')
    if request.get('check_logits') and result.get('logits_checks', 0) < result['generated_tokens']:
        raise ValueError('Finite-logit coverage missing')
    if golden.get('required_output_tokens') is not None and result['generated_tokens'] != golden['required_output_tokens']:
        raise ValueError('Filled-context output incomplete')
    if 'score_continuation_ids' in request:
        ids = request['score_continuation_ids']
        if (result.get('mode') != 'teacher_forcing' or result['finish_reason'] != 'scored' or
            trace['generated_ids'] != ids or result.get('scored_tokens') != len(ids) or
            len(result['token_nll']) != len(ids) or not all(math.isfinite(x) for x in result['token_nll'])):
            raise ValueError('Invalid teacher-forcing trajectory/NLL')
    return result


def compare_results(reference, candidate, gates, require_ids=False):
    if reference['trace']['prompt_ids'] != candidate['trace']['prompt_ids']:
        raise ValueError('Paired prompt IDs differ')
    ids_match = reference['trace']['generated_ids'] == candidate['trace']['generated_ids']
    if require_ids and (not ids_match or reference['finish_reason'] != candidate['finish_reason']):
        raise ValueError('Different generated work; speed comparison refused')
    report = {'generated_ids_match': ids_match}
    if reference.get('mode') == 'teacher_forcing':
        if not ids_match: raise ValueError('Teacher-forcing IDs differ')
        errors = [abs(a-b) for a, b in zip(reference['token_nll'], candidate['token_nll'])]
        if not errors or len(reference['token_nll']) != len(candidate['token_nll']):
            raise ValueError('Different NLL coverage')
        report.update(mean_absolute_token_nll_error=sum(errors)/len(errors), maximum_absolute_token_nll_error=max(errors))
    if 'first_logits_top8' in reference:
        a, b = (dict(zip(item['first_logits_top8']['ids'], item['first_logits_top8']['values'])) for item in (reference, candidate))
        if len(a) != 8 or len(b) != 8 or not all(math.isfinite(x) for x in [*a.values(), *b.values()]):
            raise ValueError('Missing/nonfinite top8 logits')
        shared = a.keys() & b.keys()
        report.update(first_top8_set_matches=a.keys() == b.keys(),
                      first_top8_max_shared_logit_absolute_error=max((abs(a[k]-b[k]) for k in shared), default=None))
    passed = True
    for metric in ('mean_absolute_token_nll_error', 'maximum_absolute_token_nll_error', 'first_top8_max_shared_logit_absolute_error'):
        if metric in report:
            passed &= report[metric] is not None and report[metric] <= gates[metric + '_max' if metric != 'first_top8_max_shared_logit_absolute_error' else metric]
    if gates['first_top8_set_must_match'] and 'first_top8_set_matches' in report:
        passed &= report['first_top8_set_matches']
    report['numeric_passed'] = bool(passed)
    return report
