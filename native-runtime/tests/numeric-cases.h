#pragma once
#include "protocol.h"
namespace motoai {
Json numeric_case(ggml_backend_t backend, ggml_backend_buffer_type_t buft, ggml_type type,
                  int columns, int seed, const Json & gates, int k = 256,
                  std::vector<uint8_t> * output_bytes = nullptr, bool original_f32_reference = false);
}
