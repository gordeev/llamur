#include "protocol.h"
#include "ggml-alloc.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <dlfcn.h>

using namespace motoai;
struct Input {
    ggml_tensor * tensor;
    std::vector<uint8_t> bytes;
    std::vector<float> values;
};
std::vector<uint8_t> decode(const std::string & hex) {
    if (hex.size() % 2) throw std::runtime_error("Odd hex byte count");
    auto nibble = [](char c) -> int { if (c >= '0' && c <= '9') return c-'0'; if (c >= 'a' && c <= 'f') return c-'a'+10; throw std::runtime_error("Invalid hex"); };
    std::vector<uint8_t> result(hex.size() / 2);
    for (size_t i = 0; i < result.size(); ++i) result[i] = nibble(hex[2*i]) * 16 + nibble(hex[2*i+1]);
    return result;
}
int main(int argc, char ** argv) {
    if (argc != 4 && (argc != 5 || std::string(argv[4]) != "--independent")) return 2;
    const bool require_saved = argc == 4;
    Json report{{"passed", false}, {"scope", "Exact saved layer-6 flash attention replay; independent double softmax/dot/value reference. Not whole-model acceptance."}};
    try {
        Runtime::initialize(argv[1]);
        std::ifstream stream(argv[2]);
        const auto document = Json::parse(stream);
        const auto & result = document.at("result");
        std::unique_ptr<ggml_context, decltype(&ggml_free)> ctx(ggml_init({ggml_tensor_overhead()*16 + ggml_graph_overhead_custom(32, false), nullptr, true}), ggml_free);
        if (!ctx) throw std::runtime_error("Context allocation failed");
        std::map<std::string, Input> inputs;
        Json saved_output;
        for (const auto & node : result.at("node_probes")) {
            const auto name = node.at("name").get<std::string>();
            if (name.rfind("attention.", 0) != 0) continue;
            if (node.at("status") != "captured_exact_bytes") throw std::runtime_error("Incomplete attention capture");
            if (name == "attention.output-6") { saved_output = node; continue; }
            const auto shape = node.at("shape").get<std::vector<int64_t>>();
            if (shape.size() != 4) throw std::runtime_error("Invalid shape");
            const auto type_name = node.at("type").get<std::string>();
            if (type_name != "f32" && type_name != "f16") throw std::runtime_error("Unsupported type");
            const auto type = type_name == "f32" ? GGML_TYPE_F32 : GGML_TYPE_F16;
            Input input{ggml_new_tensor(ctx.get(), type, 4, shape.data()), decode(node.at("data_hex")), {}};
            if (input.bytes.size() != ggml_nbytes(input.tensor)) throw std::runtime_error("Byte count mismatch");
            input.values.resize(ggml_nelements(input.tensor));
            if (type == GGML_TYPE_F32) std::memcpy(input.values.data(), input.bytes.data(), input.bytes.size());
            else for (size_t i = 0; i < input.values.size(); ++i) {
                ggml_fp16_t half; std::memcpy(&half, input.bytes.data() + 2*i, 2);
                input.values[i] = ggml_fp16_to_fp32(half);
            }
            inputs.emplace(name, std::move(input));
        }
        auto & q = inputs.at("attention.q-6"); auto & k = inputs.at("attention.k-6"); auto & v = inputs.at("attention.v-6");
        auto optional = [&](const char * name) { auto it = inputs.find(name); return it == inputs.end() ? nullptr : it->second.tensor; };
        const auto params = saved_output.at("op_params").get<std::vector<int32_t>>();
        if (params.size() != GGML_MAX_OP_PARAMS / sizeof(int32_t)) throw std::runtime_error("Invalid operation parameters");
        float scale, bias, softcap;
        std::memcpy(&scale, params.data(), 4); std::memcpy(&bias, params.data()+1, 4); std::memcpy(&softcap, params.data()+2, 4);
        if (bias != 0 || q.tensor->ne[3] != 1 || inputs.count("attention.sinks-6")) throw std::runtime_error("Reference currently covers single-stream attention without ALiBi/sinks");
        auto * out = ggml_flash_attn_ext(ctx.get(), q.tensor, k.tensor, v.tensor, optional("attention.mask-6"), scale, bias, softcap);
        std::memcpy(out->op_params, params.data(), GGML_MAX_OP_PARAMS);
        auto * graph = ggml_new_graph_custom(ctx.get(), 32, false); ggml_build_forward_expand(graph, out);
        std::unique_ptr<ggml_backend, decltype(&ggml_backend_free)> backend(ggml_backend_init_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, nullptr), ggml_backend_free);
        if (!backend || !ggml_backend_supports_op(backend.get(), out)) throw std::runtime_error("Unsupported CPU graph");
        const auto reg = ggml_backend_dev_backend_reg(ggml_backend_get_device(backend.get()));
        const auto set_threads = reinterpret_cast<ggml_backend_set_n_threads_t>(ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_n_threads"));
        const int threads = std::stoi(argv[3]); if (threads < 1 || threads > 64) throw std::runtime_error("Invalid threads");
        if (set_threads) set_threads(backend.get(), threads);
        std::unique_ptr<ggml_backend_buffer, decltype(&ggml_backend_buffer_free)> buffer(ggml_backend_alloc_ctx_tensors(ctx.get(), backend.get()), ggml_backend_buffer_free);
        if (!buffer) throw std::runtime_error("Buffer allocation failed");
        for (const auto & entry : inputs) ggml_backend_tensor_set(entry.second.tensor, entry.second.bytes.data(), 0, entry.second.bytes.size());
        // Optional standalone diagnostic interposer; absent in normal replay/APK.
        const auto trace_start = reinterpret_cast<void (*)()>(dlsym(RTLD_DEFAULT, "motoai_exp_start"));
        const auto trace_stop = reinterpret_cast<void (*)()>(dlsym(RTLD_DEFAULT, "motoai_exp_stop"));
        if (trace_start) trace_start();
        const auto status = ggml_backend_graph_compute(backend.get(), graph);
        if (trace_stop) trace_stop();
        if (status != GGML_STATUS_SUCCESS) throw std::runtime_error("Graph failed");
        std::vector<float> actual(ggml_nelements(out)), saved(actual.size());
        ggml_backend_tensor_get(out, actual.data(), 0, actual.size()*4);
        const auto saved_bytes = decode(saved_output.at("data_hex"));
        if (saved_bytes.size() != actual.size()*4) throw std::runtime_error("Output size mismatch");
        std::memcpy(saved.data(), saved_bytes.data(), saved_bytes.size());
        size_t different = 0; double replay_peak = 0, squared_error = 0, squared_reference = 0, max_error = 0, peak_reference = 0;
        bool finite = true;
        const int64_t dk=q.tensor->ne[0], nq=q.tensor->ne[1], hq=q.tensor->ne[2], nk=k.tensor->ne[1], hk=k.tensor->ne[2], dv=v.tensor->ne[0];
        auto at = [](const Input & input, int64_t d, int64_t token, int64_t head) { return input.values[d + input.tensor->ne[0]*(token + input.tensor->ne[1]*head)]; };
        for (int64_t h=0; h<hq; ++h) for (int64_t t=0; t<nq; ++t) {
            std::vector<double> scores(nk), weights(nk); double maximum=-INFINITY, total=0;
            for (int64_t j=0; j<nk; ++j) {
                double dot=0; for (int64_t d=0; d<dk; ++d) dot += double(at(q,d,t,h))*at(k,d,j,h/(hq/hk));
                scores[j] = dot*scale;
                if (softcap != 0) scores[j] = softcap*std::tanh(scores[j]/softcap);
                auto mask=inputs.find("attention.mask-6");
                if (mask != inputs.end()) scores[j] += at(mask->second,j,t,h % mask->second.tensor->ne[2]);
                maximum=std::max(maximum,scores[j]);
            }
            for (int64_t j=0;j<nk;++j) { weights[j]=std::exp(scores[j]-maximum); total+=weights[j]; }
            if (!(total>0) || !std::isfinite(total)) throw std::runtime_error("Invalid reference softmax");
            for (int64_t d=0;d<dv;++d) {
                double reference=0; for(int64_t j=0;j<nk;++j) if (weights[j] != 0) reference += weights[j]*at(v,d,j,h/(hq/v.tensor->ne[2]));
                reference /= total;
                const auto index=d+dv*(h+hq*t);
                finite = finite && std::isfinite(actual[index]) && std::isfinite(saved[index]);
                if (std::memcmp(&actual[index],&saved[index],4)) ++different;
                replay_peak=std::max(replay_peak,std::abs(double(actual[index])-saved[index]));
                const double error=actual[index]-reference;
                squared_error+=error*error; squared_reference+=reference*reference;
                max_error=std::max(max_error,std::abs(error)); peak_reference=std::max(peak_reference,std::abs(reference));
            }
        }
        const double nmse=squared_error/std::max(squared_reference,1e-30), relative=max_error/std::max(peak_reference,1e-30);
        // Frozen before capturing this fixture; applies to the F32 tiled prefill path.
        const bool numeric_pass=finite && nmse<=1e-10 && relative<=1e-4;
        report.update({{"finite",finite},{"threads",threads},{"shape",saved_output.at("shape")},{"differing_output_values",different},
            {"saved_output_max_absolute_error",replay_peak},{"reference_nmse",nmse},{"reference_max_absolute_error",max_error},
            {"reference_max_relative_to_peak",relative},{"reference_gates",{{"nmse_max",1e-10},{"max_relative_to_peak_max",1e-4}}},
            {"independent_reference_passed",numeric_pass},{"saved_output_exact",different==0},{"system_info",llama_print_system_info()},
            {"saved_output_required",require_saved},{"passed",numeric_pass && (!require_saved || different==0)}});
        std::string hex; constexpr char digits[]="0123456789abcdef";
        const auto * bytes=reinterpret_cast<const uint8_t *>(actual.data());
        for(size_t i=0;i<actual.size()*4;++i) {hex+=digits[bytes[i]>>4];hex+=digits[bytes[i]&15];}
        report["output_hex"]=std::move(hex);
    } catch (const std::exception & error) { report["error"]=error.what(); }
    std::cout<<report.dump()<<'\n';
    return report.at("passed").get<bool>()?0:1;
}
