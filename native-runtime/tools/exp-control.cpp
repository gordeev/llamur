// Standalone replay diagnostic only. Never linked into the APK/native runtime.
#include <math.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <map>
#include <mutex>
#ifdef MOTOAI_EXP_SUBSTITUTE
#include "exp-table.h"
#endif
namespace {
std::atomic<bool> active{false};
std::mutex guard;
std::map<uint32_t, uint32_t> measured;
uint64_t calls=0;
uint32_t bits(float value) { uint32_t key; std::memcpy(&key,&value,4); return key; }
float measured_exp(float x) {
#if defined(__APPLE__)
    // Only substitution is supported on Mach-O. Native expf values are measured
    // separately by motoai-math, without interposition. No outside-graph expf
    // initialization contributes to this attention-only graph.
#ifndef MOTOAI_EXP_SUBSTITUTE
#error "On Apple use --exp-inputs in motoai-math to record native expf"
#endif
    if (!active.load()) return static_cast<float>(::exp(static_cast<double>(x)));
#else
    static const auto original=reinterpret_cast<float (*)(float)>(dlsym(RTLD_NEXT,"expf"));
    if (!original) std::abort();
    if (!active.load()) return original(x);
#endif
    const auto key=bits(x);
    float value;
#ifdef MOTOAI_EXP_SUBSTITUTE
    size_t low=0,high=sizeof(exp_rows)/sizeof(exp_rows[0]);
    while(low<high) {const auto mid=low+(high-low)/2; if(exp_rows[mid].input<key) low=mid+1; else high=mid;}
    if(low==sizeof(exp_rows)/sizeof(exp_rows[0]) || exp_rows[low].input!=key) {
        std::fprintf(stderr,"MOTOAI_EXP_UNKNOWN %08x\n",key); std::abort();
    }
    std::memcpy(&value,&exp_rows[low].output,4);
#else
    value=original(x);
#endif
    std::lock_guard<std::mutex> lock(guard);
    auto found=measured.find(key);
    if(found!=measured.end() && found->second!=bits(value)) std::abort();
    measured[key]=bits(value);++calls;
    return value;
}
}
extern "C" void motoai_exp_start() { std::lock_guard<std::mutex> lock(guard); measured.clear();calls=0;active.store(true); }
extern "C" void motoai_exp_stop() {
    active.store(false);std::lock_guard<std::mutex> lock(guard);
    std::fprintf(stderr,"MOTOAI_EXP_CALLS %llu\n",static_cast<unsigned long long>(calls));
    for(const auto & row:measured) std::fprintf(stderr,"MOTOAI_EXP_ROW %08x %08x\n",row.first,row.second);
}
#if defined(__APPLE__)
__attribute__((used)) static const struct {const void * replacement;const void * original;}
interpose[] __attribute__((section("__DATA,__interpose"))) = {
    {reinterpret_cast<const void *>(&measured_exp),reinterpret_cast<const void *>(&::expf)}
};
#else
extern "C" float expf(float value) {return measured_exp(value);}
#endif
