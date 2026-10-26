#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=$(python3 -c 'import json; print(json.load(open("upstream.lock.json"))["commit"])')
repo=https://github.com/ggml-org/llama.cpp.git
target=third_party/llama.cpp
if [ ! -d "$target/.git" ]; then
    git clone --depth 1 "$repo" "$target"
fi
if [ -n "$(git -C "$target" status --porcelain)" ]; then
    echo 'Upstream checkout contains changes; refusing to overwrite them.' >&2
    exit 1
fi
git -C "$target" fetch --depth 1 origin "$revision"
git -C "$target" checkout --detach "$revision"
test "$(git -C "$target" rev-parse HEAD)" = "$revision"
