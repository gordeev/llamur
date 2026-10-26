#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to stable NDK r28c}"
: "${ANDROID_HOME:?Set ANDROID_HOME to an SDK with API 36}"
: "${MOTOAI_CMAKE_DIR:?Set MOTOAI_CMAKE_DIR to Android SDK CMake 3.31.6}"
grep -q 'Pkg.Revision = 28.2.13676358' "$ANDROID_NDK_ROOT/source.properties"
python3 scripts/configure-android.py --sdk "$ANDROID_HOME" \
    --ndk "$ANDROID_NDK_ROOT" --cmake "$MOTOAI_CMAKE_DIR"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.tools/gradle}"
export CMAKE_BUILD_PARALLEL_LEVEL="${BUILD_JOBS:-4}"
python3 scripts/prepare-cpu-variant.py "${MOTOAI_CPU_VARIANT:-f32-recurrent-long-attention-v2}"
./gradlew -PmotoaiCpuVariant="${MOTOAI_CPU_VARIANT:-f32-recurrent-long-attention-v2}" --no-daemon --max-workers=4 :app:assembleDebug
python3 scripts/archive-build.py
