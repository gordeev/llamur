// Independent inverse-layout check: the input is never consulted by the decoder.
#include "ggml-cpu/repack.h"
#include "protocol.h"
#include "ggml-alloc.h"
#include "gguf.h"
#include <algorithm>
#include <cstring>
#include <fstream>
#include <iostream>
#include <random>

using namespace motoai;
using Bytes = std::vector<uint8_t>;

static std::pair<uint8_t, uint8_t> scale_min(const uint8_t * bytes, int index) {
    if (index < 4) return {uint8_t(bytes[index] & 63), uint8_t(bytes[index + 4] & 63)};
    return {uint8_t((bytes[index + 4] & 15) | ((bytes[index - 4] >> 6) << 4)),
            uint8_t((bytes[index + 4] >> 4) | ((bytes[index] >> 6) << 4))};
}

static Bytes inverse(const Bytes & packed, ggml_type type, int columns, int rows, int interleave) {
    Bytes output(packed.size());
    const int blocks = columns / QK_K;
    for (int group = 0; group < rows / 8; ++group) {
        for (int block = 0; block < blocks; ++block) {
            const auto packed_index = group * blocks + block;
            for (int row = 0; row < 8; ++row) {
                const auto source_index = (group * 8 + row) * blocks + block;
                auto deinterleave = [&](uint8_t * destination, const uint8_t * source, size_t size) {
                    for (size_t i = 0; i < size; ++i) {
                        destination[i] = source[(i / interleave * 8 + row) * interleave + i % interleave];
                    }
                };
                if (type == GGML_TYPE_Q4_K) {
                    block_q4_Kx8 input;
                    std::memcpy(&input, packed.data() + packed_index * sizeof(input), sizeof(input));
                    block_q4_K original{};
                    original.GGML_COMMON_AGGR_U.GGML_COMMON_AGGR_S.d = input.d[row];
                    original.GGML_COMMON_AGGR_U.GGML_COMMON_AGGR_S.dmin = input.dmin[row];
                    uint8_t scales[8], mins[8];
                    for (int sub = 0; sub < 8; ++sub) {
                        const auto values = scale_min(input.scales + sub * 12, row);
                        scales[sub] = values.first; mins[sub] = values.second;
                    }
                    for (int sub = 0; sub < 4; ++sub) {
                        original.scales[sub] = scales[sub] | ((scales[sub + 4] >> 4) << 6);
                        original.scales[sub + 4] = mins[sub] | ((mins[sub + 4] >> 4) << 6);
                        original.scales[sub + 8] = (scales[sub + 4] & 15) | ((mins[sub + 4] & 15) << 4);
                    }
                    deinterleave(original.qs, input.qs, sizeof(original.qs));
                    std::memcpy(output.data() + source_index * sizeof(original), &original, sizeof(original));
                } else {
                    block_q6_Kx8 input;
                    std::memcpy(&input, packed.data() + packed_index * sizeof(input), sizeof(input));
                    block_q6_K original{};
                    original.d = input.d[row];
                    for (int sub = 0; sub < QK_K / 16; ++sub) original.scales[sub] = input.scales[sub * 8 + row];
                    deinterleave(original.ql, input.ql, sizeof(original.ql));
                    deinterleave(original.qh, input.qh, sizeof(original.qh));
                    std::memcpy(output.data() + source_index * sizeof(original), &original, sizeof(original));
                }
            }
        }
    }
    return output;
}

static Json audit(ggml_backend_buffer_type_t buft, ggml_type type, int columns, int rows,
                  const Bytes & input, const std::string & source) {
    std::unique_ptr<ggml_context, decltype(&ggml_free)> ctx(
        ggml_init({ggml_tensor_overhead() * 2, nullptr, true}), ggml_free);
    if (!ctx) throw std::runtime_error("Tensor metadata allocation failed");
    auto * tensor = ggml_new_tensor_2d(ctx.get(), type, columns, rows);
    if (ggml_nbytes(tensor) != input.size()) throw std::runtime_error("Input size mismatch");
    std::unique_ptr<ggml_backend_buffer, decltype(&ggml_backend_buffer_free)> buffer(
        ggml_backend_alloc_ctx_tensors_from_buft(ctx.get(), buft), ggml_backend_buffer_free);
    if (!buffer || !tensor->extra) throw std::runtime_error("CPU_REPACK layout unsupported on this CPU");
    ggml_backend_buffer_set_usage(buffer.get(), GGML_BACKEND_BUFFER_USAGE_WEIGHTS);
    ggml_backend_tensor_set(tensor, input.data(), 0, input.size());
    Bytes packed(input.size());
    // Pinned CPU_REPACK wraps a CPU allocation but intentionally has no generic
    // get_tensor callback. Only this diagnostic reads its raw packed backing
    // bytes; never treat them as ordinary GGUF tensor data.
    const auto base = reinterpret_cast<uintptr_t>(ggml_backend_buffer_get_base(buffer.get()));
    const auto address = reinterpret_cast<uintptr_t>(tensor->data);
    const auto capacity = ggml_backend_buffer_get_size(buffer.get());
    if (!base || address < base || address - base > capacity || packed.size() > capacity - (address - base)) {
        throw std::runtime_error("Packed CPU backing range invalid");
    }
    std::memcpy(packed.data(), tensor->data, packed.size());
    Json layouts = Json::array();
    for (int interleave : {4, 8}) {
        if (inverse(packed, type, columns, rows, interleave) == input) layouts.push_back(interleave);
    }
    return {{"passed", !layouts.empty()}, {"source", source}, {"type", ggml_type_name(type)},
            {"columns", columns}, {"rows", rows}, {"bytes", input.size()},
            {"matching_interleave_bytes", layouts}, {"exact_original_bytes_recovered", !layouts.empty()}};
}

int main(int argc, char ** argv) {
    if (argc < 2) { std::cerr << "Usage: motoai-repack-audit BACKEND_DIRECTORY [GGUF ...]\n"; return 2; }
    Json report{{"passed", false}, {"scope", "CPU_REPACK inverse layout audit for2D Q4_K/Q6_K matrices, including every scale/code bit. Not matmul, model quality, performance or all-format proof."}, {"cases", Json::array()}};
    try {
        Runtime::initialize(argv[1]);
        auto * device = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        if (!device) throw std::runtime_error("No CPU backend");
        auto get_extras = reinterpret_cast<ggml_backend_dev_get_extra_bufts_t>(ggml_backend_reg_get_proc_address(
            ggml_backend_dev_backend_reg(device), "ggml_backend_dev_get_extra_bufts"));
        ggml_backend_buffer_type_t buft = nullptr;
        if (get_extras) for (auto * extra = get_extras(device); extra && *extra; ++extra) {
            if (std::string(ggml_backend_buft_name(*extra)) == "CPU_REPACK") buft = *extra;
        }
        if (!buft) throw std::runtime_error("CPU_REPACK buffer unavailable; no implicit fallback");
        report["buffer_type"] = ggml_backend_buft_name(buft);
        report["system_info"] = llama_print_system_info();
        std::mt19937 random(7107001);
        for (auto type : {GGML_TYPE_Q4_K, GGML_TYPE_Q6_K}) {
            for (const auto shape : {std::pair<int, int>{256, 8}, {512, 16}, {1792, 24}}) {
                for (int pattern = 0; pattern < 3; ++pattern) {
                    Bytes input(ggml_row_size(type, shape.first) * shape.second);
                    for (auto & value : input) value = pattern == 0 ? uint8_t(random() & 255) : pattern == 1 ? 0 : 255;
                    report["cases"].push_back(audit(buft, type, shape.first, shape.second, input,
                        pattern == 0 ? "synthetic-random-bits" : pattern == 1 ? "synthetic-zero" : "synthetic-all-one-bits"));
                }
            }
        }
        for (int file = 2; file < argc; ++file) {
            std::unique_ptr<gguf_context, decltype(&gguf_free)> metadata(
                gguf_init_from_file(argv[file], {true, nullptr}), gguf_free);
            if (!metadata) throw std::runtime_error("GGUF metadata failed");
            std::ifstream stream(argv[file], std::ios::binary);
            for (auto type : {GGML_TYPE_Q4_K, GGML_TYPE_Q6_K}) {
                bool found = false;
                for (int64_t index = 0; index < gguf_get_n_tensors(metadata.get()); ++index) {
                    const auto * shape = gguf_get_tensor_ne(metadata.get(), index);
                    if (gguf_get_tensor_type(metadata.get(), index) != type || shape[0] % QK_K || shape[0] > 32768 ||
                        shape[1] < 16 || shape[2] != 1 || shape[3] != 1) continue;
                    Bytes sample(ggml_row_size(type, shape[0]) * 16);
                    const uint64_t offset = gguf_get_data_offset(metadata.get()) + gguf_get_tensor_offset(metadata.get(), index);
                    stream.seekg(static_cast<std::streamoff>(offset));
                    stream.read(reinterpret_cast<char *>(sample.data()), static_cast<std::streamsize>(sample.size()));
                    if (!stream) throw std::runtime_error("GGUF sample read failed");
                    auto result = audit(buft, type, static_cast<int>(shape[0]), 16, sample, "GGUF-sample");
                    result["tensor"] = gguf_get_tensor_name(metadata.get(), index);
                    result["file_argument_index"] = file; result["file_offset"] = offset;
                    report["cases"].push_back(result); found = true; break;
                }
                if (!found) throw std::runtime_error("GGUF lacks a bounded2D sample of a required type");
            }
        }
        report["passed"] = std::all_of(report["cases"].begin(), report["cases"].end(),
            [](const Json & item) { return item["passed"].get<bool>(); });
    } catch (const std::exception & error) { report["error"] = error.what(); }
    std::cout << report.dump(2) << '\n';
    return report["passed"].get<bool>() ? 0 : 1;
}
