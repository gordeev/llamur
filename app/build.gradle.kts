plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
providers.gradleProperty("motoaiBuildDirectory").orNull?.let { relative ->
    val destination = rootProject.file(relative).canonicalFile
    require(destination.toPath().startsWith(rootProject.file("build").canonicalFile.toPath())) {
        "Isolated build directory must be inside the repository build/"
    }
    layout.buildDirectory.set(destination)
}
val gpuBackend = providers.gradleProperty("motoaiGpuBackend").getOrElse("none")
require(gpuBackend in listOf("none", "vulkan", "opencl", "both")) { "motoaiGpuBackend must be none, vulkan, opencl or both" }
val cpuVariant = providers.gradleProperty("motoaiCpuVariant").getOrElse(
    if (gpuBackend == "none") "f32-recurrent-long-attention-v2" else "f32-recurrent-selective-backends")
require(gpuBackend == "none" || cpuVariant in listOf("f32-recurrent-selective-backends", "f32-recurrent-literal-chat", "f32-recurrent-opencl-matmul-only")) { "GPU experiments require selective driver loading" }
val kleidiAI = providers.gradleProperty("motoaiCpuKleidiAI").getOrElse("false").let {
    when (it) { "true" -> "ON"; "false" -> "OFF"; else -> error("motoaiCpuKleidiAI must be true or false") }
}
val cpuSources = mapOf("baseline" to "third_party/llama.cpp", "f32-attention-scalar-math" to ".tools/llama-cpu-f32-math-v2",
    "f32-recurrent-scalar-math" to ".tools/llama-cpu-f32-recurrent",
    "f32-recurrent-repack-stream" to ".tools/llama-cpu-repack-stream",
    "f32-recurrent-repack-order" to ".tools/llama-cpu-repack-order",
    "f32-recurrent-repack-cache" to ".tools/llama-cpu-repack-cache",
    "f32-recurrent-repack-selective" to ".tools/llama-cpu-repack-selective",
    "f32-recurrent-native-demand" to ".tools/llama-cpu-native-demand",
    "f32-recurrent-repack-q4" to ".tools/llama-cpu-repack-q4",
    "f32-recurrent-transient-pack" to ".tools/llama-cpu-transient-pack-v2",
    "f32-recurrent-transient-model" to ".tools/llama-cpu-transient-model",
    "f32-recurrent-transient-threshold" to ".tools/llama-cpu-transient-threshold",
    "f32-recurrent-stable-attention-v2" to ".tools/llama-cpu-stable-attention-v2",
    "f32-recurrent-selective-backends" to ".tools/llama-gpu-selective",
    "f32-recurrent-literal-chat" to ".tools/llama-literal-chat",
    "f32-recurrent-opencl-matmul-only" to ".tools/llama-opencl-matmul-only",
    "f32-recurrent-state-layout-v2" to ".tools/llama-state-layout-v2",
    "f32-recurrent-long-attention-v2" to ".tools/llama-long-attention-v2")
val llamaSource = rootProject.file(cpuSources[cpuVariant] ?: error("Unknown CPU patch variant"))
val gpuHost = when {
    System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
    System.getProperty("os.name").startsWith("Windows") -> "windows-x86_64"
    else -> "linux-x86_64"
}
val gpuNdk = providers.environmentVariable("ANDROID_NDK_ROOT").getOrElse(rootProject.file(".tools/android-ndk-r28c").absolutePath)
val gpuGlslc = providers.gradleProperty("motoaiGlslc").getOrElse("$gpuNdk/shader-tools/$gpuHost/glslc")
android {
    buildFeatures { buildConfig = true }
    namespace = "org.motoai.runtime"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "org.llamur.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 12
        versionName = "0.6.4-dev"
        buildConfigField("boolean", "EXACT_STATE_LAYOUT", (cpuVariant in listOf("f32-recurrent-state-layout-v2", "f32-recurrent-long-attention-v2")).toString())
        buildConfigField("String", "GPU_BACKEND", "\"$gpuBackend\"")
        buildConfigField("boolean", "TRANSIENT_REPACK", (cpuVariant in listOf("f32-recurrent-transient-model", "f32-recurrent-transient-threshold", "f32-recurrent-stable-attention-v2", "f32-recurrent-selective-backends", "f32-recurrent-literal-chat", "f32-recurrent-opencl-matmul-only", "f32-recurrent-state-layout-v2", "f32-recurrent-long-attention-v2")).toString())
        buildConfigField("int", "TRANSIENT_MIN_ROWS", if (cpuVariant in listOf("f32-recurrent-transient-threshold", "f32-recurrent-stable-attention-v2", "f32-recurrent-selective-backends", "f32-recurrent-literal-chat", "f32-recurrent-opencl-matmul-only", "f32-recurrent-state-layout-v2", "f32-recurrent-long-attention-v2")) "24" else "4")
        buildConfigField("boolean", "STREAMED_REPACK_SOURCES",
            (cpuVariant in listOf("f32-recurrent-repack-stream", "f32-recurrent-repack-order", "f32-recurrent-repack-cache", "f32-recurrent-repack-selective", "f32-recurrent-native-demand", "f32-recurrent-repack-q4", "f32-recurrent-transient-pack")).toString())
        testInstrumentationRunner = "org.motoai.runtime.RuntimeInstrumentation"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release",
            "-DMOTOAI_LLAMA_SOURCE=${llamaSource.absolutePath}", "-DMOTOAI_RUNTIME_VARIANT=$cpuVariant",
            "-DMOTOAI_CPU_KLEIDIAI=$kleidiAI", "-DMOTOAI_GPU_BACKEND=$gpuBackend",
            "-DGGML_OPENCL_MOTOAI_MATMUL_ONLY=${if (cpuVariant == "f32-recurrent-opencl-matmul-only") "ON" else "OFF"}")
            if (gpuBackend in listOf("vulkan", "both")) arguments += listOf(
                "-DVulkan_INCLUDE_DIR=${rootProject.file(".tools/gpu-deps/Vulkan-Headers-4c8aa84a61c12cab9780fa44298617666b491d71/include")}",
                "-DSPIRV-Headers_DIR=${rootProject.file(".tools/gpu-deps/install/share/cmake/SPIRV-Headers")}",
                "-DMOTOAI_SPIRV_INCLUDE=${rootProject.file(".tools/gpu-deps/install/include")}",
                "-DVulkan_GLSLC_EXECUTABLE=$gpuGlslc")
            if (gpuBackend in listOf("opencl", "both")) arguments += listOf(
                "-DOpenCL_INCLUDE_DIR=${rootProject.file(".tools/gpu-deps/OpenCL-Headers-8a97ebc88daa3495d6f57ec10bb515224400186f")}",
                "-DOpenCL_LIBRARY=${rootProject.file("build/android-opencl-link/loader/libOpenCL.so")}") } }
    }
    buildTypes {getByName("debug") {applicationIdSuffix=".debug";versionNameSuffix="-debug"}}
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    packaging { jniLibs {
        useLegacyPackaging = true
        // Link-only open loader and Android system stubs must never shadow OEM libraries.
        excludes += setOf("**/libOpenCL.so", "**/libvulkan.so")
    } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
