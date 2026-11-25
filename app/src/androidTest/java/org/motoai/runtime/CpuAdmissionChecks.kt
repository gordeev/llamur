package org.motoai.runtime

import org.json.JSONArray
import org.json.JSONObject

internal object CpuAdmissionChecks {
    private const val MIB = 1024L * 1024
    private const val FILE_BYTES = 1099627808L
    private const val LFM_SHA = "3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4"
    private val historicalShas = listOf(
        "70c112196e0b7023803c9762752e46d29e612a92c83f995bc3ba1ceb07e8fab6",
        "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87")

    private fun evaluate(context: Int, plan: JSONObject?, available: Long = 16L * 1024 * MIB,
                         sha: String = LFM_SHA, upgraded: Boolean = true, bytes: Long = FILE_BYTES,
                         transient: Boolean = false, prefix: Int = 0) =
        LoadAdmission.evaluate(bytes, sha, context, 512, 128, 24L * 1024 * MIB, available,
            226492416, false, loadMode = "mmap", upgradedEngine = upgraded, cpuPlan = plan,
            transientRepack = transient, transientRepackCompiled = true, prefixCacheMiB = prefix, exactStateLayout = true)

    // Captured LFM3 no_alloc4K/32K plan values;262K is a refusal-only geometry fixture.
    // Fresh native planner results are checked separately by the cpu plan scope.
    private fun fixture(context: Int) = JSONObject().put("no_alloc", true).put("backend_profile", "cpu")
        .put("gpu_model_bytes", 0).put("context_bytes", 638976L + context * 22528L)
        .put("compute_bytes", if (context == 4096) 72883232L else 87563296L).put("largest_cpu_tensor_bytes", 215040000L)
        .put("buffers", JSONArray().put(JSONObject().put("model_bytes", 1091380480L)))

    fun actualPlan(plan: JSONObject, bytes: Long, sha: String?, context: Int): JSONObject {
        val admitted = evaluate(context, plan, sha = sha ?: "unknown", bytes = bytes)
        check(admitted.getBoolean("accepted") && !admitted.getBoolean("profile_qualified"))
        check(admitted.getLong("estimated_context_bytes") == plan.getLong("context_bytes"))
        check(admitted.getLong("estimated_compute_bytes") == plan.getLong("compute_bytes"))
        check(!evaluate(context, plan, available = admitted.getLong("required_bytes") - 1,
            sha = sha ?: "unknown", bytes = bytes).getBoolean("accepted"))
        admitted.remove("cpu_plan")
        return admitted
    }

    fun run(): JSONObject {
        check(LoadAdmission.needsCpuPlan(LFM_SHA, false))
        check(historicalShas.all { !LoadAdmission.needsCpuPlan(it, false) && LoadAdmission.needsCpuPlan(it, true) })
        check(!evaluate(4096, null).getBoolean("accepted"))
        val small = actualPlan(fixture(4096), FILE_BYTES, LFM_SHA, 4096)
        val large = actualPlan(fixture(32768), FILE_BYTES, LFM_SHA, 32768)
        check(large.getLong("required_bytes") - small.getLong("required_bytes") == (32768L - 4096) * 22528 + (87563296L - 72883232L))
        check(small.getLong("weight_budget_bytes") >= FILE_BYTES)
        check(small.getLong("cpu_workspace_reserve_bytes") >= 215040000L)
        check(small.getLong("metadata_reserve_bytes") == 64 * MIB)
        val unsupported = evaluate(262144, fixture(262144))
        check(!unsupported.getBoolean("accepted") && !unsupported.getBoolean("weight_policy_supported"))
        check(unsupported.getString("rejection_reason") == "unsupported_cpu_metadata_geometry")
        check(!LoadAdmission.cpuPlanSupported(4096, 512, 128, 5, "native"))
        for (sha in historicalShas) {
            check(!evaluate(4096, null, sha = sha).getBoolean("accepted"))
            val current = evaluate(4096, fixture(4096), sha = sha)
            check(!current.getBoolean("profile_qualified"))
            check(current.getLong("estimated_context_bytes") == fixture(4096).getLong("context_bytes"))
            val cached = evaluate(4096, fixture(4096), sha = sha, prefix = 128)
            check(!cached.getBoolean("accepted") && !cached.getBoolean("prefix_cache_supported"))
            check(!evaluate(4096, fixture(4096), sha = sha, transient = true).getBoolean("accepted"))
        }
        return JSONObject().put("passed", true).put("small_required_bytes", small.getLong("required_bytes"))
            .put("large_required_bytes", large.getLong("required_bytes"))
            .put("scope", "CPU plan-contract fixtures: unknown and upgraded known SHA require current metadata;4K/32K growth and exact budget refusal;262K and unsupported workers refused. Real-model/service capacity remains separate.")
    }
}
