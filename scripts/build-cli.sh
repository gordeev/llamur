#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to a pinned NDK directory}"
revision=$(python3 -c 'import json; print(json.load(open("upstream.lock.json"))["commit"])')
test "$(git -C third_party/llama.cpp rev-parse HEAD)" = "$revision"
cmake -S third_party/llama.cpp -B build/android-cpu -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
    -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF \
    -DGGML_CPU_KLEIDIAI=OFF -DGGML_VULKAN=OFF -DGGML_OPENCL=OFF \
    -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=ON -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_APP=OFF \
    -DBUILD_SHARED_LIBS=ON
cmake --build build/android-cpu --parallel "${BUILD_JOBS:-6}" \
    --target llama-completion llama-bench llama-tokenize test-backend-ops
