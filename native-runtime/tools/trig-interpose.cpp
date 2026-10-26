// macOS-only diagnostic: substitute independently measured Android float trig
// at exact input bits. Not linked into the runtime/APK or a performance profile.
#include <math.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "trig-table.h"

namespace {
std::atomic<uint64_t> sin_calls{0}, cos_calls{0}, pair_calls{0}, tiny_calls{0};
float as_float(uint32_t bits) { float value; std::memcpy(&value, &bits, sizeof(value)); return value; }
TrigRow lookup(float angle) {
    uint32_t key;
    std::memcpy(&key, &angle, sizeof(key));
    // At |x|<2^-13 the correctly rounded binary32 values are sin(x)=x,
    // cos(x)=1 (round-to-nearest, FPCR0). Gemma proportional RoPE also uses
    // extremely small angles. Keep this explicit; all other unmeasured inputs abort.
    if ((key & 0x7fffffffU) < 0x39000000U) {
        ++tiny_calls;
        return {key, key, 0x3f800000U};
    }
    size_t low = 0, high = sizeof(trig_rows) / sizeof(trig_rows[0]);
    while (low < high) {
        const size_t middle = low + (high - low) / 2;
        if (trig_rows[middle].angle < key) low = middle + 1;
        else high = middle;
    }
    if (low == sizeof(trig_rows) / sizeof(trig_rows[0]) || trig_rows[low].angle != key) {
        std::fprintf(stderr, "MOTOAI_TRIG_UNKNOWN %08x\n", key);
        std::abort(); // A partial lookup must never silently act as a full control.
    }
    return trig_rows[low];
}
float measured_sin(float angle) { ++sin_calls; return as_float(lookup(angle).sine); }
float measured_cos(float angle) { ++cos_calls; return as_float(lookup(angle).cosine); }
__float2 measured_pair(float angle) {
    ++pair_calls;
    const auto row = lookup(angle);
    return {as_float(row.sine), as_float(row.cosine)};
}
__attribute__((destructor)) void counts() {
    std::fprintf(stderr, "MOTOAI_TRIG_CONTROL sin=%llu cos=%llu pair=%llu tiny=%llu\n",
        static_cast<unsigned long long>(sin_calls.load()), static_cast<unsigned long long>(cos_calls.load()),
        static_cast<unsigned long long>(pair_calls.load()), static_cast<unsigned long long>(tiny_calls.load()));
}
// Mach-O interposition also covers Apple's combined float sin/cos entry point.
__attribute__((used)) static const struct { const void * replacement; const void * original; }
interpose[] __attribute__((section("__DATA,__interpose"))) = {
    {reinterpret_cast<const void *>(&measured_sin), reinterpret_cast<const void *>(&::sinf)},
    {reinterpret_cast<const void *>(&measured_cos), reinterpret_cast<const void *>(&::cosf)},
    {reinterpret_cast<const void *>(&measured_pair), reinterpret_cast<const void *>(&::__sincosf_stret)}
};
}
