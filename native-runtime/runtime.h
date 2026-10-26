#pragma once
#include "llama.h"
#include "chat.h"
#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace motoai {

enum class WeightBuffers { Native, CpuRepack, CpuRepackQ6, CpuRepackQ4 };
inline const char * weight_buffer_name(WeightBuffers policy) {
    switch (policy) {
        case WeightBuffers::Native: return "native";
        case WeightBuffers::CpuRepack: return "cpu_repack";
        case WeightBuffers::CpuRepackQ6: return "cpu_repack_q6";
        case WeightBuffers::CpuRepackQ4: return "cpu_repack_q4";
    }
    return "invalid";
}

struct Config {
    std::string backend = "cpu"; // Explicit research offload; CPU stays the default.
    int32_t gpu_layers = 0;
    uint32_t context = 4096;
    int32_t threads = 4;
    int32_t threads_batch = 0; // 0 follows decode threads; explicit M3 scheduling control.
    std::vector<int32_t> cpu_affinity; // Empty preserves Android scheduler; otherwise exact allowed subset.
    uint32_t batch = 512;
    uint32_t micro_batch = 128;
    llama_load_mode load_mode = LLAMA_LOAD_MODE_AUTO;
    WeightBuffers weight_buffers = WeightBuffers::Native;
    bool swa_full = true; // Explicit compact-SWA experiment; default keeps the reference layout.
    bool mmap_prefault = true; // Explicit M3 demand-paging control; native mmap only.
    bool transient_repack = false; // Explicit bounded CPU/I8MM prefill experiment.
    uint32_t prefix_cache_mib = 0; // Explicit CPU/qwen35 experiment; RAM only, disabled by default.
    bool tensor_probe = false; // Diagnostic graph observation; excluded from speed profiles.
    bool attention_capture = false; // Bounded, exact layer-6 FA inputs/output for independent replay.
};

struct Request {
    std::vector<common_chat_msg> messages;
    std::string raw_prompt;
    bool chat = true;
    bool thinking = true;
    std::vector<std::string> stop_strings;
    float top_p = -1.0f; // Negative preserves the qualified legacy sampler default.
    bool parse_response = false; // Product adapter: preserve structured reasoning/content.
    bool trace = false;
    bool diagnostics = false;
    bool check_logits = false;
    std::vector<llama_token> score_continuation_ids; // Diagnostic teacher forcing, never sampling output.
    int64_t template_time_unix = -1;
    int32_t max_tokens = 128;
    float temperature = 0.0f;
    uint32_t seed = 42;
    std::string cache_session; // Adapter-owned opaque namespace; never a conversation title.
};

struct BufferMemory {
    std::string name;
    uint64_t model_bytes;
    uint64_t context_bytes;
    uint64_t compute_bytes;
};

struct ModelPlan {
    std::string backend_profile;
    std::vector<BufferMemory> buffers;
    uint64_t gpu_model_bytes = 0;
    uint64_t largest_cpu_tensor_bytes = 0;
    uint64_t largest_gpu_source_bytes = 0;
    uint64_t context_bytes = 0, compute_bytes = 0;
    int32_t layers = 0;
};

struct NodeProbe {
    std::string name, status, fingerprint;
    std::vector<int64_t> shape;
    std::vector<float> head, tail;
    double minimum = 0, maximum = 0, mean = 0, l2 = 0;
    bool finite = false;
    int32_t prompt_end = 0;
    std::string type, data_hex;
    std::vector<int32_t> op_params;
};

struct CpuThreadAffinity {
    int32_t tid = 0;
    std::vector<int32_t> cpus;
};

struct CpuPools;

struct Result {
    bool custom_stop = false;
    bool parsed_response = false;
    common_chat_msg assistant;
    std::vector<int32_t> caller_allowed_cpus;
    std::vector<CpuThreadAffinity> cpu_threads;
    int32_t prompt_tokens = 0;
    int32_t generated_tokens = 0;
    int64_t prepare_us = 0;
    int64_t reset_us = 0;
    int32_t reused_prompt_tokens = 0;
    uint64_t prefix_cache_bytes = 0;
    int64_t cache_capture_us = 0, cache_restore_us = 0;
    std::string cache_status = "disabled";
    int64_t prefill_us = 0;
    int64_t decode_us = 0;
    int64_t ttft_us = 0;
    bool eog = false;
    int64_t first_piece_us = 0;
    std::vector<int64_t> inter_token_us;
    llama_perf_context_data perf{};
    std::string rendered_prompt;
    std::vector<llama_token> prompt_ids;
    std::vector<llama_token> generated_ids;
    struct Chunk { int32_t end; int32_t tokens; int64_t eval_us; };
    std::vector<Chunk> prefill_chunks;
    int32_t logits_checks = 0;
    std::vector<llama_token> first_top_ids;
    std::vector<float> first_top_logits;
    std::vector<BufferMemory> memory_start;
    std::vector<BufferMemory> memory_end;
    std::vector<double> token_nll;
    uint64_t floating_control = 0;
    uint64_t transient_graph_nodes_marked = 0, transient_maximum_requested_bytes = 0, cpu_workspace_bytes = 0;
    std::vector<NodeProbe> node_probes;
};

struct Progress {
    const char * phase;
    int32_t completed;
    int32_t total;
    int64_t elapsed_us;
    int64_t eval_us;
};

struct PreparedPrompt {
    std::string text;
    std::vector<llama_token> tokens;
    common_chat_parser_params parser;
};
PreparedPrompt prepare_prompt(const llama_model * model, const common_chat_templates * templates,
                              const Request & request, uint32_t context);

class Cancelled : public std::runtime_error {
public:
    Cancelled() : std::runtime_error("Operation cancelled") {}
};

class ContextOverflow : public std::runtime_error {
public:
    const uint64_t prompt_tokens, output_reserve, context;
    ContextOverflow(uint64_t prompt, uint64_t reserve, uint64_t size) :
        std::runtime_error("Prompt plus output reserve exceeds configured context: input=" + std::to_string(prompt) +
            ", reserve=" + std::to_string(reserve) + ", context=" + std::to_string(size)),
        prompt_tokens(prompt), output_reserve(reserve), context(size) {}
};

class Runtime {
public:
    ~Runtime();
    using Ticket = uint64_t;
    using Output = std::function<void(const std::string &)>;
    using Phase = std::function<void(const char *)>;
    using ChatCallback = std::function<void(const common_chat_msg &)>;
    using ProgressCallback = std::function<void(const Progress &)>;
    static void initialize(const std::string & backend_directory = "", const std::string & backend = "cpu");
    static std::string backend_profile(const std::string & backend);
    Ticket ticket() const;
    void cancel();
    int64_t load(const std::string & path, const Config & config, Ticket ticket);
    ModelPlan plan(const std::string & path, const Config & config, Ticket ticket);
    std::vector<BufferMemory> memory();
    PreparedPrompt prepare(const Request & request);
    Result generate(const Request & request, Ticket ticket, const Output & output, const Phase & phase = {},
                    const ProgressCallback & progress = {}, const ChatCallback & chat = {});
    void unload();

private:
    static bool abort_callback(void * opaque);
    static bool eval_callback(ggml_tensor * tensor, bool ask, void * opaque);
    void check_cancelled() const;
    void clear();
    std::mutex mutex_;
    std::atomic<Ticket> epoch_{0};
    std::atomic<Ticket> active_ticket_{0};
    Config config_;
    struct PrefixCache {
        std::string session;
        std::vector<llama_token> tokens;
        std::vector<uint8_t> state;
    } prefix_cache_;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model_{nullptr, llama_model_free};
    std::shared_ptr<CpuPools> cpu_pools_; // Context must be freed before its borrowed pools.
    std::unique_ptr<llama_context, decltype(&llama_free)> context_{nullptr, llama_free};
    common_chat_templates_ptr templates_;
    std::vector<NodeProbe> * active_probes_ = nullptr; // Owned by the serialized generate stack.
    int32_t probe_prefix_end_ = 0;
};

}
