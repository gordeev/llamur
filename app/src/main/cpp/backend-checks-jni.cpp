#include "numeric-cases.h"
#include <jni.h>
#include <algorithm>
#include <cstring>

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_motoai_runtime_BackendNumeric_runNative(JNIEnv * env, jobject, jbyteArray payload) {
    using namespace motoai;
    Json report{{"passed", false}, {"cases", Json::array()},
        {"scope", "Synthetic MUL_MAT vs independent F64 original-F32-activation oracle and exact weight-byte roundtrip; no whole-model/speed acceptance"}};
    try {
        const auto size = env->GetArrayLength(payload);
        if (size <= 0 || size > 65536) throw std::invalid_argument("Invalid numeric specification");
        std::string data(static_cast<size_t>(size), '\0');
        env->GetByteArrayRegion(payload, 0, size, reinterpret_cast<jbyte *>(data.data()));
        if (env->ExceptionCheck()) return nullptr;
        const auto spec = Json::parse(data);
        const auto selected = spec.at("backend").get<std::string>();
        if (selected != "vulkan" && selected != "opencl") throw std::invalid_argument("Explicit GPU backend required");
        const auto reg_name = selected == "vulkan" ? "Vulkan" : "OpenCL";
        std::vector<ggml_backend_dev_t> devices;
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            auto device = ggml_backend_dev_get(i);
            if (std::string(ggml_backend_reg_name(ggml_backend_dev_backend_reg(device))) == reg_name &&
                (ggml_backend_dev_type(device) == GGML_BACKEND_DEVICE_TYPE_GPU ||
                 ggml_backend_dev_type(device) == GGML_BACKEND_DEVICE_TYPE_IGPU)) devices.push_back(device);
        }
        if (devices.size() != 1) throw std::runtime_error("Expected one actual selected GPU device");
        std::unique_ptr<ggml_backend, decltype(&ggml_backend_free)> backend(ggml_backend_dev_init(devices[0], nullptr), ggml_backend_free);
        if (!backend) throw std::runtime_error("GPU initialization failed");
        const auto buft = ggml_backend_dev_buffer_type(devices[0]);
        report["backend"] = selected;
        report["device"] = ggml_backend_dev_name(devices[0]);
        report["description"] = ggml_backend_dev_description(devices[0]);
        report["buffer"] = ggml_backend_buft_name(buft);
        bool passed = true;
        // Original fixed M2 accuracy budgets, unchanged. CPU represented-Q8
        // activation controls are separate and are not reused as a GPU oracle.
        for (auto type : {GGML_TYPE_F32, GGML_TYPE_F16, GGML_TYPE_BF16, GGML_TYPE_Q4_K, GGML_TYPE_Q5_K, GGML_TYPE_Q6_K}) {
            for (int columns : {1, 16, 128}) for (int seed : {1, 17, 91}) {
                if (selected == "opencl" && type == GGML_TYPE_BF16) {
                    report["cases"].push_back(Json{{"type", "bf16"}, {"tokens", columns}, {"seed", seed},
                        {"status", "storage_conversion_forbidden"}, {"note", "Pinned backend stores BF16 as F16"}});
                    passed = false;
                    continue;
                }
                auto result = numeric_case(backend.get(), buft, type, columns, seed, spec.at("gates"), 256, nullptr, true);
                if (result.at("status") != "pass") passed = false;
                report["cases"].push_back(std::move(result));
            }
        }
        report["passed"] = passed;
    } catch (const std::exception & failure) { report["error"] = failure.what(); }
    const auto output = report.dump(-1, ' ', false, Json::error_handler_t::replace);
    const auto bytes = env->NewByteArray(static_cast<jsize>(output.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte *>(output.data()));
    return bytes;
}
