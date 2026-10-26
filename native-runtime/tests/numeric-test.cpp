#include "numeric-cases.h"
#include <fstream>
#include <iostream>
#include <map>
#include <tuple>
using namespace motoai;

int main(int argc, char ** argv) {
    if (argc != 3 && !(argc == 4 && std::string(argv[3]) == "--extended")) return 2;
    Json report{{"passed", false}, {"cases", Json::array()}};
    try {
        std::ifstream input(argv[2]); const auto gates = Json::parse(input);
        Runtime::initialize(argv[1]);
        std::unique_ptr<ggml_backend, decltype(&ggml_backend_free)> backend(ggml_backend_init_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, nullptr), ggml_backend_free);
        if (!backend) throw std::runtime_error("No CPU backend");
        const auto device = ggml_backend_get_device(backend.get());
        const auto reg = ggml_backend_dev_backend_reg(device);
        const auto set_threads = reinterpret_cast<ggml_backend_set_n_threads_t>(ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_n_threads"));
        if (set_threads) set_threads(backend.get(), 4);
        std::vector<ggml_backend_buffer_type_t> buffers{ggml_backend_dev_buffer_type(device)};
        const auto extra = reinterpret_cast<ggml_backend_dev_get_extra_bufts_t>(ggml_backend_reg_get_proc_address(reg, "ggml_backend_dev_get_extra_bufts"));
        if (extra) for (auto * b = extra(device); b && *b; ++b) buffers.push_back(*b);
        bool passed = true, baseline_passed = true;
        std::map<std::tuple<int, int, int, int>, std::vector<uint8_t>> native_outputs;
        int repack_compared = 0, repack_different = 0;
        const bool extended = argc == 4;
        report["scope"] = extended ? "Extended quantized shapes; original scalar represented-activation gates unchanged" : "Original operator matrix";
        const auto types = extended ? std::vector<ggml_type>{GGML_TYPE_Q4_K, GGML_TYPE_Q6_K} :
            std::vector<ggml_type>{GGML_TYPE_F32, GGML_TYPE_F16, GGML_TYPE_BF16, GGML_TYPE_Q4_K, GGML_TYPE_Q5_K, GGML_TYPE_Q6_K};
        for (auto buft : buffers) for (auto type : types) {
            for (int k : extended ? std::vector<int>{256, 1024, 4096, 8192, 16384} : std::vector<int>{256})
            for (int columns : extended ? std::vector<int>{1, 4, 5, 16} : std::vector<int>{1, 16, 128}) for (int seed : {1, 17, 91}) {
                std::vector<uint8_t> output_bytes;
                auto result = numeric_case(backend.get(), buft, type, columns, seed, gates, k, &output_bytes);
                const auto key = std::make_tuple(static_cast<int>(type), columns, seed, k);
                if (result.contains("output_fnv1a64")) {
                    if (buft == buffers.front()) native_outputs[key] = output_bytes;
                    else if (std::string(ggml_backend_buft_name(buft)) == "CPU_REPACK" && native_outputs.count(key)) {
                        const bool same = native_outputs.at(key) == output_bytes;
                        result["native_output_bitwise_equal"] = same;
                        ++repack_compared; if (!same) ++repack_different;
                    }
                }
                const auto status = result.at("status").get<std::string>();
                if (status == "fail" || status == "reference_not_defined" || (buft == buffers.front() && status != "pass")) passed = false;
                if (buft == buffers.front() && status != "pass") baseline_passed = false;
                report["cases"].push_back(std::move(result));
            }
        }
        report["system_info"] = llama_print_system_info(); report["passed"] = passed; report["baseline_passed"] = baseline_passed;
        report["repack_compared_cases"] = repack_compared;
        report["repack_nonidentical_cases"] = repack_different;
        report["bitwise_comparison_method"] = "direct byte-vector equality; FNV fingerprints are diagnostic only";
    } catch (const std::exception & error) { report["error"] = error.what(); }
    std::cout << report.dump(2) << '\n';
    return report.at("passed").get<bool>() ? 0 : 1;
}
