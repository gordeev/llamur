#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
root="$PWD"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to NDK 29.0.13113456 for B0}"
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK with API 36 and build-tools}"
: "${MOTOAI_CMAKE_DIR:?Set MOTOAI_CMAKE_DIR to Android SDK CMake 3.31.6}"
grep -q 'Pkg.Revision = 29.0.13113456' "$ANDROID_NDK_ROOT/source.properties"
revision=$(python3 -c 'import json; print(json.load(open("upstream.lock.json"))["commit"])')
variant="${MOTOAI_BASELINE_VARIANT:-B0}"
if [ "$variant" = B0 ]; then
    source_dir=third_party/llama.cpp
    test -z "$(git -C "$source_dir" status --porcelain)"
elif [ "$variant" = B1-jinja ] || [ "$variant" = B1-jinja-no-mmap ]; then
    source_dir=$(python3 scripts/prepare-b1.py --variant "$variant")
else
    echo "Unknown baseline variant: $variant" >&2
    exit 1
fi
test "$(git -C "$source_dir" rev-parse HEAD)" = "$revision"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$root/.tools/gradle}"
export CMAKE_BUILD_PARALLEL_LEVEL="${BUILD_JOBS:-4}"
python3 scripts/configure-android.py --project "$source_dir/examples/llama.android" \
    --sdk "$ANDROID_HOME" --ndk "$ANDROID_NDK_ROOT" --cmake "$MOTOAI_CMAKE_DIR"
"$source_dir/examples/llama.android/gradlew" \
    -p "$source_dir/examples/llama.android" \
    --no-daemon --max-workers=4 -Pandroid.injected.build.abi=arm64-v8a \
    :app:assembleDebug
