// Host-only causal control, never linked into the APK. Load an independently
// measured complete Android table before any inference, without changing weights.
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include "gelu-table.h"

namespace {
uint16_t * actual = nullptr;
__attribute__((constructor)) void install_table() {
    const auto initialize = reinterpret_cast<void (*)()>(dlsym(RTLD_DEFAULT, "ggml_cpu_init"));
    actual = reinterpret_cast<uint16_t *>(dlsym(RTLD_DEFAULT, "ggml_table_gelu_f16"));
    if (!initialize || !actual) { std::fprintf(stderr, "MOTOAI_GELU_CONTROL missing CPU symbols\n"); std::abort(); }
    initialize();
    size_t changed = 0;
    for (size_t i = 0; i < 65536; ++i) if (actual[i] != measured_gelu[i]) ++changed;
    std::memcpy(actual, measured_gelu, sizeof(measured_gelu));
    std::fprintf(stderr, "MOTOAI_GELU_CONTROL changed=%zu\n", changed);
}
__attribute__((destructor)) void verify_table() {
    const bool same = actual && std::memcmp(actual, measured_gelu, sizeof(measured_gelu)) == 0;
    std::fprintf(stderr, "MOTOAI_GELU_CONTROL final_match=%d\n", same);
    if (!same) std::abort();
}
}
