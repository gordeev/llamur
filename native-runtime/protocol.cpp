#include "protocol.h"
#include "ggml-cpu.h"
#include <limits>
#include <cmath>
#include <algorithm>

namespace motoai {
namespace {
int64_t integer(const Json & value, const char * key, int64_t fallback, int64_t low, int64_t high) {
    auto found = value.find(key);
    if (found == value.end()) return fallback;
    if (!found->is_number_integer() ||
        (found->is_number_unsigned() && found->get<uint64_t>() > static_cast<uint64_t>(high))) {
        throw std::invalid_argument(std::string("Invalid integer: ") + key);
    }
    const auto number = found->get<int64_t>();
    if (number < low || number > high) throw std::invalid_argument(std::string("Integer out of range: ") + key);
    return number;
}
}

Config parse_config(const Json & data) {
    Config config;
    config.backend = data.value("backend", "cpu");
    config.gpu_layers = static_cast<int32_t>(integer(data, "gpu_layers", 0, 0, 128));
    if ((config.backend != "cpu" && config.backend != "vulkan" && config.backend != "opencl") ||
        ((config.backend == "cpu") != (config.gpu_layers == 0)))
        throw std::invalid_argument("Select cpu with zero GPU layers, or explicit vulkan/opencl with 1..128 layers");
    config.tensor_probe = data.value("tensor_probe", false);
    config.attention_capture = data.value("attention_capture", false);
    config.swa_full = data.value("swa_full", true);
    config.context = static_cast<uint32_t>(integer(data, "context", 4096, 128, 262144));
    config.threads = static_cast<int32_t>(integer(data, "threads", 4, 1, 64));
    config.threads_batch = static_cast<int32_t>(integer(data, "threads_batch", 0, 0, 64));
    if (data.contains("cpu_affinity")) {
        const auto & cpus = data.at("cpu_affinity");
        if (!cpus.is_array() || cpus.size() > 64) throw std::invalid_argument("Invalid CPU affinity array");
        for (const auto & cpu : cpus) {
            if (!cpu.is_number_integer() || (cpu.is_number_unsigned() && cpu.get<uint64_t>() > 127) ||
                cpu.get<int64_t>() < 0 || cpu.get<int64_t>() > 127)
                throw std::invalid_argument("Invalid CPU affinity index");
            const auto index = cpu.get<int32_t>();
            if (std::find(config.cpu_affinity.begin(), config.cpu_affinity.end(), index) != config.cpu_affinity.end())
                throw std::invalid_argument("Duplicate CPU affinity index");
            config.cpu_affinity.push_back(index);
        }
        std::sort(config.cpu_affinity.begin(), config.cpu_affinity.end());
    }
    config.batch = static_cast<uint32_t>(integer(data, "batch", 512, 1, 2048));
    config.micro_batch = static_cast<uint32_t>(integer(data, "micro_batch", 128, 1, config.batch));
    const auto mode = data.value("load_mode", "auto");
    if (mode == "read") config.load_mode = LLAMA_LOAD_MODE_NONE;
    else if (mode == "mmap") config.load_mode = LLAMA_LOAD_MODE_MMAP;
    else if (mode != "auto") throw std::invalid_argument("Unknown load mode");
    const auto weights = data.value("weight_buffers", "native");
    if (weights == "cpu_repack") config.weight_buffers = WeightBuffers::CpuRepack;
    else if (weights == "cpu_repack_q6") config.weight_buffers = WeightBuffers::CpuRepackQ6;
    else if (weights == "cpu_repack_q4") config.weight_buffers = WeightBuffers::CpuRepackQ4;
    else if (weights != "native") throw std::invalid_argument("Unknown weight buffer policy");
    config.mmap_prefault = data.value("mmap_prefault", true);
    config.transient_repack = data.value("transient_repack", false);
    config.prefix_cache_mib = static_cast<uint32_t>(integer(data, "prefix_cache_mib", 0, 0, 256));
    if(config.prefix_cache_mib && config.prefix_cache_mib < 64) throw std::invalid_argument("Prefix cache budget must be0 or64..256MiB");
    return config;
}

Request parse_request(const Json & data) {
    Request request;
    request.max_tokens = static_cast<int32_t>(integer(data, "max_tokens", 128, 1, 262144));
    request.seed = static_cast<uint32_t>(integer(data, "seed", 42, 0, std::numeric_limits<uint32_t>::max()));
    request.temperature = data.value("temperature", 0.0f);
    request.thinking = data.value("thinking", true);
    request.parse_response = data.value("parse_response", false);
    if (data.contains("top_p")) {
        if (!data.at("top_p").is_number()) throw std::invalid_argument("Invalid top_p");
        request.top_p = data.at("top_p").get<float>();
        if (!std::isfinite(request.top_p) || request.top_p <= 0 || request.top_p > 1) throw std::invalid_argument("Invalid top_p");
    }
    if (data.contains("stop")) {
        const auto & stops = data.at("stop");
        if (!stops.is_array() || stops.size() > 4) throw std::invalid_argument("Invalid stop strings");
        for (const auto & value : stops) {
            if (!value.is_string()) throw std::invalid_argument("Invalid stop string");
            auto stop = value.get<std::string>();
            if (stop.empty() || stop.size() > 256) throw std::invalid_argument("Invalid stop string size");
            request.stop_strings.push_back(std::move(stop));
        }
        if (!request.parse_response) throw std::invalid_argument("Stop strings require structured response parsing");
    }
    request.chat = data.value("chat", true);
    request.trace = data.value("trace", false);
    request.diagnostics = data.value("diagnostics", false);
    request.check_logits = data.value("check_logits", false);
    request.cache_session = data.value("cache_session", "");
    if(request.cache_session.size()>128 || std::any_of(request.cache_session.begin(),request.cache_session.end(),[](unsigned char c) {
        return !((c>='a' && c<='z') || (c>='A' && c<='Z') || (c>='0' && c<='9') || c=='-' || c=='_' || c=='.' || c==':');
    })) throw std::invalid_argument("Invalid opaque cache session");
    if (data.contains("score_continuation_ids")) {
        const auto & ids = data.at("score_continuation_ids");
        if (!ids.is_array() || ids.empty() || ids.size() > 4096) throw std::invalid_argument("Invalid teacher-forcing continuation");
        for (const auto & id : ids) {
            if (!id.is_number_integer() || id.get<int64_t>() < 0 || id.get<int64_t>() > std::numeric_limits<int32_t>::max()) {
                throw std::invalid_argument("Invalid teacher-forcing token");
            }
            request.score_continuation_ids.push_back(id.get<int32_t>());
        }
        if (ids.size() != static_cast<size_t>(request.max_tokens)) throw std::invalid_argument("Teacher-forcing budget must equal continuation length");
        request.check_logits = true;
    }
    request.template_time_unix = integer(data, "template_time_unix", -1, -1, 4102444800LL);
    request.raw_prompt = data.value("raw_prompt", "");
    if (data.contains("messages")) {
        const auto & messages = data.at("messages");
        if (!messages.is_array() || messages.size() > 1024) throw std::invalid_argument("Invalid messages");
        for (const auto & item : messages) {
            common_chat_msg message;
            message.role = item.at("role").get<std::string>();
            message.content = item.at("content").get<std::string>();
            message.reasoning_content = item.value("reasoning_content", "");
            request.messages.push_back(std::move(message));
        }
    }
    return request;
}

Json config_json(const Config & config, int64_t elapsed) {
#ifdef LLAMA_MOTOAI_INPUT_RANGES
    constexpr bool literal_controls = true;
#else
    constexpr bool literal_controls = false;
#endif
#ifdef GGML_MOTOAI_F32_KV_CHUNKS
    constexpr int attention_kv_chunks = GGML_MOTOAI_F32_KV_CHUNKS;
#else
    constexpr int attention_kv_chunks = 0;
#endif
#ifdef LLAMA_MOTOAI_TRANSIENT_MIN_ROWS
    constexpr int transient_min_rows = LLAMA_MOTOAI_TRANSIENT_MIN_ROWS;
#else
    constexpr int transient_min_rows = 4;
#endif
#ifdef LLAMA_MOTOAI_KEEP_KV_LAYOUT
    constexpr bool exact_state_layout = true;
#else
    constexpr bool exact_state_layout = false;
#endif
#ifdef GGML_MOTOAI_LONG_F32_ACC
    constexpr bool long_attention_wide_accumulator = true;
#else
    constexpr bool long_attention_wide_accumulator = false;
#endif
    const char * mode = config.load_mode == LLAMA_LOAD_MODE_NONE ? "read" :
        config.load_mode == LLAMA_LOAD_MODE_MMAP ? "mmap" : "auto";
    return {{"ok", true}, {"backend", config.backend}, {"backend_profile", Runtime::backend_profile(config.backend)}, {"gpu_layers", config.gpu_layers}, {"runtime_variant", MOTOAI_RUNTIME_VARIANT}, {"load_us", elapsed}, {"context", config.context},
            {"literal_chat_controls", literal_controls}, {"attention_f32_kv_chunks", attention_kv_chunks}, {"long_attention_wide_accumulator", long_attention_wide_accumulator}, {"threads", config.threads}, {"threads_batch", config.threads_batch ? config.threads_batch : config.threads},
            {"cpu_affinity", config.cpu_affinity}, {"explicit_threadpools", config.threads_batch != 0 || !config.cpu_affinity.empty()}, {"batch", config.batch}, {"micro_batch", config.micro_batch},
            {"load_mode", mode}, {"kv_type", "f16"}, {"tensor_probe", config.tensor_probe}, {"attention_capture", config.attention_capture},
            {"weight_buffers", weight_buffer_name(config.weight_buffers)},
            {"mmap_prefault", config.mmap_prefault}, {"swa_full", config.swa_full},
            {"transient_repack", config.transient_repack},
            {"prefix_cache_mib", config.prefix_cache_mib}, {"exact_state_layout", exact_state_layout},
            {"transient_min_rows", config.transient_repack ? transient_min_rows : 0},
            {"cpu_workspace_reserve_bytes", config.transient_repack ? 81ULL * 1024 * 1024 : 0},
            {"repack_type_filter", config.weight_buffers == WeightBuffers::CpuRepackQ6 ? "q6_K" : config.weight_buffers == WeightBuffers::CpuRepackQ4 ? "q4_K" : config.weight_buffers == WeightBuffers::Native ? "none" : "all_supported"},
            {"weight_buffer_policy", config.weight_buffers == WeightBuffers::Native ? "native CPU buffers; no extra weight buffers" : "CPU_REPACK-only extras; experimental"},
            {"all_extra_buffers_disabled", config.weight_buffers == WeightBuffers::Native}};
}

Json result_json(const Result & result) {
    Json reply = {{"ok", true}, {"prompt_tokens", result.prompt_tokens}, {"generated_tokens", result.generated_tokens},
        {"processed_prompt_tokens", result.prompt_tokens-result.reused_prompt_tokens},
        {"prepare_us", result.prepare_us}, {"reset_us", result.reset_us}, {"prefill_us", result.prefill_us}, {"decode_us", result.decode_us},
        {"reused_prompt_tokens", result.reused_prompt_tokens}, {"prefix_cache_bytes", result.prefix_cache_bytes},
        {"cache_capture_us", result.cache_capture_us}, {"cache_restore_us", result.cache_restore_us}, {"cache_status", result.cache_status},
        {"first_target_token_us", result.ttft_us}, {"first_nonempty_piece_us", result.first_piece_us},
        {"finish_reason", result.custom_stop ? "stop" : result.eog ? "eog" : "length"}, {"inter_token_us", result.inter_token_us},
        {"backend_perf", {{"prompt_tokens", result.perf.n_p_eval}, {"decode_evals", result.perf.n_eval},
                          {"prefill_ms", result.perf.t_p_eval_ms}, {"decode_ms", result.perf.t_eval_ms}}}};
    reply["cpu_scheduling"] = {{"caller_allowed_cpus", result.caller_allowed_cpus},
        {"scope", "Caller and newly created CPU pool workers; affinity verified after request, not clock residency"}, {"threads", Json::array()}};
    for (const auto & thread : result.cpu_threads) reply["cpu_scheduling"]["threads"].push_back({{"tid", thread.tid}, {"cpus", thread.cpus}});
    reply["prefill_chunks"] = Json::array();
    for (const auto & chunk : result.prefill_chunks) reply["prefill_chunks"].push_back(
        {{"end", chunk.end}, {"tokens", chunk.tokens}, {"eval_us", chunk.eval_us}});
    reply["logits_checks"] = result.logits_checks;
    reply["arm64_fpcr"] = result.floating_control;
    reply["cpu_workspace"] = {{"allocated_bytes", result.cpu_workspace_bytes},
        {"transient_maximum_requested_bytes", result.transient_maximum_requested_bytes},
        {"graph_nodes_marked", result.transient_graph_nodes_marked},
        {"scope", "Marked graph nodes include reservation/rebuilds, not executed-node count; allocated bytes include native CPU scratch"}};
    if (!result.node_probes.empty()) {
        reply["node_probes"] = Json::array();
        for (const auto & node : result.node_probes) reply["node_probes"].push_back({{"name", node.name}, {"status", node.status},
            {"prompt_end", node.prompt_end},
            {"shape", node.shape}, {"finite", node.finite}, {"fingerprint_fnv1a64", node.fingerprint},
            {"minimum", node.minimum}, {"maximum", node.maximum}, {"mean", node.mean}, {"l2", node.l2},
            {"head", node.head}, {"tail", node.tail}, {"type", node.type}, {"data_hex", node.data_hex}, {"op_params", node.op_params}});
    }
    reply["mode"] = result.token_nll.empty() ? "generation" : "teacher_forcing";
    if (!result.token_nll.empty()) {
        double total = 0;
        for (const auto nll : result.token_nll) total += nll;
        const double mean = total / result.token_nll.size();
        reply["finish_reason"] = "scored";
        reply["scored_tokens"] = result.token_nll.size();
        reply["token_nll"] = result.token_nll;
        reply["mean_nll"] = mean;
        // Long, improbable continuations may exceed the range of exp(double).
        reply["perplexity"] = mean < 700 ? Json(std::exp(mean)) : Json(nullptr);
    }
    if (!result.memory_start.empty()) {
        reply["buffer_allocations_start"] = memory_json(result.memory_start);
        reply["buffer_allocations_end"] = memory_json(result.memory_end);
    }
    if (!result.first_top_ids.empty()) reply["first_logits_top8"] = {
        {"ids", result.first_top_ids}, {"values", result.first_top_logits}};
    if (!result.prompt_ids.empty()) reply["trace"] = {{"rendered_prompt", result.rendered_prompt},
        {"prompt_ids", result.prompt_ids}, {"generated_ids", result.generated_ids}};
    if (result.parsed_response) reply["assistant"] = {{"role", "assistant"}, {"content", result.assistant.content}, {"reasoning_content", result.assistant.reasoning_content}};
    return reply;
}

Json memory_json(const std::vector<BufferMemory> & memory) {
    Json result = Json::array();
    for (const auto & buffer : memory) result.push_back({{"buffer", buffer.name}, {"model_bytes", buffer.model_bytes},
        {"context_bytes", buffer.context_bytes}, {"compute_bytes", buffer.compute_bytes}});
    return result;
}

Json plan_json(const ModelPlan & plan) {
    return {{"ok", true}, {"no_alloc", true}, {"backend_profile", plan.backend_profile}, {"layers", plan.layers}, {"buffers", memory_json(plan.buffers)},
        {"gpu_model_bytes", plan.gpu_model_bytes}, {"context_bytes", plan.context_bytes}, {"compute_bytes", plan.compute_bytes},
        {"largest_cpu_tensor_bytes", plan.largest_cpu_tensor_bytes}, {"largest_gpu_source_bytes", plan.largest_gpu_source_bytes},
        {"scope", "Pinned no_alloc read-layout selection, no weight data allocation/inference. Mmap CPU pages and backend/driver overhead require separate reserves; not a measured RSS peak."}};
}
}
