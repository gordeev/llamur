#include "runtime.h"
#include "model-file.h"
#include "response-stream.h"
#include "cpu-scheduling.h"
#include "common.h"
#include "sampling.h"
#include "gguf.h"
#include "llama-model.h" // Pinned model-buffer validation; contained in the native boundary.
// Pinned upstream staging API; contained here, never exposed over JNI.
#include "../third_party/llama.cpp/src/llama-ext.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <limits>
#include <iomanip>
#include <sstream>
#include <cstring>
#if defined(__ANDROID__)
#include <malloc.h>
#endif

namespace motoai {
namespace {
int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
std::vector<BufferMemory> allocations(const llama_context * context) {
    std::vector<BufferMemory> result;
    for (const auto & item : llama_get_memory_breakdown(context)) {
        result.push_back({ggml_backend_buft_name(item.first), item.second.model, item.second.context, item.second.compute});
    }
    return result;
}
std::vector<ggml_backend_dev_t> model_devices(const std::string & path, const Config & config) {
    std::vector<ggml_backend_dev_t> selected_devices;
    if (!config.swa_full)
        throw std::invalid_argument("Compact SWA failed fixed Gemma quality gates; full F16 SWA is required");
    if (config.backend != "cpu" && config.backend != "vulkan" && config.backend != "opencl")
        throw std::invalid_argument("Unknown backend");
    if ((config.backend == "cpu") != (config.gpu_layers == 0) || config.gpu_layers < 0 || config.gpu_layers > 128)
        throw std::invalid_argument("Invalid backend/layer combination");
    if (config.backend != "cpu") {
        if (config.weight_buffers != WeightBuffers::Native || config.transient_repack ||
            config.load_mode != LLAMA_LOAD_MODE_MMAP || config.mmap_prefault)
            throw std::invalid_argument("GPU experiment requires native mmap without full prefault or CPU packing");
        const auto name = config.backend == "vulkan" ? "Vulkan" : "OpenCL";
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            auto device = ggml_backend_dev_get(i);
            const auto type = ggml_backend_dev_type(device);
            if (std::string(ggml_backend_reg_name(ggml_backend_dev_backend_reg(device))) == name &&
                (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU)) selected_devices.push_back(device);
        }
        if (selected_devices.size() != 1) throw std::invalid_argument("GPU profile requires exactly one selected device");
        if (config.backend == "opencl") {
            // This pinned OpenCL implementation stores BF16 as F16, which can
            // change represented weights. Reject the whole profile until a
            // separately validated lossless BF16 route exists (even partial offload).
            ggml_context * metadata = nullptr;
            const gguf_init_params header_params{true, &metadata};
            auto input_file=open_model_file(path);
            std::unique_ptr<gguf_context, decltype(&gguf_free)> header(gguf_init_from_file_ptr(input_file.get(), header_params), gguf_free);
            std::unique_ptr<ggml_context, decltype(&ggml_free)> tensors(metadata, ggml_free);
            if (!header) throw std::invalid_argument("Cannot inspect GPU model tensor types");
            for (int64_t i = 0; i < gguf_get_n_tensors(header.get()); ++i)
                if (gguf_get_tensor_type(header.get(), i) == GGML_TYPE_BF16)
                    throw std::invalid_argument("OpenCL BF16-to-F16 storage violates the unchanged-weight policy");
        }
        selected_devices.push_back(nullptr);
    }
    if (selected_devices.empty()) selected_devices.push_back(nullptr); // CPU explicitly excludes every registered GPU.
    return selected_devices;
}
// Follow only view-like Q transformations, never traverse the model graph.
bool layer6_query(const ggml_tensor * tensor, int depth = 0) {
    if (!tensor || depth > 6) return false;
    if (std::string(tensor->name) == "Qcur_pos-6") return true;
    return (tensor->op == GGML_OP_VIEW || tensor->op == GGML_OP_RESHAPE || tensor->op == GGML_OP_PERMUTE ||
            tensor->op == GGML_OP_CONT) && layer6_query(tensor->src[0], depth + 1);
}
NodeProbe capture_attention_tensor(const ggml_tensor * tensor, const std::string & name, int32_t prompt_end) {
    NodeProbe probe;
    probe.name = name;
    probe.prompt_end = prompt_end;
    probe.type = ggml_type_name(tensor->type);
    probe.shape.assign(tensor->ne, tensor->ne + GGML_MAX_DIMS);
    const auto count = ggml_nelements(tensor);
    const auto element = ggml_type_size(tensor->type);
    if ((tensor->type != GGML_TYPE_F32 && tensor->type != GGML_TYPE_F16) || count <= 0 || count > 2097152 ||
        tensor->nb[0] != element) {
        probe.status = "unsupported_type_layout_or_size";
        return probe;
    }
    // Canonical dimension-0-fast bytes; source KV/Q tensors can be permuted views.
    std::vector<uint8_t> bytes(count * element);
    size_t offset = 0;
    for (int64_t i3 = 0; i3 < tensor->ne[3]; ++i3)
        for (int64_t i2 = 0; i2 < tensor->ne[2]; ++i2)
            for (int64_t i1 = 0; i1 < tensor->ne[1]; ++i1) {
                const auto size = tensor->ne[0] * element;
                ggml_backend_tensor_get(tensor, bytes.data() + offset,
                    i3 * tensor->nb[3] + i2 * tensor->nb[2] + i1 * tensor->nb[1], size);
                offset += size;
            }
    constexpr char hex[] = "0123456789abcdef";
    probe.data_hex.reserve(bytes.size() * 2);
    for (const auto byte : bytes) { probe.data_hex += hex[byte >> 4]; probe.data_hex += hex[byte & 15]; }
    probe.status = "captured_exact_bytes";
    return probe;
}
}

std::string Runtime::backend_profile(const std::string & backend) {
    if (backend == "cpu") return "cpu";
    if (backend != "opencl") return "upstream";
    const auto registry = ggml_backend_reg_by_name("OpenCL");
    if (!registry) return "unavailable";
    const auto profile = reinterpret_cast<const char * (*)()>(
        ggml_backend_reg_get_proc_address(registry, "ggml_backend_opencl_math_profile"));
    return profile ? profile() : "upstream";
}

void Runtime::initialize(const std::string & backend_directory, const std::string & backend) {
    if (backend != "cpu" && backend != "vulkan" && backend != "opencl") throw std::invalid_argument("Unknown backend");
    static std::mutex registry_mutex;
    std::lock_guard<std::mutex> registry_guard(registry_mutex);
    static std::once_flag once;
    std::call_once(once, [&] {
#ifdef GGML_MOTOAI_SELECTIVE_BACKEND_LOAD
        if (!ggml_backend_reg_by_name("CPU"))
            ggml_backend_load_by_name("cpu", backend_directory.empty() ? nullptr : backend_directory.c_str());
        if (!ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) throw std::runtime_error("No usable CPU backend");
#else
        if (backend_directory.empty()) ggml_backend_load_all();
        else ggml_backend_load_all_from_path(backend_directory.c_str());
#endif
        llama_backend_init();
    });
    if (backend != "cpu") {
#ifdef GGML_MOTOAI_SELECTIVE_BACKEND_LOAD
        const char * registry = backend == "vulkan" ? "Vulkan" : "OpenCL";
        if (!ggml_backend_reg_by_name(registry) &&
            !ggml_backend_load_by_name(backend.c_str(), backend_directory.empty() ? nullptr : backend_directory.c_str()))
            throw std::runtime_error("Requested GPU backend unavailable; select CPU explicitly");
#else
        throw std::invalid_argument("Selective GPU backend loading is not compiled");
#endif
    }
}

Runtime::Ticket Runtime::ticket() const { return epoch_.load(); }
std::vector<BufferMemory> Runtime::memory() {
    std::lock_guard<std::mutex> guard(mutex_);
    return context_ ? allocations(context_.get()) : std::vector<BufferMemory>{};
}
void Runtime::cancel() { epoch_.fetch_add(1); }
bool Runtime::abort_callback(void * opaque) {
    auto & self = *static_cast<Runtime *>(opaque);
    return self.epoch_.load() != self.active_ticket_.load();
}
bool Runtime::eval_callback(ggml_tensor * tensor, bool ask, void * opaque) {
    auto & self = *static_cast<Runtime *>(opaque);
    auto * probes = self.active_probes_;
    if (!probes || probes->size() >= 96) return !ask;
    if (self.config_.attention_capture && tensor->op == GGML_OP_FLASH_ATTN_EXT && layer6_query(tensor->src[0]) &&
        std::none_of(probes->begin(), probes->end(), [](const NodeProbe & p) { return p.name == "attention.output-6"; })) {
        if (ask) return true;
        for (int i = 0; i < 5; ++i) {
            if (tensor->src[i]) probes->push_back(capture_attention_tensor(tensor->src[i],
                std::string("attention.") + std::vector<std::string>{"q", "k", "v", "mask", "sinks"}[i] + "-6", self.probe_prefix_end_));
        }
        auto output = capture_attention_tensor(tensor, "attention.output-6", self.probe_prefix_end_);
        output.op_params.assign(tensor->op_params, tensor->op_params + GGML_MAX_OP_PARAMS / sizeof(int32_t));
        probes->push_back(std::move(output));
        return true;
    }
    const std::string name = tensor->name;
    static const std::vector<std::string> selected = {"inp_embd", "inp_scaled", "inp_per_layer_selected", "per_layer_proj",
        "attn_norm-0", "Qcur-0", "Qcur_normed-0", "Qcur_pos-0", "attn_out-0", "ffn_out-0",
        "per_layer_embd_out-0", "l_out-0", "l_out-20", "l_out-40", "result_norm", "result_output",
        // First differing layer in the cross-CPU prefix control. Diagnostic only.
        "attn_norm-6", "Qcur-6", "Qcur_normed-6", "Qcur_pos-6", "Kcur-6", "Vcur-6",
        "Kcur_normed-6", "Vcur_normed-6", "Kcur_pos-6", "kqv_out-6", "attn_post_norm-6",
        "attn_out-6", "ffn_norm-6", "ffn_up-6", "ffn_gate-6", "ffn_geglu-6", "ffn_gelu-6",
        "ffn_geglu_erf-6", "ffn_gelu_erf-6", "ffn_down-6", "ffn_out-6", "ffn_post_norm-6",
        // Hybrid recurrent path: identify the first platform-dependent boundary.
        "model.input_embed", "linear_attn_qkv_mixed-0", "z-0", "beta-0", "beta_sigmoid-0", "alpha-0",
        "a_softplus-0", "gate-0", "state_predelta-0", "conv_output_raw-0", "conv_output_silu-0",
        "q_conv-0", "k_conv-0", "v_conv-0", "q_conv_predelta-0", "k_conv_predelta-0", "v_conv_predelta-0",
        "final_output-0", "linear_attn_out-0", "attn_residual-0", "attn_post_norm-0", "ffn_norm-0",
        "ffn_gate-0", "ffn_up-0", "ffn_silu-0", "post_ffn-0"};
    if ((std::find(selected.begin(), selected.end(), name) == selected.end() && name.rfind("l_out-", 0) != 0) ||
        std::any_of(probes->begin(), probes->end(), [&](const NodeProbe & probe) { return probe.name == name; })) return !ask;
    if (ask) return true;
    NodeProbe probe;
    probe.name = name;
    probe.prompt_end = self.probe_prefix_end_;
    probe.shape.assign(tensor->ne, tensor->ne + GGML_MAX_DIMS);
    const auto count = ggml_nelements(tensor);
    if (tensor->type != GGML_TYPE_F32 || !ggml_is_contiguous(tensor) || count <= 0 || count > 131072) {
        probe.status = "unsupported_type_layout_or_size";
    } else {
        std::vector<float> values(static_cast<size_t>(count));
        ggml_backend_tensor_get(tensor, values.data(), 0, values.size() * sizeof(float));
        uint64_t hash = 14695981039346656037ULL;
        const auto * bytes = reinterpret_cast<const uint8_t *>(values.data());
        for (size_t i = 0; i < values.size() * sizeof(float); ++i) { hash ^= bytes[i]; hash *= 1099511628211ULL; }
        std::ostringstream digest;
        digest << std::hex << std::setw(16) << std::setfill('0') << hash;
        probe.fingerprint = digest.str();
        probe.minimum = std::numeric_limits<double>::infinity();
        probe.maximum = -std::numeric_limits<double>::infinity();
        probe.finite = true;
        for (const float value : values) {
            if (!std::isfinite(value)) { probe.finite = false; continue; }
            probe.minimum = std::min(probe.minimum, static_cast<double>(value));
            probe.maximum = std::max(probe.maximum, static_cast<double>(value));
            probe.mean += value; probe.l2 += static_cast<double>(value) * value;
        }
        probe.mean /= count;
        probe.l2 = std::sqrt(probe.l2);
        const auto sample = std::min<size_t>(32, values.size());
        probe.head.assign(values.begin(), values.begin() + sample);
        probe.tail.assign(values.end() - sample, values.end());
        probe.status = "captured";
    }
    probes->push_back(std::move(probe));
    return true;
}
void Runtime::check_cancelled() const {
    if (epoch_.load() != active_ticket_.load()) throw Cancelled();
}
Runtime::~Runtime() { clear(); }

void Runtime::clear() {
    prefix_cache_ = {};
    templates_.reset();
    context_.reset();
    cpu_pools_.reset();
    model_.reset();
#if defined(__ANDROID__)
    // Cancelled large loads can leave freed tokenizer/weight allocations in
    // bionic's allocator caches. Return unused pages before another admission.
    // M_PURGE is available at the project's minimum API28; live buffers stay intact.
    mallopt(M_PURGE, 0);
#endif
}

int64_t Runtime::load(const std::string & path, const Config & config, Ticket operation_ticket) {
    std::lock_guard<std::mutex> guard(mutex_);
    active_ticket_.store(operation_ticket);
    check_cancelled();
    CallerAffinity caller_affinity(!config.cpu_affinity.empty());
    validate_cpu_scheduling(config, caller_affinity.allowed());
    if (config.context < 128 || config.context > 262144 || config.threads < 1 || config.threads > 64 ||
        config.batch < 1 || config.batch > 2048 || config.micro_batch < 1 || config.micro_batch > config.batch) {
        throw std::invalid_argument("Invalid context, threads or batch configuration");
    }
    if (config.load_mode != LLAMA_LOAD_MODE_AUTO && config.load_mode != LLAMA_LOAD_MODE_NONE &&
        config.load_mode != LLAMA_LOAD_MODE_MMAP) throw std::invalid_argument("Unsupported load mode");
    if (config.prefix_cache_mib) {
        if(config.prefix_cache_mib<64 || config.prefix_cache_mib>256 || config.backend!="cpu" ||
           config.weight_buffers!=WeightBuffers::Native || config.context>8192 ||
           config.batch!=512 || config.micro_batch!=128 || config.threads>4 || config.threads_batch>4)
            throw std::invalid_argument("Prefix cache requires explicit bounded native CPU geometry");
        ggml_context * tensors=nullptr;
        auto input_file=open_model_file(path);
        std::unique_ptr<gguf_context,decltype(&gguf_free)> header(gguf_init_from_file_ptr(input_file.get(),{true,&tensors}),gguf_free);
        std::unique_ptr<ggml_context,decltype(&ggml_free)> metadata(tensors,ggml_free);
        if(!header) throw std::invalid_argument("Cannot inspect prefix-cache model architecture");
        const auto key=gguf_find_key(header.get(),"general.architecture");
        if(key<0 || gguf_get_kv_type(header.get(),key)!=GGUF_TYPE_STRING)
            throw std::invalid_argument("Missing prefix-cache architecture");
        const std::string architecture=gguf_get_val_str(header.get(),key);
#ifdef LLAMA_MOTOAI_KEEP_KV_LAYOUT
        if(architecture!="qwen35" && architecture!="gemma4")
#else
        if(architecture!="qwen35")
#endif
            throw std::invalid_argument("Prefix cache requires a qualified architecture and exact state layout API");
    }
    if (config.weight_buffers != WeightBuffers::Native && config.weight_buffers != WeightBuffers::CpuRepack && config.weight_buffers != WeightBuffers::CpuRepackQ6 && config.weight_buffers != WeightBuffers::CpuRepackQ4) {
        throw std::invalid_argument("Unsupported weight buffer policy");
    }
#ifndef LLAMA_MOTOAI_REPACK_FILTER
    if (config.weight_buffers == WeightBuffers::CpuRepackQ6 || config.weight_buffers == WeightBuffers::CpuRepackQ4) throw std::invalid_argument("Selective repack is not compiled; no implicit fallback");
#endif
    if (!config.mmap_prefault) {
#ifndef LLAMA_MOTOAI_MMAP_PREFAULT_CONTROL
        throw std::invalid_argument("Mmap prefault control is not compiled; no implicit fallback");
#endif
        if (config.load_mode != LLAMA_LOAD_MODE_MMAP || config.weight_buffers != WeightBuffers::Native) {
            throw std::invalid_argument("Demand-paging control requires native buffers and explicit mmap");
        }
    }
    if (config.weight_buffers != WeightBuffers::Native) {
        // This experiment has a distinct mmap memory budget; never enable all
        // extras in a mixed KleidiAI build or silently fall back to native.
        if (config.load_mode != LLAMA_LOAD_MODE_MMAP) throw std::invalid_argument("Experimental CPU_REPACK requires explicit mmap");
        auto * device = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        if (!device) throw std::runtime_error("No CPU backend");
        auto extras = reinterpret_cast<ggml_backend_dev_get_extra_bufts_t>(ggml_backend_reg_get_proc_address(
            ggml_backend_dev_backend_reg(device), "ggml_backend_dev_get_extra_bufts"));
        auto * types = extras ? extras(device) : nullptr;
        bool repack = false;
        for (; types && *types; ++types) {
            if (std::string(ggml_backend_buft_name(*types)) != "CPU_REPACK") {
                throw std::runtime_error("CPU_REPACK requires a CPU_REPACK-only build; other extra weight buffers are forbidden");
            }
            repack = true;
        }
        if (!repack) throw std::runtime_error("CPU_REPACK is unavailable; no implicit native fallback");
        if (config.weight_buffers == WeightBuffers::CpuRepackQ4) {
            // DOTPROD/8x4 has observed strict-bit differences on host. This
            // candidate is limited to the separately screened I8MM/8x8 path.
            const auto get_features = reinterpret_cast<ggml_backend_get_features_t>(ggml_backend_reg_get_proc_address(
                ggml_backend_dev_backend_reg(device), "ggml_backend_get_features"));
            bool neon = false, i8mm = false;
            if (get_features) for (auto * feature = get_features(ggml_backend_dev_backend_reg(device)); feature && feature->name; ++feature) {
                const bool enabled = feature->value && std::string(feature->value) == "1";
                if (std::string(feature->name) == "NEON") neon = enabled;
                if (std::string(feature->name) == "MATMUL_INT8") i8mm = enabled;
            }
            if (!neon || !i8mm) throw std::invalid_argument("Experimental Q4_K-only repack requires I8MM CPU support; no implicit fallback");
        }
    }
    if (config.transient_repack) {
#ifndef LLAMA_MOTOAI_TRANSIENT_REPACK
        throw std::invalid_argument("Transient repack is not compiled; no implicit fallback");
#else
#ifdef LLAMA_MOTOAI_TRANSIENT_MIN_ROWS
        constexpr uint32_t transient_min_rows = LLAMA_MOTOAI_TRANSIENT_MIN_ROWS;
#else
        constexpr uint32_t transient_min_rows = 4;
#endif
        if (config.weight_buffers != WeightBuffers::Native || config.load_mode != LLAMA_LOAD_MODE_MMAP ||
            config.context > 32768 || config.batch > 512 || config.micro_batch < transient_min_rows || config.micro_batch > 128 || config.threads > 4 || config.threads_batch > 4)
            throw std::invalid_argument("Transient repack requires bounded native mmap CPU geometry");
        auto * device = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        if (!device) throw std::runtime_error("No CPU backend");
        auto reg = ggml_backend_dev_backend_reg(device);
        auto features = reinterpret_cast<ggml_backend_get_features_t>(ggml_backend_reg_get_proc_address(reg, "ggml_backend_get_features"));
        bool neon = false, i8mm = false;
        if (features) for (auto * f = features(reg); f && f->name; ++f) {
            const bool enabled = f->value && std::string(f->value) == "1";
            if (std::string(f->name) == "NEON") neon = enabled;
            if (std::string(f->name) == "MATMUL_INT8") i8mm = enabled;
        }
        if (!neon || !i8mm || !ggml_backend_reg_get_proc_address(reg, "ggml_backend_cpu_mark_transient_repack"))
            throw std::invalid_argument("Transient repack requires I8MM and explicit workspace API; no fallback");
#endif
    }
    // Validate the requested registry and lossless-storage policy before touching
    // the old model. Never substitute a different GPU backend behind the client.
    auto selected_devices = model_devices(path, config);
    const auto started = now_us();
    clear();
    auto params = llama_model_default_params();
    // Pinned KleidiAI's Q6_K/Q8_0 extra weight buffers requantize values.
    // A CPU tensor override still reconsiders extra bufts in this upstream API.
    // Native remains default. Experimental repack is allowed only after the
    // runtime registry above proves that no other extra buffers can be chosen.
    params.use_extra_bufts = config.weight_buffers != WeightBuffers::Native;
#ifdef LLAMA_MOTOAI_MMAP_PREFAULT_CONTROL
    params.mmap_prefault = config.mmap_prefault;
#endif
#ifdef LLAMA_MOTOAI_REPACK_FILTER
    params.cpu_repack_type_mask = config.weight_buffers == WeightBuffers::CpuRepackQ6 ? uint64_t(1) << GGML_TYPE_Q6_K :
        config.weight_buffers == WeightBuffers::CpuRepackQ4 ? uint64_t(1) << GGML_TYPE_Q4_K : 0;
#endif
    params.n_gpu_layers = config.gpu_layers;
    if (!selected_devices.empty()) params.devices = selected_devices.data();
    params.load_mode = config.load_mode;
    params.progress_callback_user_data = this;
    params.progress_callback = [](float, void * self) { return !abort_callback(self); };
    auto descriptor_file=descriptor_model_path(path)?open_model_file(path):ModelFile(nullptr,fclose);
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
        descriptor_file?llama_model_load_from_file_ptr(descriptor_file.get(),params):llama_model_load_from_file(path.c_str(), params), llama_model_free);
    check_cancelled();
    if (!model) throw std::runtime_error("Model load failed");
    const auto trained = llama_model_n_ctx_train(model.get());
    if (trained > 0 && config.context > static_cast<uint32_t>(trained)) {
        throw std::invalid_argument("Requested context exceeds model training context");
    }
    auto context_params = llama_context_default_params();
#ifdef LLAMA_MOTOAI_TRANSIENT_REPACK
    context_params.cpu_transient_repack = config.transient_repack;
#endif
    context_params.swa_full = config.swa_full;
    context_params.n_ctx = config.context;
    context_params.n_batch = config.batch;
    context_params.n_ubatch = config.micro_batch;
    context_params.n_threads = config.threads;
    context_params.n_threads_batch = config.threads_batch ? config.threads_batch : config.threads;
    context_params.no_perf = false;
    context_params.offload_kqv = backend_profile(config.backend) != "matmul_only";
    context_params.type_k = GGML_TYPE_F16;
    context_params.type_v = GGML_TYPE_F16;
    context_params.abort_callback = abort_callback;
    context_params.abort_callback_data = this;
    if (config.tensor_probe || config.attention_capture) {
        context_params.cb_eval = eval_callback;
        context_params.cb_eval_user_data = this;
    }
    std::unique_ptr<llama_context, decltype(&llama_free)> context(
        llama_init_from_model(model.get(), context_params), llama_free);
    check_cancelled();
    if (!context) throw std::runtime_error("Context allocation failed");
    if (config.weight_buffers != WeightBuffers::Native) {
        uint64_t packed = 0;
        for (const auto & item : allocations(context.get())) {
            if (item.name == "CPU_REPACK") packed += item.model_bytes;
            else if (item.model_bytes && item.name != "CPU" && item.name != "CPU_Mapped") {
                throw std::runtime_error("Unexpected model buffer in CPU_REPACK profile: " + item.name);
            }
        }
        if (!packed) throw std::runtime_error("No model weights selected CPU_REPACK; no implicit native fallback");
        if (config.weight_buffers == WeightBuffers::CpuRepackQ6 || config.weight_buffers == WeightBuffers::CpuRepackQ4) for (const auto & item : model->tensors_by_name) {
            const auto * tensor = item.second;
            const auto expected = config.weight_buffers == WeightBuffers::CpuRepackQ6 ? GGML_TYPE_Q6_K : GGML_TYPE_Q4_K;
            if (tensor->buffer && std::string(ggml_backend_buffer_name(tensor->buffer)) == "CPU_REPACK" && tensor->type != expected) {
                throw std::runtime_error("Selective repack selected a tensor outside the requested type filter");
            }
        }
    }
    if (config.backend != "cpu") {
        bool offloaded = false;
        const std::string device_buffer = ggml_backend_buft_name(ggml_backend_dev_buffer_type(selected_devices.front()));
        for (const auto & item : allocations(context.get()))
            if (item.model_bytes && item.name == device_buffer) offloaded = true;
        if (!offloaded) throw std::runtime_error("Requested offload allocated no GPU model weights");
    }
    if (llama_n_ctx(context.get()) < config.context) throw std::runtime_error("Runtime allocated less context than requested");
    auto templates = common_chat_templates_init(model.get(), "");
    check_cancelled();
    auto pools = make_cpu_pools(config);
    config_ = config;
    if (pools) llama_attach_threadpool(context.get(), pools->decode, pools->batch);
    model_ = std::move(model);
    context_ = std::move(context);
    cpu_pools_ = std::move(pools);
    templates_ = std::move(templates);
    return now_us() - started;
}

PreparedPrompt prepare_prompt(const llama_model * model, const common_chat_templates * templates,
                              const Request & request, uint32_t context) {
    if (request.max_tokens < 1 || request.max_tokens > static_cast<int32_t>(context) ||
        !std::isfinite(request.temperature) || request.temperature < 0 || request.temperature > 5) {
        throw std::invalid_argument("Invalid sampling or output budget");
    }
    constexpr size_t max_input_bytes = 2 * 1024 * 1024;
    if (request.raw_prompt.size() > max_input_bytes || request.messages.size() > 1024) {
        throw std::invalid_argument("Request exceeds input limit");
    }
    size_t input_bytes = 0;
    for (const auto & message : request.messages) {
        if (message.role != "system" && message.role != "user" && message.role != "assistant") {
            throw std::invalid_argument("Unsupported message role");
        }
        if (message.content.size() > max_input_bytes - input_bytes) throw std::invalid_argument("Request exceeds input limit");
        input_bytes += message.content.size();
        if (message.reasoning_content.size() > max_input_bytes - input_bytes) throw std::invalid_argument("Request exceeds input limit");
        input_bytes += message.reasoning_content.size();
    }
    const auto * vocab = llama_model_get_vocab(model);
    if (request.chat) {
#ifndef LLAMA_MOTOAI_INPUT_RANGES
        // Flattening marked Jinja parts would parse user-supplied control markers.
        // Until segmented tokenization preserves whitespace/BPE boundaries, reject
        // this unsupported input explicitly rather than silently rewriting it.
        for (const auto & message : request.messages) {
            if (common_tokenize(vocab, message.content, false, true) !=
                common_tokenize(vocab, message.content, false, false) ||
                common_tokenize(vocab, message.reasoning_content, false, true) != common_tokenize(vocab, message.reasoning_content, false, false)) {
                throw std::invalid_argument("Literal control tokens in chat message content are not supported yet");
            }
        }
#endif
    }
    std::string prompt = request.raw_prompt;
    common_chat_parser_params parser;
#ifdef LLAMA_MOTOAI_INPUT_RANGES
    std::vector<llama_tokenize_input_range> input_ranges;
#endif
    if (request.chat) {
        if (request.messages.empty()) throw std::invalid_argument("No chat messages");
        common_chat_templates_inputs inputs;
        inputs.messages = request.messages;
        inputs.use_jinja = true;
        inputs.enable_thinking = request.thinking;
        if (request.parse_response) inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
        if (request.template_time_unix >= 0) inputs.now = std::chrono::system_clock::time_point(std::chrono::seconds(request.template_time_unix));
        auto applied = common_chat_templates_apply(templates, inputs);
        if (request.parse_response) {
            parser.format = applied.format;
            parser.generation_prompt = common_chat_input_tokenize(vocab, applied.generation_prompt);
            parser.parser = std::move(applied.parser);
        }
        const auto & rendered = applied.prompt;
        prompt = rendered.str();
#ifdef LLAMA_MOTOAI_INPUT_RANGES
        uint32_t offset = 0;
        for (const auto & part : rendered.parts) {
            if (part.is_input && !part.text.empty()) input_ranges.push_back({offset, static_cast<uint32_t>(part.text.size())});
            offset += static_cast<uint32_t>(part.text.size());
        }
#endif
    }
    if (prompt.size() > 4 * max_input_bytes) throw std::invalid_argument("Rendered template exceeds input limit");
    auto tokens = common_tokenize(vocab, prompt, true, true);
#ifdef LLAMA_MOTOAI_INPUT_RANGES
    if (request.chat && !input_ranges.empty()) {
        std::vector<llama_token> protected_tokens(prompt.size() + 2);
        auto count = llama_tokenize_with_input_ranges(vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
            protected_tokens.data(), static_cast<int32_t>(protected_tokens.size()), true,
            input_ranges.data(), static_cast<int32_t>(input_ranges.size()));
        if (count == std::numeric_limits<int32_t>::min()) throw std::invalid_argument("Token count overflow");
        if (count < 0) {
            protected_tokens.resize(static_cast<size_t>(-count));
            count = llama_tokenize_with_input_ranges(vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
                protected_tokens.data(), static_cast<int32_t>(protected_tokens.size()), true,
                input_ranges.data(), static_cast<int32_t>(input_ranges.size()));
            if (count < 0) throw std::runtime_error("Input-range tokenization size changed");
        }
        protected_tokens.resize(static_cast<size_t>(count));
        tokens = std::move(protected_tokens);
    }
#endif
    if (tokens.empty()) throw std::invalid_argument("Empty tokenized prompt");
    if (tokens.size() + static_cast<uint64_t>(request.max_tokens) > context) throw ContextOverflow(tokens.size(), request.max_tokens, context);
    return {std::move(prompt), std::move(tokens), std::move(parser)};
}

ModelPlan Runtime::plan(const std::string & path, const Config & config, Ticket operation_ticket) {
    std::lock_guard<std::mutex> guard(mutex_);
    active_ticket_.store(operation_ticket);
    check_cancelled();
    if (config.weight_buffers != WeightBuffers::Native || config.transient_repack || config.prefix_cache_mib ||
        config.context < 128 || config.context > (config.backend == "cpu" ? 32768u : 8192u) || config.batch < 1 || config.batch > 512 ||
        config.micro_batch < 1 || config.micro_batch > 128 || config.micro_batch > config.batch ||
        config.threads < 1 || config.threads > 4 || config.threads_batch < 0 || config.threads_batch > 4)
        throw std::invalid_argument("Metadata planner supports bounded native geometry only");
    auto devices = model_devices(path, config);
    auto params = llama_model_default_params();
    params.no_alloc = true;
    params.use_extra_bufts = false;
    params.n_gpu_layers = config.gpu_layers;
    params.devices = devices.data();
    params.progress_callback_user_data = this;
    params.progress_callback = [](float, void * self) { return !abort_callback(self); };
    // The pinned API asserts for no_alloc+mmap. Select the same model buffers
    // using a no_alloc read layout, without reading or allocating weight data.
    params.load_mode = LLAMA_LOAD_MODE_NONE;
    auto descriptor_file=descriptor_model_path(path)?open_model_file(path):ModelFile(nullptr,fclose);
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
        descriptor_file?llama_model_load_from_file_ptr(descriptor_file.get(),params):llama_model_load_from_file(path.c_str(), params), llama_model_free);
    check_cancelled();
    if (!model || !model->hparams.no_alloc) throw std::runtime_error("Metadata-only model planning failed");
    if (config.context > static_cast<uint32_t>(llama_model_n_ctx_train(model.get())))
        throw std::invalid_argument("Planned context exceeds model training context");
    auto cp = llama_context_default_params();
    cp.swa_full = config.swa_full;
    cp.n_ctx = config.context; cp.n_batch = config.batch; cp.n_ubatch = config.micro_batch;
    cp.n_threads = config.threads; cp.n_threads_batch = config.threads_batch ? config.threads_batch : config.threads;
    cp.offload_kqv = backend_profile(config.backend) != "matmul_only";
    cp.type_k = GGML_TYPE_F16; cp.type_v = GGML_TYPE_F16;
    std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(model.get(), cp), llama_free);
    check_cancelled();
    if (!context) throw std::runtime_error("Metadata-only context planning failed");
    ModelPlan plan;
    plan.backend_profile = backend_profile(config.backend);
    plan.layers = model->hparams.n_layer();
    plan.buffers = allocations(context.get());
    for (const auto & item : llama_get_memory_breakdown(context.get())) {
        const auto device = ggml_backend_buft_get_device(item.first);
        const auto type = device ? ggml_backend_dev_type(device) : GGML_BACKEND_DEVICE_TYPE_CPU;
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU)
            plan.gpu_model_bytes += item.second.model;
        plan.context_bytes += item.second.context;
        plan.compute_bytes += item.second.compute;
    }
    for (const auto & entry : model->tensors_by_name) {
        const auto * tensor = entry.second;
        if (!tensor->buffer) throw std::runtime_error("Tensor has no planned buffer");
        const auto buft = ggml_backend_buffer_get_type(tensor->buffer);
        const auto device = ggml_backend_buft_get_device(buft);
        const auto type = device ? ggml_backend_dev_type(device) : GGML_BACKEND_DEVICE_TYPE_CPU;
        const auto bytes = static_cast<uint64_t>(ggml_nbytes(tensor));
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU)
            plan.largest_gpu_source_bytes = std::max(plan.largest_gpu_source_bytes, bytes);
        else plan.largest_cpu_tensor_bytes = std::max(plan.largest_cpu_tensor_bytes, bytes);
    }
    if (config.backend != "cpu" && !plan.gpu_model_bytes)
        throw std::runtime_error("Requested GPU layers did not select any GPU weight buffer");
    check_cancelled();
    return plan;
}

PreparedPrompt Runtime::prepare(const Request & request) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (!model_ || !templates_) throw std::runtime_error("No model loaded");
    return prepare_prompt(model_.get(), templates_.get(), request, 262144);
}

Result Runtime::generate(const Request & request, Ticket operation_ticket, const Output & output, const Phase & phase,
                         const ProgressCallback & progress, const ChatCallback & chat) {
    std::lock_guard<std::mutex> guard(mutex_);
    const auto started = now_us();
    active_ticket_.store(operation_ticket);
    check_cancelled();
    if (!model_ || !context_) throw std::runtime_error("No model loaded");
    CallerAffinity caller_affinity(cpu_pools_ != nullptr);
    validate_cpu_scheduling(config_, caller_affinity.allowed());
    // Upstream does not re-apply a running pool's main-thread mask. Our API
    // restores the service executor after every request, so apply it explicitly
    // before prefill as well as decode, including shared/one-thread pools.
    caller_affinity.apply(config_.cpu_affinity);
    if (phase) phase("prepare");
    auto prepared_prompt = prepare_prompt(model_.get(), templates_.get(), request, config_.context);
    const auto & tokens = prepared_prompt.tokens;
    const auto * vocab = llama_model_get_vocab(model_.get());
    for (const auto id : request.score_continuation_ids) {
        if (id < 0 || id >= llama_vocab_n_tokens(vocab) || llama_vocab_is_eog(vocab, id)) {
            throw std::invalid_argument("Teacher-forcing token outside vocabulary or EOG");
        }
    }
    if (!request.score_continuation_ids.empty() && request.score_continuation_ids.size() != static_cast<size_t>(request.max_tokens)) {
        throw std::invalid_argument("Teacher-forcing budget mismatch");
    }
    check_cancelled();
    if (phase) phase("reset");
    const auto reset_started = now_us();
    llama_memory_clear(llama_get_memory(context_.get()), true);
#ifdef LLAMA_MOTOAI_KEEP_KV_LAYOUT
    constexpr llama_state_seq_flags cache_flags=LLAMA_STATE_SEQ_FLAGS_KEEP_KV_LAYOUT;
#else
    constexpr llama_state_seq_flags cache_flags=0;
#endif
    size_t reused=0;
    int64_t restore_us=0;
    std::string cache_status=config_.prefix_cache_mib?"miss":"disabled";
    const bool cache_enabled=config_.prefix_cache_mib && !request.cache_session.empty();
    if (!cache_enabled || prefix_cache_.session!=request.cache_session) prefix_cache_={};
    if (cache_enabled && !prefix_cache_.state.empty() && prefix_cache_.tokens.size()<tokens.size() &&
        std::equal(prefix_cache_.tokens.begin(),prefix_cache_.tokens.end(),tokens.begin())) {
        const auto before=now_us();
        const auto restored=llama_state_seq_set_data_ext(context_.get(),prefix_cache_.state.data(),prefix_cache_.state.size(),0,cache_flags);
        restore_us=now_us()-before;
        if(restored==prefix_cache_.state.size()) { reused=prefix_cache_.tokens.size();cache_status="hit"; }
        else {
            prefix_cache_={};llama_memory_clear(llama_get_memory(context_.get()),true);cache_status="restore_failed_full_replay";
        }
    }
    const auto reset_us = now_us() - reset_started;
    llama_perf_context_reset(context_.get());
    if (phase) phase("sampler");
    common_params_sampling sampling;
    sampling.temp = request.temperature;
    sampling.seed = request.seed;
    if (request.top_p >= 0) sampling.top_p = request.top_p;
    std::unique_ptr<common_sampler, decltype(&common_sampler_free)> sampler(
        common_sampler_init(model_.get(), sampling), common_sampler_free);
    if (!sampler) throw std::runtime_error("Sampler creation failed");
    common_batch batch(context_.get());
    Result result;
    struct ProbeGuard {
        std::vector<NodeProbe> *& pointer;
        ~ProbeGuard() { pointer = nullptr; }
    } probe_guard{active_probes_};
    if (config_.tensor_probe || config_.attention_capture) active_probes_ = &result.node_probes;
#if defined(__aarch64__)
    // Observe the native worker's FPCR without changing Java/OS floating-point policy.
    asm volatile("mrs %0, fpcr" : "=r"(result.floating_control));
#endif
    result.reset_us = reset_us;
    result.reused_prompt_tokens=static_cast<int32_t>(reused);
    result.cache_restore_us=restore_us;
    result.cache_status=cache_status;
    result.prompt_tokens = static_cast<int32_t>(tokens.size());
    if (request.trace) { result.rendered_prompt = prepared_prompt.text; result.prompt_ids = tokens; }
    if (request.diagnostics) result.memory_start = allocations(context_.get());
    if (phase) phase("prefill");
    check_cancelled();
    const auto prepared = now_us();
    result.prepare_us = prepared - started;
    auto report_progress = [&](const char * name, int32_t completed, int32_t total, int64_t eval_us) {
        if (request.diagnostics && progress) progress({name, completed, total, now_us() - started, eval_us});
    };
    auto process = [&] {
        check_cancelled();
        const int status = llama_process(context_.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get());
        check_cancelled();
        if (status != 0) throw std::runtime_error("Backend evaluation failed: " + std::to_string(status));
    };
    const size_t capture_boundary=(tokens.size()-1)/config_.batch*config_.batch;
    for (size_t offset = reused; offset < tokens.size(); offset += config_.batch) {
        batch.clear();
        const auto count = std::min<size_t>(config_.batch, tokens.size() - offset);
        for (size_t i = 0; i < count; ++i) {
            batch.add(tokens[offset + i], static_cast<llama_pos>(offset + i), 0,
                      offset + i + 1 == tokens.size());
        }
        report_progress("prefill", static_cast<int32_t>(offset), result.prompt_tokens,
                        result.prefill_chunks.empty() ? 0 : result.prefill_chunks.back().eval_us);
        probe_prefix_end_ = static_cast<int32_t>(offset + count);
        const auto eval_started = now_us();
        process();
        const auto eval_us = now_us() - eval_started;
        result.prefill_chunks.push_back({static_cast<int32_t>(offset + count), static_cast<int32_t>(count), eval_us});
        report_progress("prefill", static_cast<int32_t>(offset + count), result.prompt_tokens, eval_us);
        const auto boundary=offset+count;
        if(cache_enabled && boundary<=capture_boundary && boundary>prefix_cache_.tokens.size()) {
            check_cancelled();
            const auto capture_start=now_us();
            const auto bytes=llama_state_seq_get_size_ext(context_.get(),0,cache_flags);
            const auto budget=uint64_t(config_.prefix_cache_mib)*1024*1024;
            if(bytes && bytes<=budget) {
                // One bounded allocation reused in place. Retain the last fitting
                // complete chunk when a later prefix exceeds the cache budget.
                try {
                    if(prefix_cache_.state.capacity()<budget) prefix_cache_.state.reserve(budget);
                    prefix_cache_.state.resize(bytes);
                    if(llama_state_seq_get_data_ext(context_.get(),prefix_cache_.state.data(),bytes,0,cache_flags)!=bytes)
                        prefix_cache_={};
                    else {
                        prefix_cache_.tokens.assign(tokens.begin(),tokens.begin()+boundary);
                        prefix_cache_.session=request.cache_session;
                    }
                } catch(const std::bad_alloc &) { prefix_cache_={}; }
            }
            result.cache_capture_us+=now_us()-capture_start;
            check_cancelled();
        }
    }
    result.prefix_cache_bytes=prefix_cache_.state.size();
    const auto prefilled = now_us();
    active_probes_ = nullptr; // Observe the initial prefix only, never a later decode graph.
    if (phase) phase("decode");
    result.prefill_us = prefilled - prepared;
    common_chat_input response_input;
    int64_t previous_token_us = 0;
    int64_t last_progress_us = 0;
    for (int32_t i = 0; i < request.max_tokens; ++i) {
        check_cancelled();
        const bool scoring = !request.score_continuation_ids.empty();
        if (request.check_logits || scoring) {
            const float * logits = llama_get_logits_ith(context_.get(), -1);
            if (!logits) throw std::runtime_error("Missing output logits");
            const int32_t count = llama_vocab_n_tokens(vocab);
            std::vector<std::pair<float, llama_token>> top;
            for (int32_t id = 0; id < count; ++id) {
                if (!std::isfinite(logits[id])) throw std::runtime_error("Non-finite raw output logit at token " + std::to_string(id));
                if (i == 0) {
                    top.emplace_back(logits[id], id);
                    if (top.size() > 8) {
                        const auto lowest = std::min_element(top.begin(), top.end());
                        top.erase(lowest);
                    }
                }
            }
            ++result.logits_checks;
            if (i == 0) {
                std::sort(top.rbegin(), top.rend());
                for (const auto & item : top) { result.first_top_logits.push_back(item.first); result.first_top_ids.push_back(item.second); }
            }
            if (scoring) {
                const double maximum = *std::max_element(logits, logits + count);
                double sum = 0;
                for (int32_t id = 0; id < count; ++id) sum += std::exp(static_cast<double>(logits[id]) - maximum);
                const double nll = maximum + std::log(sum) - logits[request.score_continuation_ids[i]];
                if (!std::isfinite(nll)) throw std::runtime_error("Non-finite teacher-forcing loss");
                result.token_nll.push_back(nll);
            }
        }
        const auto token = scoring ? request.score_continuation_ids[i] : common_sampler_sample(sampler.get(), context_.get(), -1);
        common_sampler_accept(sampler.get(), token, true);
        if (llama_vocab_is_eog(vocab, token)) { result.eog = true; break; }
        const auto piece = common_token_to_piece(vocab, token, true);
        const auto emitted = now_us();
        if (result.generated_tokens == 0) result.ttft_us = emitted - started;
        else result.inter_token_us.push_back(emitted - previous_token_us);
        previous_token_us = emitted;
        if (result.first_piece_us == 0 && !piece.empty()) result.first_piece_us = emitted - started;
        if (request.trace) result.generated_ids.push_back(token);
        ++result.generated_tokens;
        if (output) output(piece);
        if (request.parse_response) {
            if (response_input.size() + piece.size() > 16 * 1024 * 1024) throw std::runtime_error("Response exceeds RAM transport budget");
            response_input.append(piece, token);
            result.assistant = common_chat_parse(response_input, true, prepared_prompt.parser);
            const auto stopped = stop_text(result.assistant.content, request.stop_strings, false);
            result.custom_stop = stopped.stopped;
            result.assistant.content = utf8_complete_prefix(stopped.text);
            result.assistant.reasoning_content = utf8_complete_prefix(result.assistant.reasoning_content);
            result.parsed_response = true;
            if (chat) chat(result.assistant);
        }
        if (emitted - last_progress_us >= 1000000 || i + 1 == request.max_tokens) {
            report_progress("decode", result.generated_tokens, request.max_tokens, 0);
            last_progress_us = emitted;
        }
        if (result.custom_stop || i + 1 == request.max_tokens) break;
        batch.clear();
        batch.add(token, static_cast<llama_pos>(tokens.size() + i), 0, true);
        process();
    }
    if (request.parse_response) {
        result.assistant = common_chat_parse(response_input, !result.eog, prepared_prompt.parser);
        const auto stopped = stop_text(result.assistant.content, request.stop_strings, true);
        result.custom_stop = stopped.stopped;
        result.assistant.content = stopped.text;
        result.parsed_response = true;
        if (chat) chat(result.assistant);
    }
    result.decode_us = now_us() - prefilled;
    result.caller_allowed_cpus = caller_affinity.allowed();
    result.cpu_threads = verify_cpu_pools(cpu_pools_.get(), config_);
    result.perf = llama_perf_context(context_.get());
    if (request.diagnostics) result.memory_end = allocations(context_.get());
#ifdef LLAMA_MOTOAI_TRANSIENT_REPACK
    const auto workspace = llama_motoai_get_transient_stats(context_.get());
    result.transient_graph_nodes_marked = workspace.graph_nodes_marked;
    result.transient_maximum_requested_bytes = workspace.maximum_requested_bytes;
    result.cpu_workspace_bytes = workspace.allocated_cpu_workspace_bytes;
    if (config_.transient_repack && result.cpu_workspace_bytes > 81ULL * 1024 * 1024)
        throw std::runtime_error("CPU workspace exceeded explicit transient budget");
#endif
    return result;
}

void Runtime::unload() {
    cancel();
    std::lock_guard<std::mutex> guard(mutex_);
    clear();
}

}
