#include "nlohmann/json.hpp"
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <dlfcn.h>
#include <fstream>

std::string bits(float value) {
    uint32_t raw;
    std::memcpy(&raw, &value, sizeof(raw));
    std::ostringstream out;
    out << std::hex << std::setw(8) << std::setfill('0') << raw;
    return out.str();
}
uint64_t floating_control() {
    uint64_t control = 0;
#if defined(__aarch64__)
    asm volatile("mrs %0, fpcr" : "=r"(control));
#endif
    return control;
}

int main(int argc, char ** argv) {
    if (argc != 3) return 2;
    if (std::string(argv[1]) == "--exp-inputs") {
        std::ifstream input(argv[2]);
        const auto values = nlohmann::ordered_json::parse(input);
        nlohmann::ordered_json result = nlohmann::ordered_json::array();
        for (const auto & item : values) {
            const auto raw = static_cast<uint32_t>(std::stoul(item.get<std::string>(), nullptr, 16));
            float x; std::memcpy(&x, &raw, 4);
            result.push_back({{"input", bits(x)}, {"output", bits(::expf(x))}});
        }
        std::cout << result.dump() << '\n';
        return 0;
    }
    if (std::string(argv[1]) == "--tables") {
        void * library = dlopen(argv[2], RTLD_NOW | RTLD_LOCAL);
        if (!library) { std::cerr << dlerror() << '\n'; return 1; }
        const auto initialize = reinterpret_cast<void (*)()>(dlsym(library, "ggml_cpu_init"));
        const auto * gelu = reinterpret_cast<const uint16_t *>(dlsym(library, "ggml_table_gelu_f16"));
        const auto * quick = reinterpret_cast<const uint16_t *>(dlsym(library, "ggml_table_gelu_quick_f16"));
        if (!initialize || !gelu || !quick) { std::cerr << "Missing exported CPU activation table\n"; return 1; }
        initialize();
        nlohmann::ordered_json result{{"scope", "Actual initialized tables from the specified CPU backend library; no model inference"},
            {"library", argv[2]}, {"arm64_fpcr", floating_control()}, {"gelu_f16", nlohmann::ordered_json::array()}, {"gelu_quick_f16", nlohmann::ordered_json::array()}};
        for (int i = 0; i < 65536; ++i) { result["gelu_f16"].push_back(gelu[i]); result["gelu_quick_f16"].push_back(quick[i]); }
        std::cout << result.dump() << '\n';
        dlclose(library);
        return 0;
    }
    // Runtime argv prevents compiler constant-folding from replacing libm calls.
    char * end = nullptr;
    const float base = std::strtof(argv[1], &end);
    if (!end || *end || !std::isfinite(base) || base < 2 || base > 10000000) return 2;
    const long dimensions = std::strtol(argv[2], &end, 10);
    if (!end || *end || dimensions < 2 || dimensions > 1024 || dimensions % 2) return 2;
    using Json = nlohmann::ordered_json;
    const float scale = ::powf(base, -2.0f / static_cast<float>(dimensions));
    Json result{{"scope", "Scalar libm component of pinned CPU RoPE (no model or graph inference)"},
                {"base", base}, {"dimensions", dimensions}, {"theta_scale_bits", bits(scale)}, {"values", Json::array()}};
    for (int position = 0; position < 5; ++position) {
        float theta = static_cast<float>(position);
        for (int axis = 0; axis < dimensions / 2; ++axis) {
            const float cosine = ::cosf(theta);
            const float sine = ::sinf(theta);
            const float cosine64 = static_cast<float>(std::cos(static_cast<double>(theta)));
            const float sine64 = static_cast<float>(std::sin(static_cast<double>(theta)));
            result["values"].push_back({{"position", position}, {"axis", axis}, {"theta_bits", bits(theta)},
                {"cos_bits", bits(cosine)}, {"sin_bits", bits(sine)},
                {"double_cos_to_float_bits", bits(cosine64)}, {"double_sin_to_float_bits", bits(sine64)}});
            theta *= scale;
        }
    }
    std::cout << result.dump(2) << '\n';
}
