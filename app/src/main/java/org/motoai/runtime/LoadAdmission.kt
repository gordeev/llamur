package org.motoai.runtime

import org.json.JSONObject

/** Conservative screening, not a promise against concurrent system memory pressure. */
internal object LoadAdmission {
    private const val MIB = 1024L * 1024
    private data class Profile(val model: Long, val fixedContext: Long, val contextPerToken: Long, val compute: Long, val mappedWindow: Long)
    // Pinned CPU/F16-KV/no-extra-buffers, 256-token padding; matches measured
    // ctx128/4096/8192 allocations. Compute uses the <=b512/ub128 upper budget.
    private val profiles = mapOf(
        "70c112196e0b7023803c9762752e46d29e612a92c83f995bc3ba1ceb07e8fab6" to Profile(5618141184, 52690944, 32768, 141826592, 1024 * MIB),
        "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87" to Profile(5338832192, 0, 57344, 150478848, 2048 * MIB)
    )
    // Separate no_alloc plans, checked against actual allocations in M3 tests.
    // Native/source windows are additive here, not the optimistic max of phases.
    private data class Repack(val packed: Long, val nativeWindow: Long, val sourceWindow: Long)
    private val repackProfiles = mapOf(
        "70c112196e0b7023803c9762752e46d29e612a92c83f995bc3ba1ceb07e8fab6" to Repack(5041766400, 572129280, 834355200),
        "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87" to Repack(2746060800, 1937768448, 377487360)
    )
    private val q6RepackProfiles = mapOf(
        // Metadata plan1839022080 committed bytes; retain the original native
        // mmap working window for the Q4_K tensors that remain mapped.
        "70c112196e0b7023803c9762752e46d29e612a92c83f995bc3ba1ceb07e8fab6" to Repack(1839022080, 1024 * MIB, 834355200)
    )
    private val q4RepackProfiles = mapOf(
        // Q4 linear weights only. The2GiB native window covers the remaining
        // Q6 dense matrices (including the native output head); input embedding
        // rows remain clean mapped pages. No optimistic1GiB window here.
        "70c112196e0b7023803c9762752e46d29e612a92c83f995bc3ba1ceb07e8fab6" to Repack(3202744320, 2048 * MIB, 28311552)
    )
    fun needsCpuPlan(sha: String?, upgradedEngine: Boolean): Boolean = upgradedEngine || sha !in profiles

    fun cpuPlanSupported(context: Int, batch: Int, microBatch: Int, threads: Int, weightBuffers: String): Boolean =
        context in 128..32768 && batch in 1..512 && microBatch in 1..128 && microBatch <= batch &&
            threads in 1..4 && weightBuffers == "native"

    fun evaluate(bytes: Long, sha: String?, context: Int, batch: Int, microBatch: Int,
                 total: Long, available: Long, threshold: Long, lowMemory: Boolean,
                 reclaimableAnonymous: Long = 0, threads: Int = 4, loadMode: String = "read", weightBuffers: String = "native",
                 streamedRepackSources: Boolean = false, transientRepack: Boolean = false,
                 transientRepackCompiled: Boolean = false, transientMinRows: Int = 4, prefixCacheMiB: Int = 0, exactStateLayout: Boolean = false, swaFull: Boolean = true,
                 upgradedEngine: Boolean = false, cpuPlan: JSONObject? = null): JSONObject {
        require(prefixCacheMiB == 0 || prefixCacheMiB in 64..256) { "Invalid prefix-cache budget" }
        require(weightBuffers in listOf("native", "cpu_repack", "cpu_repack_q6", "cpu_repack_q4")) { "Unknown weight buffer policy" }
        val repack = when (weightBuffers) { "cpu_repack" -> repackProfiles[sha]; "cpu_repack_q6" -> q6RepackProfiles[sha]; "cpu_repack_q4" -> q4RepackProfiles[sha]; else -> null }
        val profile = profiles[sha]
        val geometry = profile != null && context in 128..8192 && batch in 1..512 && microBatch in 1..128 && threads in 1..4
        // Full Ornith repack repeatedly triggered LMKD despite ample reported
        // MemAvailable. Keep the failed experiment out of the usable runtime.
        val excludedFullOrnith = weightBuffers == "cpu_repack" && sha == "70c112196e0b7023803c9762752e46d29e612a92c83f995bc3ba1ceb07e8fab6"
        // Q4-only packing also reached LOW_MEMORY during the first scored
        // request (2026-10-25). MemAvailable admission alone did not protect it.
        val excludedQ4Ornith = weightBuffers == "cpu_repack_q4" && sha == "70c112196e0b7023803c9762752e46d29e612a92c83f995bc3ba1ceb07e8fab6"
        val transientGeometry = profile != null && context in 128..32768 && batch in 1..512 && microBatch in 1..128 && threads in 1..4
        val transientSupported = !transientRepack || (!upgradedEngine && transientRepackCompiled && transientGeometry && microBatch >= transientMinRows && weightBuffers == "native" && loadMode == "mmap" && sha in profiles.keys)
        val prefixSupported = prefixCacheMiB == 0 || (!upgradedEngine && geometry && batch == 512 && microBatch == 128 &&
            weightBuffers == "native" && (sha == "70c112196e0b7023803c9762752e46d29e612a92c83f995bc3ba1ceb07e8fab6" ||
                (exactStateLayout && sha == "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87")))
        // The pinned compact route failed the fixed Gemma NLL/top8 gates.
        val compactSupported = swaFull
        val policySupported = compactSupported && !excludedFullOrnith && !excludedQ4Ornith && transientSupported && prefixSupported && (weightBuffers == "native" || (repack != null && geometry && loadMode == "mmap"))
        val qualified = swaFull && geometry && weightBuffers == "native" && !transientRepack && prefixCacheMiB == 0
        val headroom = maxOf(768 * MIB, threshold * 2)
        val contextBytes = profile?.let { it.fixedContext + it.contextPerToken * ((context + 255L) / 256 * 256) }
        val computeScale = maxOf(1L, (microBatch + 127L) / 128, (batch + 511L) / 512, (threads + 3L) / 4) * maxOf(1L, (context + 8191L) / 8192)
        // Headroom includes runtime/Java overhead as well as system reserve.
        // Extrapolation stays conservative/explicitly unqualified: a larger
        // context must never bypass a refused smaller measured profile.
        // Original mmap weights are clean/reclaimable, not a second anonymous
        // allocation. Reserve at least the largest pinned tensor's rounded-up
        // working window (1GiB Ornith / 2GiB Gemma), plus committed state/compute.
        // This is admission, not a peak-RSS limit or a guarantee of cache residency.
        val phased = repack != null && streamedRepackSources
        val modelBudget = if (repack != null) repack.packed + repack.nativeWindow + if (phased) 0 else repack.sourceWindow
            else if (profile != null && loadMode == "mmap") profile.mappedWindow else profile?.model ?: bytes
        // Only builds containing patch0004 can use phase accounting. Packing
        // drops clean source PTEs after each tensor, and context/compute are
        // allocated only after model loading completes. There is no full-file
        // prefault. Keep the full largest source tensor or native decode window,
        // whichever phase costs more; headroom and live pressure checks remain.
        val workspace = if (phased) repack!!.packed + maxOf(repack.sourceWindow, repack.nativeWindow + contextBytes!! + profile!!.compute * computeScale)
            else if (profile != null) modelBudget + contextBytes!! + profile.compute * computeScale else bytes
        // CPU backend scratch is separate from llama's graph compute buffers.
        // Include its entire capped request plus padding, never hide it in RSS.
        val cpuWorkspaceReserve = if (transientRepack) 81 * MIB else 0
        val prefixReserve = prefixCacheMiB * MIB
        val required = workspace + headroom + cpuWorkspaceReserve + prefixReserve
        val effective = available + reclaimableAnonymous.coerceAtLeast(0)
        val accepted = policySupported && !lowMemory && bytes < total - 512 * MIB && effective >= required
        val result = JSONObject().put("accepted", accepted).put("available_bytes", available)
            .put("reclaimable_anonymous_bytes", reclaimableAnonymous.coerceAtLeast(0))
            .put("effective_available_bytes", effective).put("required_bytes", required).put("headroom_bytes", headroom)
            .put("model_file_bytes", bytes).put("low_memory", lowMemory)
            .put("estimated_context_bytes", contextBytes ?: JSONObject.NULL)
            .put("weight_budget_bytes", modelBudget).put("load_mode", loadMode)
            .put("swa_full", swaFull).put("compact_swa_supported", compactSupported)
            .put("weight_buffers", weightBuffers).put("weight_policy_supported", policySupported)
            .put("transient_repack", transientRepack).put("cpu_workspace_reserve_bytes", cpuWorkspaceReserve)
            .put("prefix_cache_reserve_bytes", prefixReserve).put("prefix_cache_supported", prefixSupported)
            .put("transient_min_rows", if (transientRepack) transientMinRows else 0)
            .put("rejection_reason", if (excludedFullOrnith) "full_ornith_repack_failed_stability" else if (excludedQ4Ornith) "q4_ornith_repack_failed_stability" else if (!policySupported) "unsupported_experimental_profile" else if (!accepted) "memory_pressure_or_budget" else JSONObject.NULL)
            .put("packed_weight_bytes", repack?.packed ?: 0)
            .put("transient_source_window_bytes", repack?.sourceWindow ?: 0)
            .put("streamed_repack_sources", phased).put("phase_budget_before_headroom_bytes", workspace)
            .put("mapped_weight_window", profile != null && loadMode == "mmap" && weightBuffers == "native")
            .put("profile_qualified", qualified)
            .put("profile", if (prefixCacheMiB > 0) "experimental-full-sequence-prefix-v1" else if (transientRepack) "experimental-bounded-cpu-workspace-v1" else if (phased && weightBuffers == "cpu_repack_q4") "experimental-streamed-repack-q4-budget-v1" else if (phased && weightBuffers == "cpu_repack_q6") "experimental-streamed-repack-q6-budget-v1" else if (phased) "experimental-streamed-repack-phase-budget-v2" else if (weightBuffers != "native") "experimental-repack-additive-budget-v1" else if (qualified) "padded-cpu-context-budget-v2" else if (profile != null) "conservative-unqualified-extrapolation" else "file-size-lower-bound-only")
            .put("scope", "CPU/F16 KV. Native disables extras. Patched streaming builds budget committed weights plus max(source packing,native/context/compute); other repack builds add windows. Headroom unchanged. Repack is not a qualified RSS bound; concurrent system pressure can still cause LMKD.")
        if (!needsCpuPlan(sha, upgradedEngine)) return result
        // A matching GGUF does not carry allocation qualification across engine
        // revisions. Unknown models also need their actual state/graph geometry.
        val supported = policySupported && cpuPlanSupported(context, batch, microBatch, threads, weightBuffers)
        result.put("accepted", false).put("profile_qualified", false).put("cpu_plan_required", true)
            .put("weight_policy_supported", supported).put("profile", "unqualified-cpu-metadata-budget-v1")
            .put("rejection_reason", if (!policySupported) result.get("rejection_reason") else if (!supported) "unsupported_cpu_metadata_geometry" else "cpu_metadata_plan_required")
        if (!supported || cpuPlan == null) return result
        require(cpuPlan.optBoolean("no_alloc") && cpuPlan.optString("backend_profile") == "cpu" &&
            cpuPlan.optLong("gpu_model_bytes", -1) == 0L) { "Invalid native CPU memory plan" }
        fun size(value: Long): Long = value.also { require(it in 0..64L * 1024 * MIB) { "Invalid native CPU plan size" } }
        val plannedContext = size(cpuPlan.getLong("context_bytes"))
        val plannedCompute = size(cpuPlan.getLong("compute_bytes"))
        val largestTensor = size(cpuPlan.getLong("largest_cpu_tensor_bytes"))
        val buffers = cpuPlan.getJSONArray("buffers")
        var plannedModel = 0L
        for (i in 0 until buffers.length()) plannedModel = size(plannedModel + size(buffers.getJSONObject(i).getLong("model_bytes")))
        require(plannedModel > 0 && plannedContext > 0 && plannedCompute > 0 && largestTensor in 1..plannedModel) { "Incomplete native CPU memory plan" }
        val nativeModel = maxOf(bytes, plannedModel)
        // Mapped weights are clean/reclaimable. Keep the old known-model window
        // as a conservative floor, without claiming its old measurement applies
        // to this engine. Unknown models retain the full native/file footprint.
        val weightBudget = if (loadMode == "mmap" && profile != null) minOf(nativeModel, maxOf(largestTensor, profile.mappedWindow)) else nativeModel
        val metadataReserve = 64 * MIB
        val scratchReserve = maxOf(81 * MIB, largestTensor, size(cpuPlan.optLong("cpu_workspace_bytes", 0)))
        val plannedWorkspace = weightBudget + plannedContext + plannedCompute + metadataReserve
        val plannedRequired = plannedWorkspace + scratchReserve + headroom + prefixReserve
        val plannedAccepted = !lowMemory && bytes < total - 512 * MIB && effective >= plannedRequired
        return result.put("accepted", plannedAccepted).put("required_bytes", plannedRequired)
            .put("estimated_context_bytes", plannedContext).put("estimated_compute_bytes", plannedCompute)
            .put("weight_budget_bytes", weightBudget).put("planned_native_model_bytes", plannedModel)
            .put("metadata_reserve_bytes", metadataReserve).put("cpu_workspace_reserve_bytes", scratchReserve)
            .put("phase_budget_before_headroom_bytes", plannedWorkspace).put("mapped_weight_window", loadMode == "mmap" && profile != null)
            .put("cpu_plan", cpuPlan).put("rejection_reason", if (plannedAccepted) JSONObject.NULL else "memory_pressure_or_budget")
            .put("scope", "Current-engine no_alloc context/compute and native weight geometry; known mapped weight window or full weights for unknown/read models;64MiB metadata; CPU scratch upper reserve at least max(81MiB,largest tensor); unchanged system headroom. Unqualified allocation estimate, not measured RSS; concurrent pressure can still cause LMKD.")
    }
}
