#pragma once
#include "runtime.h"
#include "ggml-cpu.h"

namespace motoai {
// CPU pools borrow no weight/state storage. Their lifetime surrounds the context.
struct CpuPools {
    ggml_threadpool * decode = nullptr;
    ggml_threadpool * batch = nullptr;
    decltype(ggml_threadpool_free) * free_pool = nullptr;
    std::vector<int32_t> worker_tids;
    ~CpuPools();
};

class CallerAffinity {
public:
    explicit CallerAffinity(bool restore);
    ~CallerAffinity();
    void apply(const std::vector<int32_t> & cpus);
    const std::vector<int32_t> & allowed() const { return allowed_; }
private:
    bool restore_;
    std::vector<int32_t> allowed_;
};
void validate_cpu_scheduling(const Config & config, const std::vector<int32_t> & allowed);
std::shared_ptr<CpuPools> make_cpu_pools(const Config & config);
std::vector<CpuThreadAffinity> verify_cpu_pools(const CpuPools * pools, const Config & config);
}
