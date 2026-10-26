// Feasibility measurement, not a runtime optimization or model-quality gate.
// Synthetic unchanged Q4_K/Q6_K bytes; include allocation, repack and free costs.
#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "ggml-alloc.h"
#include "ggml-quants.h"
#include "nlohmann/json.hpp"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <random>
#include <stdexcept>
#include <vector>
#include <set>
#include <cstdio>
#include <csignal>
#include <dlfcn.h>
#include <unistd.h>
#ifdef __linux__
#include <sched.h>
#endif

using Json = nlohmann::ordered_json;
using Context = std::unique_ptr<ggml_context, decltype(&ggml_free)>;
using Buffer = std::unique_ptr<ggml_backend_buffer, decltype(&ggml_backend_buffer_free)>;
using Clock = std::chrono::steady_clock;
static int64_t us(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - start).count();
}

struct MatrixGraph {
    Context weights{nullptr, ggml_free}, graph_context{nullptr, ggml_free};
    Buffer weight_buffer{nullptr, ggml_backend_buffer_free}, activations{nullptr, ggml_backend_buffer_free};
    ggml_tensor * w = nullptr, * x = nullptr, * out = nullptr;
    ggml_cgraph * graph = nullptr;
    MatrixGraph(ggml_backend_t backend, ggml_type type, int k, int m, int n) {
        weights.reset(ggml_init({ggml_tensor_overhead() * 4, nullptr, true}));
        graph_context.reset(ggml_init({ggml_tensor_overhead() * 16 + ggml_graph_overhead_custom(128, false), nullptr, true}));
        if (!weights || !graph_context) throw std::runtime_error("Metadata allocation failed");
        w = ggml_new_tensor_2d(weights.get(), type, k, m);
        x = ggml_new_tensor_2d(graph_context.get(), GGML_TYPE_F32, k, n);
        out = ggml_mul_mat(graph_context.get(), w, x);
        graph = ggml_new_graph_custom(graph_context.get(), 128, false);
        ggml_build_forward_expand(graph, out);
        activations.reset(ggml_backend_alloc_ctx_tensors(graph_context.get(), backend));
        if (!activations) throw std::runtime_error("Activation allocation failed");
    }
    void allocate_weights(ggml_backend_buffer_type_t buft) {
        weight_buffer.reset(ggml_backend_alloc_ctx_tensors_from_buft(weights.get(), buft));
        if (!weight_buffer) throw std::runtime_error("Weight allocation failed");
        ggml_backend_buffer_set_usage(weight_buffer.get(), GGML_BACKEND_BUFFER_USAGE_WEIGHTS);
    }
};

using MarkTransient = bool (*)(ggml_tensor *);
using TransientSize = size_t (*)(const ggml_tensor *);
using WorkspaceSize = size_t (*)(ggml_backend_t);
static Json check_workspace_eligibility(MarkTransient mark, TransientSize size) {
    Json results = Json::array();
    auto probe = [&](const char * label, ggml_type type, int k, int m, int n, bool expected, bool transpose = false, bool three_d = false) {
        Context ctx(ggml_init({ggml_tensor_overhead() * 16, nullptr, true}), ggml_free);
        if (!ctx) throw std::runtime_error("Eligibility metadata allocation failed");
        auto * w = three_d ? ggml_new_tensor_3d(ctx.get(), type, k, m, 2) : ggml_new_tensor_2d(ctx.get(), type, k, m);
        auto * x = three_d ? ggml_new_tensor_3d(ctx.get(), GGML_TYPE_F32, k, n, 2) : ggml_new_tensor_2d(ctx.get(), GGML_TYPE_F32, k, n);
        ggml_tensor * strided = nullptr;
        // MUL_MAT's public constructor itself rejects transposed A. Exercise
        // the marker on unsupported metadata without asking that constructor
        // to build an invalid operation (the probe is never executed).
        if (transpose) {
            strided = ggml_transpose(ctx.get(), w);
            w = ggml_new_tensor_2d(ctx.get(), type, m, k);
            x = ggml_new_tensor_2d(ctx.get(), GGML_TYPE_F32, m, n);
        }
        auto * out = ggml_mul_mat(ctx.get(), w, x);
        if (strided) out->src[0] = strided;
        if (strided && ggml_is_contiguous(strided)) throw std::runtime_error("Invalid non-contiguous test fixture: GGML treats this block view as contiguous");
        const bool actual = mark(out);
        if (actual != expected || (!expected && size(out) != 0)) {
            const auto * source = out->src[0];
            const Json diagnostic{{"case", label}, {"accepted", actual}, {"expected", expected},
                {"contiguous", ggml_is_contiguous(source)}, {"ne", {source->ne[0], source->ne[1]}},
                {"nb", {source->nb[0], source->nb[1]}}};
            throw std::runtime_error("Eligibility guard failed: " + diagnostic.dump());
        }
        results.push_back({{"case", label}, {"accepted", actual}, {"requested_bytes", size(out)}});
    };
    // Metadata only: these rejected shapes never allocate their weight payload.
    probe("below64MiB", GGML_TYPE_Q4_K, 16384, 7280, 4, true);
    probe("above64MiB", GGML_TYPE_Q4_K, 16384, 7288, 4, false);
    probe("k_over_limit", GGML_TYPE_Q4_K, 32768, 32, 4, false);
    probe("m_over_limit", GGML_TYPE_Q4_K, 256, 16400, 4, false);
    probe("n_over_limit", GGML_TYPE_Q4_K, 256, 32, 513, false);
    probe("decode", GGML_TYPE_Q4_K, 256, 32, 1, false);
    probe("unaligned_rows", GGML_TYPE_Q4_K, 256, 31, 4, false);
    probe("unqualified_type", GGML_TYPE_F16, 256, 32, 4, false);
    probe("non_contiguous", GGML_TYPE_Q4_K, 1024, 512, 4, false, true);
    probe("three_dimensional_weights", GGML_TYPE_Q4_K, 256, 32, 4, false, false, true);
    return results;
}
static Json run_case(ggml_backend_t backend, ggml_backend_buffer_type_t packed_type, const Json & spec,
                     MarkTransient mark = nullptr, TransientSize transient_size = nullptr, WorkspaceSize workspace_size = nullptr) {
    const auto name = spec.at("type").get<std::string>();
    const auto type = name == "q4_K" ? GGML_TYPE_Q4_K : name == "q6_K" ? GGML_TYPE_Q6_K : GGML_TYPE_COUNT;
    const int k = spec.at("k"), m = spec.at("m"), n = spec.at("n");
    if (type == GGML_TYPE_COUNT || k < 256 || k > 16384 || k % 256 || m < 8 || m > 16384 || m % 8 || n < 1 || n > 512)
        throw std::runtime_error("Unsupported bounded matrix shape");
    const size_t bytes = ggml_row_size(type, k) * size_t(m);
    if (bytes > 64 * 1024 * 1024) throw std::runtime_error("Matrix exceeds64MiB transient-copy ceiling");
    std::mt19937 random(7107001);
    auto value = [&] { return float(int(random() % 2001) - 1000) / 1024.0f; };
    std::vector<uint8_t> encoded(bytes);
    std::vector<float> row(k), input(size_t(k) * n), native_output(size_t(m) * n), packed_output(size_t(m) * n);
    for (int i = 0; i < m; ++i) {
        for (auto & v : row) v = value();
        if (ggml_quantize_chunk(type, row.data(), encoded.data() + ggml_row_size(type, k) * i, 0, 1, k, nullptr) != ggml_row_size(type, k))
            throw std::runtime_error("Wrong quantized row size");
    }
    for (auto & v : input) v = value();
    MatrixGraph native(backend, type, k, m, n), packed(backend, type, k, m, n);
    native.allocate_weights(ggml_backend_dev_buffer_type(ggml_backend_get_device(backend)));
    ggml_backend_tensor_set(native.w, encoded.data(), 0, bytes);
    const bool workspace = mark != nullptr;
    bool applied = false;
    if (workspace) {
        packed.allocate_weights(ggml_backend_dev_buffer_type(ggml_backend_get_device(backend)));
        ggml_backend_tensor_set(packed.w, encoded.data(), 0, bytes);
        if (transient_size(native.out) != 0) throw std::runtime_error("Unmarked native graph has transient workspace");
        applied = mark(packed.out);
        if (applied != (n >= 4)) throw std::runtime_error("Unexpected workspace eligibility; no implicit fallback");
        if (transient_size(packed.out) > 80 * 1024 * 1024) throw std::runtime_error("Unbounded workspace request");
    }
    for (auto * g : {&native, &packed}) ggml_backend_tensor_set(g->x, input.data(), 0, input.size() * sizeof(float));
    Json result{{"type", name}, {"k", k}, {"m", m}, {"n", n}, {"original_weight_bytes", bytes},
                {"scope", workspace ? "Synthetic single-matrix CPU workspace feasibility; includes parallel packing and graph compute with pooled scratch, excludes fixture generation, activation allocation and output readback. No model/app speed claim." :
                    "Synthetic single-matrix kernel feasibility; no model/end-to-end speed claim. Includes transient allocation/repack/free, excludes fixture generation and output readback."},
                {"pairs", Json::array()}};
    result["mode"] = workspace ? "cpu_graph_workspace" : "allocate_repack_free";
    result["workspace_applied"] = applied;
    if (workspace) result["requested_workspace_bytes"] = transient_size(packed.out);
    for (int pair = 0; pair < 6; ++pair) {
        Json timing{{"warmup", pair == 0}, {"order", pair % 2 ? "transient,native" : "native,transient"}};
        for (int step = 0; step < 2; ++step) {
            const bool transient = (pair + step) % 2 != 0;
            auto & g = transient ? packed : native;
            const auto start = Clock::now();
            int64_t allocate_us = 0, pack_us = 0, compute_us = 0, free_us = 0;
            if (transient && !workspace) {
                auto begin = Clock::now(); g.allocate_weights(packed_type); allocate_us = us(begin);
                if (!g.w->extra || !ggml_backend_supports_op(backend, g.out)) throw std::runtime_error("Packed kernel unsupported; no native fallback");
                if (ggml_backend_buffer_get_size(g.weight_buffer.get()) > 64 * 1024 * 1024)
                    throw std::runtime_error("Actual packed buffer exceeds64MiB ceiling");
                timing["transient_buffer_bytes"] = ggml_backend_buffer_get_size(g.weight_buffer.get());
                begin = Clock::now(); ggml_backend_tensor_set(g.w, encoded.data(), 0, bytes); pack_us = us(begin);
            }
            const auto begin = Clock::now();
            if (ggml_backend_graph_compute(backend, g.graph) != GGML_STATUS_SUCCESS) throw std::runtime_error("Kernel failed");
            compute_us = us(begin);
            if (transient && !workspace) {
                const auto free_start = Clock::now(); g.weight_buffer.reset(); free_us = us(free_start);
                g.w->data = nullptr; g.w->buffer = nullptr; g.w->extra = nullptr;
            }
            timing[transient ? "transient_total_us" : "native_total_us"] = us(start);
            if (workspace) {
                timing[transient ? "transient_cpu_workspace_bytes" : "native_cpu_workspace_bytes"] = workspace_size(backend);
                if (workspace_size(backend) > 80 * 1024 * 1024 + 8 * 128)
                    throw std::runtime_error("Actual pooled CPU workspace exceeds80MiB plus thread padding");
            }
            if (transient) timing["transient_parts_us"] = {{"allocate", allocate_us}, {"pack", pack_us}, {"compute", compute_us}, {"free", free_us}};
            ggml_backend_tensor_get(g.out, transient ? packed_output.data() : native_output.data(), 0, native_output.size() * sizeof(float));
        }
        const bool identical = std::memcmp(native_output.data(), packed_output.data(), native_output.size() * sizeof(float)) == 0;
        timing["all_output_bits_equal"] = identical;
        if (!identical) {
            size_t different = 0;
            double maximum_error = 0;
            for (size_t i = 0; i < native_output.size(); ++i) {
                if (std::memcmp(&native_output[i], &packed_output[i], sizeof(float))) ++different;
                maximum_error = std::max(maximum_error, std::abs(double(native_output[i]) - packed_output[i]));
            }
            timing["different_output_cells"] = different;
            timing["max_native_packed_absolute_error"] = maximum_error;
            result["pairs"].push_back(timing);
            result["passed"] = false;
            result["error"] = "Native/transient output bits differ; reject feasibility result";
            return result;
        }
        for (float v : native_output) if (!std::isfinite(v)) throw std::runtime_error("Non-finite output");
        if (workspace) {
            std::vector<uint8_t> source_after(bytes);
            ggml_backend_tensor_get(packed.w, source_after.data(), 0, bytes);
            if (source_after != encoded || packed.w->extra != nullptr)
                throw std::runtime_error("Transient computation modified native source weights/metadata");
        }
        result["pairs"].push_back(timing);
    }
    // Independent F64 dot checks for up to64 unique output cells after the
    // original Q8_K activation representation, outside all timed regions.
    std::vector<block_q8_K> q8(size_t(k) * n / QK_K);
    std::vector<float> represented_input(input.size());
    quantize_row_q8_K_ref(input.data(), q8.data(), input.size());
    dequantize_row_q8_K(q8.data(), represented_input.data(), input.size());
    double error2 = 0, reference2 = 0, peak_error = 0, peak_reference = 0;
    std::set<size_t> sampled;
    const size_t sample_count = std::min<size_t>(64, native_output.size());
    while (sampled.size() < sample_count) sampled.insert(random() % native_output.size());
    for (const size_t index : sampled) {
        const size_t r = index % m, c = index / m;
        ggml_get_type_traits(type)->to_float(encoded.data() + ggml_row_size(type, k) * r, row.data(), k);
        double reference = 0;
        for (int j = 0; j < k; ++j) reference += double(row[j]) * represented_input[c * k + j];
        const double error = double(native_output[c * m + r]) - reference;
        error2 += error * error; reference2 += reference * reference;
        peak_error = std::max(peak_error, std::abs(error)); peak_reference = std::max(peak_reference, std::abs(reference));
    }
    result["independent_sampled_reference"] = {{"cells", sample_count}, {"nmse", error2 / std::max(reference2, 1e-30)},
                                             {"max_relative_to_peak", peak_error / std::max(peak_reference, 1e-30)}};
    const auto & gate = spec.at("numeric_gate");
    if (result["independent_sampled_reference"]["nmse"].get<double>() > gate.at("nmse").get<double>() ||
        result["independent_sampled_reference"]["max_relative_to_peak"].get<double>() > gate.at("max_relative_to_peak").get<double>())
    {
        result["passed"] = false;
        result["error"] = "Original represented-activation numeric gate failed";
        return result;
    }
    result["passed"] = true;
    return result;
}

int main(int argc, char ** argv) {
    if (argc != 3) { std::cerr << "Usage: motoai-prefill-pack-bench BACKEND_DIRECTORY SPEC.json\n"; return 2; }
    // Own-process bound remains effective if the ADB transport disappears.
    std::signal(SIGALRM, SIG_DFL);
    alarm(240);
    Json report{{"passed", false}, {"scope", "Bounded synthetic kernel feasibility only. No GGUF weights changed or loaded; not APK speed, stability or product acceptance."}, {"cases", Json::array()}};
    try {
        std::ifstream file(argv[2]); const auto spec = Json::parse(file);
        const int threads = spec.at("threads");
        if (threads < 1 || threads > 8 || spec.at("cases").size() > 16) throw std::runtime_error("Unbounded benchmark specification");
#ifdef __linux__
        cpu_set_t allowed, requested;
        CPU_ZERO(&requested);
        if (sched_getaffinity(0, sizeof(allowed), &allowed)) throw std::runtime_error("Cannot read own allowed CPUs");
        for (const int cpu : spec.at("cpus").get<std::vector<int>>()) {
            if (cpu < 0 || cpu >= CPU_SETSIZE) throw std::runtime_error("Invalid CPU index");
            if (CPU_ISSET(cpu, &allowed)) CPU_SET(cpu, &requested);
        }
        if (!CPU_COUNT(&requested) || sched_setaffinity(0, sizeof(requested), &requested)) throw std::runtime_error("Own CPU affinity intersection failed");
        report["cpus"] = Json::array();
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) if (CPU_ISSET(cpu, &requested)) report["cpus"].push_back(cpu);
#endif
        ggml_log_set([](ggml_log_level level, const char * text, void *) {
            if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) std::fputs(text, stderr);
        }, nullptr);
        ggml_backend_load_all_from_path(argv[1]);
        std::unique_ptr<ggml_backend, decltype(&ggml_backend_free)> backend(ggml_backend_init_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, nullptr), ggml_backend_free);
        if (!backend) throw std::runtime_error("CPU backend unavailable");
        const auto reg = ggml_backend_dev_backend_reg(ggml_backend_get_device(backend.get()));
        const auto set_threads = reinterpret_cast<ggml_backend_set_n_threads_t>(ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_n_threads"));
        if (!set_threads) throw std::runtime_error("CPU thread control unavailable");
        set_threads(backend.get(), threads);
        Dl_info info{};
        if (!dladdr(reinterpret_cast<void *>(set_threads), &info) || !info.dli_fname)
            throw std::runtime_error("Cannot identify selected CPU backend library");
        report["cpu_backend_library"] = info.dli_fname;
        const auto get_features = reinterpret_cast<ggml_backend_get_features_t>(ggml_backend_reg_get_proc_address(reg, "ggml_backend_get_features"));
        report["cpu_backend_features"] = Json::object();
        if (get_features) for (auto * feature = get_features(reg); feature && feature->name; ++feature)
            report["cpu_backend_features"][feature->name] = feature->value ? feature->value : "unavailable";
        auto extras = reinterpret_cast<ggml_backend_dev_get_extra_bufts_t>(ggml_backend_reg_get_proc_address(reg, "ggml_backend_dev_get_extra_bufts"));
        ggml_backend_buffer_type_t packed_type = nullptr;
        if (extras) for (auto * b = extras(ggml_backend_get_device(backend.get())); b && *b; ++b)
            if (std::string(ggml_backend_buft_name(*b)) == "CPU_REPACK") packed_type = *b;
        if (!packed_type) throw std::runtime_error("CPU_REPACK unavailable");
        const auto mode = spec.value("mode", std::string("allocate_repack_free"));
        if (mode != "allocate_repack_free" && mode != "cpu_graph_workspace") throw std::runtime_error("Unknown explicit kernel mode");
        const bool workspace = mode == "cpu_graph_workspace";
        auto mark = workspace ? reinterpret_cast<MarkTransient>(ggml_backend_reg_get_proc_address(reg, "ggml_backend_cpu_mark_transient_repack")) : nullptr;
        auto transient_size = workspace ? reinterpret_cast<TransientSize>(ggml_backend_reg_get_proc_address(reg, "ggml_backend_cpu_transient_repack_size")) : nullptr;
        auto workspace_size = workspace ? reinterpret_cast<WorkspaceSize>(ggml_backend_reg_get_proc_address(reg, "ggml_backend_cpu_workspace_size")) : nullptr;
        if (workspace && (!mark || !transient_size || !workspace_size)) throw std::runtime_error("Explicit workspace API unavailable");
        if (workspace) report["eligibility_checks"] = check_workspace_eligibility(mark, transient_size);
        report["threads"] = threads;
        bool all_passed = true;
        for (const auto & c : spec.at("cases")) {
            report["active_case"] = c;
            std::cerr << "Case " << c.at("type") << " K=" << c.at("k") << " M=" << c.at("m") << " N=" << c.at("n") << std::endl;
            auto result = run_case(backend.get(), packed_type, c, mark, transient_size, workspace_size);
            const bool passed = result.at("passed");
            report["cases"].push_back(std::move(result));
            all_passed = all_passed && passed;
        }
        report.erase("active_case");
        if (report["cases"].empty()) throw std::runtime_error("No benchmark cases");
        report["passed"] = all_passed;
        if (!all_passed) report["error"] = "Kernel feasibility gate failed; retained failed cases are not speed evidence";
    } catch (const std::exception & error) { report["error"] = error.what(); }
    std::cout << report.dump(2) << '\n';
    return report["passed"].get<bool>() ? 0 : 1;
}
