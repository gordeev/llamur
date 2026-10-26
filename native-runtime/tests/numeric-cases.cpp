#include "numeric-cases.h"
#include "ggml-alloc.h"
#include "ggml-quants.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <tuple>

using namespace motoai;
using Context = std::unique_ptr<ggml_context, decltype(&ggml_free)>;
using Buffer = std::unique_ptr<ggml_backend_buffer, decltype(&ggml_backend_buffer_free)>;

motoai::Json motoai::numeric_case(ggml_backend_t backend, ggml_backend_buffer_type_t buft, ggml_type type, int columns, int seed, const Json & gates, int k,
          std::vector<uint8_t> * output_bytes, bool original_f32_reference) {
    const int rows = 32;
    const auto name = ggml_type_name(type);
    Json result{{"type", name}, {"buffer", ggml_backend_buft_name(buft)}, {"tokens", columns}, {"seed", seed}, {"k", k}};
    Context weights(ggml_init({ggml_tensor_overhead() * 4, nullptr, true}), ggml_free);
    Context context(ggml_init({ggml_tensor_overhead() * 16 + ggml_graph_overhead_custom(128, false), nullptr, true}), ggml_free);
    if (!weights || !context) throw std::runtime_error("Context allocation failed");
    auto * w = ggml_new_tensor_2d(weights.get(), type, k, rows);
    auto * x = ggml_new_tensor_2d(context.get(), GGML_TYPE_F32, k, columns);
    auto * out = ggml_mul_mat(context.get(), w, x);
    auto * graph = ggml_new_graph_custom(context.get(), 128, false);
    ggml_build_forward_expand(graph, out);
    Buffer wb(ggml_backend_alloc_ctx_tensors_from_buft(weights.get(), buft), ggml_backend_buffer_free);
    Buffer ab(ggml_backend_alloc_ctx_tensors(context.get(), backend), ggml_backend_buffer_free);
    if (!wb || !ab) throw std::runtime_error("Buffer allocation failed");
    ggml_backend_buffer_set_usage(wb.get(), GGML_BACKEND_BUFFER_USAGE_WEIGHTS);
    if (!ggml_backend_supports_op(backend, out)) { result["status"] = "unsupported"; return result; }
    std::vector<float> original(k * rows), input(k * columns), represented(k * rows), actual(rows * columns);
    for (size_t i = 0; i < original.size(); ++i) original[i] = std::sin(float((i * 37 + seed) % 257) * 0.17f) * 0.7f;
    for (size_t i = 0; i < input.size(); ++i) input[i] = std::cos(float((i * 13 + seed) % 131) * 0.11f) * 0.6f;
    std::vector<uint8_t> encoded(ggml_nbytes(w));
    if (type == GGML_TYPE_F32) std::memcpy(encoded.data(), original.data(), encoded.size());
    else {
        const auto size = ggml_quantize_chunk(type, original.data(), encoded.data(), 0, rows, k, nullptr);
        if (size != encoded.size()) throw std::runtime_error("Quantizer returned wrong byte count");
    }
    const auto row_bytes = ggml_row_size(type, k);
    for (int row = 0; row < rows; ++row) {
        if (type == GGML_TYPE_F32) std::memcpy(represented.data() + row * k, encoded.data() + row * row_bytes, k * sizeof(float));
        else ggml_get_type_traits(type)->to_float(encoded.data() + row * row_bytes, represented.data() + row * k, k);
    }
    ggml_backend_tensor_set(w, encoded.data(), 0, encoded.size());
    ggml_backend_tensor_set(x, input.data(), 0, input.size() * sizeof(float));
    if (ggml_backend_graph_compute(backend, graph) != GGML_STATUS_SUCCESS) throw std::runtime_error("Evaluation failed");
    ggml_backend_tensor_get(out, actual.data(), 0, actual.size() * sizeof(float));
    uint64_t hash = 14695981039346656037ULL;
    const auto * actual_bytes = reinterpret_cast<const uint8_t *>(actual.data());
    if (output_bytes) output_bytes->assign(actual_bytes, actual_bytes + actual.size() * sizeof(float));
    for (size_t i = 0; i < actual.size() * sizeof(float); ++i) { hash ^= actual_bytes[i]; hash *= 1099511628211ULL; }
    std::ostringstream digest; digest << std::hex << hash;
    result["output_fnv1a64"] = digest.str();
    auto measure = [&](const std::vector<float> & reference_input) {
        double squared_error = 0, squared_reference = 0, peak_error = 0, peak_reference = 0;
        bool finite = true;
        for (int column = 0; column < columns; ++column) for (int row = 0; row < rows; ++row) {
            double reference = 0;
            for (int j = 0; j < k; ++j) reference += double(represented[row * k + j]) * reference_input[column * k + j];
            const auto value = actual[column * rows + row];
            finite = finite && std::isfinite(value);
            const double error = double(value) - reference;
            squared_error += error * error; squared_reference += reference * reference;
            peak_error = std::max(peak_error, std::abs(error)); peak_reference = std::max(peak_reference, std::abs(reference));
        }
        const double nmse = squared_error / std::max(squared_reference, 1e-30);
        const double peak = peak_error / std::max(peak_reference, 1e-30);
        const auto & gate = gates.at("types").at(name);
        return Json{{"nmse", nmse}, {"max_relative_to_peak", peak}, {"finite", finite},
                    {"within_limits", finite && nmse <= gate.at("nmse").get<double>() && peak <= gate.at("max_relative_to_peak").get<double>()}};
    };
    result["f32_activation_accuracy"] = measure(input);
    if (original_f32_reference) {
        std::vector<uint8_t> restored(encoded.size());
        ggml_backend_tensor_get(w, restored.data(), 0, restored.size());
        result["weight_bytes_roundtrip_equal"] = restored == encoded;
        result["activation_reference"] = "original_f32";
        result["status"] = restored == encoded && result["f32_activation_accuracy"]["within_limits"].get<bool>() ? "pass" : "fail";
        return result;
    }
    const bool native_policy = result["buffer"] == "CPU" || result["buffer"] == "CPU_REPACK";
    if (!native_policy) {
        result["status"] = "reference_not_defined";
        result["note"] = "Experimental buffer; its activation/weight conversion is not certified by the native CPU reference";
        return result;
    }
    std::vector<float> reference_input = input;
    if (type == GGML_TYPE_F16) {
        std::vector<ggml_fp16_t> encoded_input(input.size());
        ggml_fp32_to_fp16_row(input.data(), encoded_input.data(), input.size());
        ggml_fp16_to_fp32_row(encoded_input.data(), reference_input.data(), input.size());
        result["activation_reference"] = "f16";
    } else if (type == GGML_TYPE_BF16) {
        std::vector<ggml_bf16_t> encoded_input(input.size());
        ggml_fp32_to_bf16_row_ref(input.data(), encoded_input.data(), input.size());
        ggml_bf16_to_fp32_row(encoded_input.data(), reference_input.data(), input.size());
        result["activation_reference"] = "bf16";
    } else if (type == GGML_TYPE_Q4_K || type == GGML_TYPE_Q5_K || type == GGML_TYPE_Q6_K) {
        std::vector<block_q8_K> encoded_input(input.size() / QK_K);
        quantize_row_q8_K_ref(input.data(), encoded_input.data(), input.size());
        dequantize_row_q8_K(encoded_input.data(), reference_input.data(), input.size());
        result["activation_reference"] = "q8_K";
    } else result["activation_reference"] = "f32";
    result["operator_correctness"] = measure(reference_input);
    result["status"] = result["operator_correctness"]["within_limits"].get<bool>() ? "pass" : "fail";
    return result;
}

