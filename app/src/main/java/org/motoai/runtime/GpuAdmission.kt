package org.motoai.runtime

import org.json.JSONObject

/** Explicit research budget. no_alloc sizes do not include upload copies or the OEM driver. */
internal object GpuAdmission {
    private const val MIB = 1024L * 1024
    fun evaluate(base: JSONObject, plan: JSONObject, sha: String?, config: JSONObject): JSONObject {
        val result = JSONObject(base.toString())
        val supported = sha == "70c112196e0b7023803c9762752e46d29e612a92c83f995bc3ba1ceb07e8fab6" &&
            config.optString("backend") == "opencl" && config.optString("weight_buffers", "native") == "native" &&
            config.optString("load_mode") == "mmap" && !config.optBoolean("mmap_prefault", true) &&
            !config.optBoolean("transient_repack", false) && config.optInt("prefix_cache_mib", 0) == 0 &&
            config.optInt("threads", 4) in 1..4 && config.optInt("threads_batch", 0) in 0..4 &&
            plan.optBoolean("no_alloc") && plan.optLong("gpu_model_bytes") > 0
        val gpu = plan.getLong("gpu_model_bytes")
        val context = plan.getLong("context_bytes")
        val compute = plan.getLong("compute_bytes")
        val source = plan.getLong("largest_gpu_source_bytes")
        require(listOf(gpu, context, compute, source).all { it in 0..64L * 1024 * MIB }) { "Invalid native GPU plan size" }
        // Native CPU mmap working window follows the pinned Ornith CPU budget.
        // OpenCL upload uses a temporary device buffer AND the original host
        // source tensor. They precede context allocation; reserve the larger phase.
        val cpuWindow = maxOf(1024 * MIB, plan.getLong("largest_cpu_tensor_bytes"))
        val upload = 2 * source
        val inference = cpuWindow + context + compute
        val driver = 512 * MIB
        val required = gpu + maxOf(upload, inference) + driver + base.getLong("headroom_bytes")
        val accepted = supported && !base.getBoolean("low_memory") && base.getLong("effective_available_bytes") >= required
        return result.put("accepted", accepted).put("weight_policy_supported", supported)
            .put("required_bytes", required).put("profile_qualified", false)
            .put("profile", "experimental-opencl-metadata-budget-v1")
            .put("gpu_model_reserve_bytes", gpu).put("gpu_driver_reserve_bytes", driver)
            .put("gpu_upload_phase_bytes", upload).put("gpu_inference_phase_bytes", inference)
            .put("gpu_plan", plan).put("rejection_reason", if (!supported) "unsupported_gpu_research_profile" else if (!accepted) "gpu_memory_budget" else JSONObject.NULL)
            .put("scope", "Pinned no_alloc selected buffers + native mmap window + max(upload copies,context/compute) +512MiB driver +unchanged system headroom. Experimental; not a guaranteed peak or protection against concurrent pressure.")
    }
}
