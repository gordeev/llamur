#pragma once
#include "runtime.h"
#include "nlohmann/json.hpp"

namespace motoai {
using Json = nlohmann::ordered_json;
Config parse_config(const Json & data);
Request parse_request(const Json & data);
Json config_json(const Config & config, int64_t load_us);
Json result_json(const Result & result);
Json memory_json(const std::vector<BufferMemory> & memory);
Json plan_json(const ModelPlan & plan);
}
