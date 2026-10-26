// Metadata-only M3 feasibility tool. It must never allocate or run model weights.
#include "protocol.h"
#include "llama-model.h" // Pinned staging internals, contained in this diagnostic.
#include "llama-ext.h"
#include <algorithm>
#include <fstream>
#include <iostream>
#include <set>

int main(int argc, char ** argv) {
    using namespace motoai;
    if (argc != 4 && argc != 5) {
        std::cerr << "Usage: motoai-load-plan MODEL CONFIG_JSON native|cpu_repack|cpu_repack_q6|cpu_repack_q4 [BACKEND_DIRECTORY]\n";
        return 2;
    }
    Json report{{"ok", false}, {"scope", "Metadata/no_alloc planning only; no weights loaded or inference. Not a measured RSS peak, quality or speed result."}};
    try {
        const std::string policy = argv[3];
        if (policy != "native" && policy != "cpu_repack" && policy != "cpu_repack_q6" && policy != "cpu_repack_q4") throw std::invalid_argument("Unknown weight policy");
        std::ifstream input(argv[2]);
        Json document; input >> document;
        auto config = parse_config(document.contains("config") ? document.at("config") : document);
        config.weight_buffers = policy == "native" ? WeightBuffers::Native : policy == "cpu_repack_q6" ? WeightBuffers::CpuRepackQ6 : policy == "cpu_repack_q4" ? WeightBuffers::CpuRepackQ4 : WeightBuffers::CpuRepack;
        Runtime::initialize(argc == 5 ? argv[4] : "");
        auto * device = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        if (!device) throw std::runtime_error("No CPU backend");
        auto get_extras = reinterpret_cast<ggml_backend_dev_get_extra_bufts_t>(ggml_backend_reg_get_proc_address(
            ggml_backend_dev_backend_reg(device), "ggml_backend_dev_get_extra_bufts"));
        Json extras = Json::array();
        if (get_extras) {
            auto * types = get_extras(device);
            for (; types && *types; ++types) extras.push_back(ggml_backend_buft_name(*types));
        }
        report["available_cpu_extra_buffers"] = extras;
        if (policy != "native" && (extras.empty() ||
            std::any_of(extras.begin(), extras.end(), [](const Json & name) { return name != "CPU_REPACK"; }))) {
            throw std::runtime_error("cpu_repack requires CPU_REPACK-only extras; build with KleidiAI disabled. No implicit fallback.");
        }
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.no_alloc = true;
        params.use_extra_bufts = policy != "native";
#ifdef LLAMA_MOTOAI_REPACK_FILTER
        params.cpu_repack_type_mask = policy == "cpu_repack_q6" ? uint64_t(1) << GGML_TYPE_Q6_K : policy == "cpu_repack_q4" ? uint64_t(1) << GGML_TYPE_Q4_K : 0;
#else
        if (policy == "cpu_repack_q6" || policy == "cpu_repack_q4") throw std::runtime_error("Selective repack API not compiled");
#endif
        // This pinned upstream asserts on no_alloc+mmap. Plan the read-layout
        // buffer selection without reading weights; mmap phase budgets below
        // remain explicit hypotheses, not an exact mmap allocation plan.
        params.load_mode = LLAMA_LOAD_MODE_NONE;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(argv[1], params), llama_model_free);
        if (!model) throw std::runtime_error("Metadata plan failed");
        if (!model->hparams.no_alloc) throw std::runtime_error("Metadata-only invariant failed");
        if (config.context > static_cast<uint32_t>(llama_model_n_ctx_train(model.get()))) {
            throw std::invalid_argument("Context exceeds model training limit");
        }
        auto cp = llama_context_default_params();
        cp.n_ctx = config.context; cp.n_batch = config.batch; cp.n_ubatch = config.micro_batch;
        cp.n_threads = config.threads; cp.n_threads_batch = config.threads;
        cp.type_k = GGML_TYPE_F16; cp.type_v = GGML_TYPE_F16;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(model.get(), cp), llama_free);
        if (!context) throw std::runtime_error("Context plan failed");
        Json buffers = Json::array(), tensors = Json::array();
        uint64_t packed = 0, state = 0, scratch = 0, largest_native = 0, largest_packed_source = 0;
        for (const auto & item : llama_get_memory_breakdown(context.get())) {
            const std::string name = ggml_backend_buft_name(item.first);
            buffers.push_back({{"name", name}, {"model_bytes", item.second.model},
                               {"context_bytes", item.second.context}, {"compute_bytes", item.second.compute}});
            if (name == "CPU_REPACK") packed += item.second.model;
            state += item.second.context; scratch += item.second.compute;
        }
        std::set<const ggml_tensor *> seen;
        for (const auto & entry : model->tensors_by_name) {
            const auto * tensor = entry.second;
            if (!seen.insert(tensor).second) continue;
            if (!tensor->buffer) throw std::runtime_error("Unplanned tensor buffer");
            const std::string name = ggml_backend_buft_name(ggml_backend_buffer_get_type(tensor->buffer));
            const auto bytes = static_cast<uint64_t>(ggml_nbytes(tensor));
            if (name == "CPU_REPACK") largest_packed_source = std::max(largest_packed_source, bytes);
            else largest_native = std::max(largest_native, bytes);
            tensors.push_back({{"name", entry.first}, {"type", ggml_type_name(tensor->type)},
                               {"buffer", name}, {"bytes", bytes}});
        }
        auto configuration = config_json(config, 0);
        configuration.erase("weight_buffer_policy");
        configuration.erase("all_extra_buffers_disabled");
        configuration.erase("load_us");
        configuration["use_extra_bufts"] = params.use_extra_bufts;
        report.update({{"ok", true}, {"weight_policy", policy}, {"no_alloc", true},
                       {"runtime_variant", config_json(config, 0)["runtime_variant"]}, {"buffers", buffers}, {"tensors", tensors},
                       {"packed_model_bytes", packed}, {"context_bytes", state}, {"compute_bytes", scratch},
                       {"largest_native_tensor_bytes", largest_native}, {"largest_packed_source_tensor_bytes", largest_packed_source},
                       {"requested_config", configuration}, {"planning_load_mode", "no_alloc-read-layout"}});
        // Native mapped pages remain clean/reclaimable. These phase upper budgets
        // are a hypothesis for later app admission, not a proven live memory cap.
        if (config.load_mode == LLAMA_LOAD_MODE_MMAP) {
            report["mapped_phase_budget_before_headroom"] = packed +
                std::max(largest_packed_source, largest_native + state + scratch);
        }
    } catch (const std::exception & error) { report["error"] = error.what(); }
    std::cout << report.dump(2) << '\n';
    return report["ok"].get<bool>() ? 0 : 1;
}
