#include "cpu-scheduling.h"
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <stdexcept>
#if defined(__linux__)
#include <dirent.h>
#include <sched.h>
#include <unistd.h>
#endif

namespace motoai {
namespace {
std::vector<int32_t> affinity(int32_t tid = 0) {
    std::vector<int32_t> cpus;
#if defined(__linux__)
    cpu_set_t mask;
    if (sched_getaffinity(tid, sizeof(mask), &mask)) throw std::runtime_error("Cannot read CPU affinity");
    for (int i = 0; i < CPU_SETSIZE; ++i) if (CPU_ISSET(i, &mask)) cpus.push_back(i);
#else
    (void) tid;
#endif
    return cpus;
}
std::vector<int32_t> tids() {
    std::vector<int32_t> result;
#if defined(__linux__)
    auto * directory = opendir("/proc/self/task");
    if (!directory) throw std::runtime_error("Cannot inventory CPU worker threads");
    while (auto * entry = readdir(directory)) {
        char * end = nullptr;
        const auto tid = std::strtol(entry->d_name, &end, 10);
        if (end != entry->d_name && *end == '\0' && tid > 0) result.push_back(static_cast<int32_t>(tid));
    }
    closedir(directory);
#endif
    return result;
}
}
CpuPools::~CpuPools() {
    if (free_pool) { free_pool(decode); free_pool(batch); }
}
CallerAffinity::CallerAffinity(bool restore) : restore_(restore), allowed_(affinity()) {}
CallerAffinity::~CallerAffinity() {
#if defined(__linux__)
    if (restore_ && !allowed_.empty()) {
        cpu_set_t mask;
        CPU_ZERO(&mask);
        for (auto cpu : allowed_) CPU_SET(cpu, &mask);
        if (sched_setaffinity(0, sizeof(mask), &mask)) std::fputs("MotoAI: failed to restore caller CPU affinity\n", stderr);
    }
#endif
}
void CallerAffinity::apply(const std::vector<int32_t> & cpus) {
    if (cpus.empty()) return;
#if defined(__linux__)
    cpu_set_t mask;
    CPU_ZERO(&mask);
    for (auto cpu : cpus) CPU_SET(cpu, &mask);
    if (sched_setaffinity(0, sizeof(mask), &mask) || affinity() != cpus)
        throw std::runtime_error("Cannot apply exact caller CPU affinity");
#else
    throw std::invalid_argument("Explicit CPU affinity unavailable on this platform");
#endif
}
void validate_cpu_scheduling(const Config & config, const std::vector<int32_t> & allowed) {
    if (config.threads_batch < 0 || config.threads_batch > 64) throw std::invalid_argument("Invalid batch threads");
    // The pinned CPU barrier/polling path has a measured severe slowdown with
    //8 workers on this app's7 allowed CPUs. Reject oversubscription before
    // replacing a model and revalidate after Android cpuset changes.
    if (!allowed.empty() && static_cast<size_t>(std::max(config.threads, config.threads_batch)) > allowed.size())
        throw std::invalid_argument("CPU threads exceed current allowed CPU count; oversubscription is unsupported");
    std::vector<int32_t> seen;
    for (const auto cpu : config.cpu_affinity) {
        if (cpu < 0 || cpu >= GGML_MAX_N_THREADS || cpu > 127 ||
            std::find(seen.begin(), seen.end(), cpu) != seen.end()) throw std::invalid_argument("Invalid CPU affinity");
        if (std::find(allowed.begin(), allowed.end(), cpu) == allowed.end())
            throw std::invalid_argument("Requested CPU is outside current caller allowed set; no implicit intersection");
        seen.push_back(cpu);
    }
    if (!seen.empty() && static_cast<size_t>(std::max(config.threads, config.threads_batch)) > seen.size())
        throw std::invalid_argument("CPU affinity would oversubscribe the selected group");
}
std::shared_ptr<CpuPools> make_cpu_pools(const Config & config) {
    if (!config.threads_batch && config.cpu_affinity.empty()) return {};
    auto * device = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (!device) throw std::runtime_error("CPU scheduling requires CPU backend");
    auto * registry = ggml_backend_dev_backend_reg(device);
    const auto make_pool = reinterpret_cast<decltype(ggml_threadpool_new) *>(ggml_backend_reg_get_proc_address(registry, "ggml_threadpool_new"));
    const auto free_pool = reinterpret_cast<decltype(ggml_threadpool_free) *>(ggml_backend_reg_get_proc_address(registry, "ggml_threadpool_free"));
    if (!make_pool || !free_pool) throw std::runtime_error("CPU threadpool API unavailable; no implicit fallback");
    auto result = std::make_shared<CpuPools>();
    result->free_pool = free_pool;
    auto decode = ggml_threadpool_params_default(config.threads);
    auto batch = ggml_threadpool_params_default(config.threads_batch ? config.threads_batch : config.threads);
    for (const auto cpu : config.cpu_affinity) decode.cpumask[cpu] = batch.cpumask[cpu] = true;
    // Keep upstream normal priority, poll=50 and group placement (not strict per-core).
    const auto before = tids();
    if (!ggml_threadpool_params_match(&decode, &batch)) {
        result->batch = make_pool(&batch);
        if (!result->batch) throw std::runtime_error("Batch CPU threadpool allocation failed");
        decode.paused = true;
    }
    result->decode = make_pool(&decode);
    if (!result->decode) throw std::runtime_error("Decode CPU threadpool allocation failed");
    for (const auto tid : tids()) if (std::find(before.begin(), before.end(), tid) == before.end()) result->worker_tids.push_back(tid);
#if defined(__linux__)
    const auto expected = config.threads - 1 + (result->batch ? batch.n_threads - 1 : 0);
    if (result->worker_tids.size() != static_cast<size_t>(expected))
        throw std::runtime_error("Ambiguous CPU pool worker inventory; cannot verify placement");
#endif
    return result;
}
std::vector<CpuThreadAffinity> verify_cpu_pools(const CpuPools * pools, const Config & config) {
    std::vector<CpuThreadAffinity> result;
#if defined(__linux__)
    auto workers = pools ? pools->worker_tids : std::vector<int32_t>{};
    workers.push_back(static_cast<int32_t>(gettid()));
    for (const auto tid : workers) {
        auto cpus = affinity(tid);
        if (!config.cpu_affinity.empty() && cpus != config.cpu_affinity)
            throw std::runtime_error("Actual CPU worker affinity differs from requested group");
        result.push_back({tid, std::move(cpus)});
    }
#else
    (void) pools; (void) config;
#endif
    return result;
}
}
