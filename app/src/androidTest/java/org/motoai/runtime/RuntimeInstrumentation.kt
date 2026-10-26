package org.motoai.runtime

import android.app.Instrumentation
import android.os.Bundle
import android.os.Debug
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Public tiny fixture only. Framework instrumentation; no test runtime dependency in the APK. */
class RuntimeInstrumentation : Instrumentation() {
    private var gpuNumeric: Bundle? = null
    private var sustained: Bundle? = null
    private var prefix: Bundle? = null
    private var gpuPlan: Bundle? = null
    private var safMode: String? = null
    private var referenceOnly = false
    private var downloadsOnly = false
    private var apiSession: Bundle? = null
    private var uiPerformance:Bundle? = null
    private var productBackground = false
    private var productOnly = false
    private var serviceOnly = false
    private var backgroundOnly = false
    private var storageOnly = false
    private var transientServiceOnly = false
    private var migrationFile: String? = null
    private var historyOnly=false
    private var providerBackground=false
    private var providerTheme:Int?=null
    private var providersOnly=false
    private var readmeTheme:Int?=null
    private var localization=false
    private var compaction=false
    private var providerUpdates=false
    private var liveCatalogue=false
    private var infoModel: String? = null
    private var infoId: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments);providersOnly=arguments?.getString("scope")=="providers"; serviceOnly = arguments?.getString("scope") == "service"
        if(arguments?.getString("scope")=="readme-screenshots")readmeTheme=arguments.getString("theme","2").toInt().also {require(it in 1..2)}
        localization=arguments?.getString("scope")=="localization"
        compaction=arguments?.getString("scope")=="compaction"
        providerUpdates=arguments?.getString("scope")=="provider-updates";liveCatalogue=arguments?.getString("liveCatalogue")=="true"
        if(arguments?.getString("scope")=="ui-performance")uiPerformance=Bundle(arguments)
        safMode=arguments?.getString("scope")?.takeIf {it in listOf("saf-ui","saf-resume","saf-copy-delete")}
        referenceOnly = arguments?.getString("scope") == "reference"
        downloadsOnly = arguments?.getString("scope") == "downloads"
        if (arguments?.getString("scope") == "api-session") apiSession = Bundle(arguments)
        productBackground = arguments?.getString("scope") == "product-background"
        productOnly = arguments?.getString("scope") == "product"
        backgroundOnly = arguments?.getString("scope") == "background"
        storageOnly = arguments?.getString("scope") == "storage"
        transientServiceOnly = arguments?.getString("scope") == "transient-service"
        migrationFile = arguments?.getString("migrationFile")
        if (arguments?.getString("scope") == "model-info") {
            infoModel = arguments.getString("model")
            infoId = arguments.getString("runId")
        }
        if (arguments?.getString("scope") == "gpu-numeric") gpuNumeric = Bundle(arguments)
        if (arguments?.getString("scope") == "sustained") sustained = Bundle(arguments)
        if (arguments?.getString("scope") == "prefix") prefix = Bundle(arguments)
        if (arguments?.getString("scope") == "gpu-plan") gpuPlan = Bundle(arguments)
        historyOnly=arguments?.getString("scope")=="history"
        providerBackground=arguments?.getString("providerBackground")=="true";providerTheme=arguments?.getString("providerTheme")?.toIntOrNull()?.also {require(it in 0..2)}
        start()
    }
    override fun onStart() {
        val report = JSONObject().put("passed", false)
        if(readmeTheme!=null){try {report.put("screenshots",ReadmeScreenshots(this).run(readmeTheme!!)).put("passed",true)}catch(f:Throwable){report.put("error",f.toString()).put("stack",f.stackTraceToString())};File(targetContext.filesDir,"runtime-readme.json").writeText(report.toString(2));finish(if(report.optBoolean("passed"))-1 else 0,Bundle());return}
        if(localization){val checks=LocalizationChecks(this);try {report.put("localization",checks.run()).put("passed",true)}catch(f:Throwable){report.put("error",f.toString()).put("stack",f.stackTraceToString())};report.put("progress",checks.progress);File(targetContext.filesDir,"runtime-localization.json").writeText(report.toString(2));finish(if(report.optBoolean("passed"))-1 else 0,Bundle());return}
        if(compaction){val checks=CompactionChecks(this);try {report.put("compaction",checks.run()).put("passed",true)}catch(f:Throwable){report.put("error",f.toString()).put("stack",f.stackTraceToString())};report.put("progress",checks.progress);File(targetContext.filesDir,"runtime-compaction.json").writeText(report.toString(2));finish(if(report.optBoolean("passed"))-1 else 0,Bundle());return}
        if(providerUpdates){try {report.put("updates",ProviderUpdateChecks(this).run(liveCatalogue)).put("passed",true)}catch(f:Throwable){report.put("error",f.toString()).put("stack",f.stackTraceToString())};File(targetContext.filesDir,"runtime-provider-updates.json").writeText(report.toString(2));finish(if(report.optBoolean("passed"))-1 else 0,Bundle());return}
        if(historyOnly){try{report.put("history",HistoryChecks(this).run()).put("passed",true)}catch(f:Throwable){report.put("error",f.toString()).put("stack",f.stackTraceToString())};File(targetContext.filesDir,"runtime-history-tests.json").writeText(report.toString(2));finish(if(report.getBoolean("passed"))-1 else 0,Bundle());return}
        if(providersOnly) {
            val checks=ProviderChecks(this)
            try {report.put("providers",checks.run(providerBackground,providerTheme)).put("passed",true)}catch(f:Throwable){report.put("error",f.toString()).put("stack",f.stackTraceToString()).put("progress",checks.progress)}
            File(targetContext.filesDir,"runtime-providers-tests.json").writeText(report.toString(2));finish(if(report.getBoolean("passed"))-1 else 0,Bundle());return
        }
        gpuPlan?.let { args ->
            val id = args.getString("runId") ?: error("Missing GPU plan ID")
            require(id.matches(Regex("plan-[a-zA-Z0-9_-]{1,64}")))
            try {
                val backend = args.getString("backend") ?: error("Missing backend")
                val model = args.getString("model") ?: error("Missing model")
                require(backend in listOf("cpu", "opencl", "vulkan"))
                report.put("initialization", NativeRuntime.initialize(targetContext, backend))
                val plans = org.json.JSONArray()
                val sizes = if (backend == "cpu") listOf(4096, 8192, 16384, 32768) else listOf(4096)
                val layerCounts = if (backend == "cpu") listOf(0) else listOf(4, 8, 16, 24, 128)
                for (size in sizes) for (layers in layerCounts) {
                    val config = JSONObject().put("backend", backend).put("gpu_layers", layers)
                        .put("context", size).put("threads", 4).put("batch", 512).put("micro_batch", 128)
                        .put("load_mode", "mmap").put("mmap_prefault", false).put("swa_full", args.getString("swaFull") != "false")
                    val result = ModelStore.open(targetContext, model).use { NativeRuntime.plan(it.path, config, NativeRuntime.ticket()) }
                    plans.put(JSONObject().put("config", config).put("plan", result))
                }
                report.put("plans", plans).put("passed", true)
                    .put("scope", "Metadata/no_alloc buffer selection only; no model weight data loaded or inference")
            } catch (failure: Throwable) { report.put("error", failure.toString()).put("stack", failure.stackTraceToString()) }
            File(targetContext.filesDir, "$id-report.json").writeText(report.toString(2))
            finish(if (report.getBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
            return
        }
        prefix?.let { args ->
            val id = args.getString("runId") ?: error("Missing prefix test ID")
            require(id.matches(Regex("prefix-[a-zA-Z0-9_-]{1,64}")))
            try {
                val name = args.getString("requestFile") ?: error("Missing public prefix fixture")
                require(RuntimeService.validName(name))
                val spec = JSONObject(File(targetContext.filesDir, "bench/$name").readText())
                report.put("prefix", PrefixChecks(this).run(spec, id, args.getString("model") ?: "Ornith-1.5-9B-Q4_K_M.gguf", args.getString("transient") == "true", args.getString("swaFull") != "false")).put("passed", true)
            } catch (failure: Throwable) { report.put("error", failure.toString()).put("stack", failure.stackTraceToString()) }
            File(targetContext.filesDir, "$id-report.json").writeText(report.toString(2))
            finish(if (report.getBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
            return
        }
        safMode?.let {mode->
            try {report.put("saf",if(mode=="saf-copy-delete")SafChecks(this).copyDelete()else SafChecks(this).run(mode=="saf-resume")).put("passed",true)}
            catch(failure:Throwable){report.put("error",failure.toString()).put("stack",failure.stackTraceToString())}
            File(targetContext.filesDir,"runtime-$mode-tests.json").writeText(report.toString(2))
            finish(if(report.getBoolean("passed")) -1 else 0,Bundle().apply {putString("report",report.toString())});return
        }
        if(referenceOnly) {
            try { report.put("reference",ReferenceChecks(this).run()).put("passed",true) }
            catch(failure:Throwable){report.put("error",failure.toString()).put("stack",failure.stackTraceToString())}
            File(targetContext.filesDir,"runtime-reference-tests.json").writeText(report.toString(2))
            finish(if(report.getBoolean("passed")) -1 else 0,Bundle().apply {putString("report",report.toString())});return
        }
        if(downloadsOnly) {
            try { report.put("downloads",DownloadChecks(this).run()).put("passed",true) }
            catch(failure:Throwable){report.put("error",failure.toString()).put("stack",failure.stackTraceToString())}
            File(targetContext.filesDir,"runtime-downloads-tests.json").writeText(report.toString(2))
            finish(if(report.getBoolean("passed")) -1 else 0,Bundle().apply {putString("report",report.toString())});return
        }
        apiSession?.let { args ->
            try { report.put("session", ApiSession(this).run(args)).put("passed", true) }
            catch (failure: Throwable) { report.put("error", failure.toString()).put("stack", failure.stackTraceToString()) }
            val id=args.getString("runId")!!.also {require(it.matches(Regex("api-[a-f0-9]{32}")))}
            File(targetContext.filesDir,"$id-report.json").writeText(report.toString(2))
            finish(if(report.getBoolean("passed")) -1 else 0,Bundle().apply {putString("report",report.toString())});return
        }
        uiPerformance?.let {args->
            try {report.put("ui_performance",UiPerformanceChecks(this).run(args)).put("passed",true)}catch(failure:Throwable){report.put("error",failure.toString()).put("stack",failure.stackTraceToString())}
            File(targetContext.filesDir,"runtime-ui-performance-tests.json").writeText(report.toString(2))
            finish(if(report.optBoolean("passed"))-1 else 0,Bundle().apply {putString("report",report.toString())});return
        }
        if (productBackground) {
            try { report.put("background", BackgroundChecks(this).runProduct()).put("passed", true) }
            catch (failure: Throwable) { report.put("error", failure.toString()).put("stack", failure.stackTraceToString()) }
            File(targetContext.filesDir, "runtime-product-background-tests.json").writeText(report.toString(2))
            finish(if (report.getBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
            return
        }
        if (productOnly) {
            try { report.put("product", ProductChecks(this).run()).put("passed", true) }
            catch (failure: Throwable) { report.put("error", failure.toString()).put("stack", failure.stackTraceToString()) }
            File(targetContext.filesDir, "runtime-product-tests.json").writeText(report.toString(2))
            finish(if (report.getBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
            return
        }
        if (backgroundOnly) {
            try { report.put("background", BackgroundChecks(this).run()).put("passed", true) }
            catch (failure: Throwable) { report.put("error", failure.toString()).put("stack", failure.stackTraceToString()) }
            File(targetContext.filesDir, "runtime-background-tests.json").writeText(report.toString(2))
            finish(if (report.getBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
            return
        }
        gpuNumeric?.let { args ->
            val id = args.getString("runId") ?: error("Missing GPU test ID")
            require(id.matches(Regex("gpu-[a-zA-Z0-9_-]{1,64}")))
            try {
                val backend = args.getString("backend") ?: error("Missing GPU selection")
                val name = args.getString("gatesFile") ?: error("Missing fixed numeric gates")
                require(RuntimeService.validName(name))
                val gates = JSONObject(File(targetContext.filesDir, "bench/$name").readText())
                report.put("initialization", NativeRuntime.initialize(targetContext, backend))
                val numeric = BackendNumeric.run(backend, gates)
                report.put("numeric", numeric).put("passed", numeric.getBoolean("passed"))
            } catch (failure: Throwable) { report.put("error", failure.toString()) }
            File(targetContext.filesDir, "$id-report.json").writeText(report.toString(2))
            finish(if (report.getBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
            return
        }
        sustained?.let { args ->
            val id = args.getString("runId") ?: error("Missing sustained ID")
            require(id.matches(Regex("sustained-[a-zA-Z0-9_-]{1,64}")))
            try {
                val name = args.getString("requestFile") ?: error("Missing sustained fixture")
                require(RuntimeService.validName(name))
                val spec = JSONObject(File(targetContext.filesDir, "bench/$name").readText())
                report.put("sustained", SustainedChecks(this).run(spec, args.getString("seconds")!!.toInt(), id))
                    .put("passed", true)
            } catch (failure: Throwable) { report.put("error", failure.toString()) }
            File(targetContext.filesDir, "$id-report.json").writeText(report.toString(2))
            finish(if (report.getBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
            return
        }
        if (infoModel != null) {
            val id = infoId ?: error("Missing model-info run ID")
            require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
            try {
                ModelStore.open(targetContext, infoModel!!).use { opened ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(android.os.ParcelFileDescriptor.dup(opened.descriptor.fileDescriptor)).use { input ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); total += n }
                    }
                    check(total == opened.bytes)
                    report.put("model", infoModel).put("bytes", total).put("run_id", id).put("location", opened.location)
                        .put("sha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }).put("passed", true)
                }
            } catch (failure: Throwable) { report.put("error", failure.toString()) }
            File(targetContext.filesDir, "model-info-$id.json").writeText(report.toString(2))
            finish(if (report.getBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
            return
        }
        if (storageOnly) {
            try {
                report.put("storage", StorageChecks.run(targetContext))
                migrationFile?.let { name ->
                    require(RuntimeService.validName(name))
                    val migrations = org.json.JSONArray(File(targetContext.filesDir, "bench/$name").readText())
                    val completed = org.json.JSONArray()
                    for (index in 0 until migrations.length()) {
                        val item = migrations.getJSONObject(index)
                        completed.put(ModelStore.publishToDownloads(targetContext, item.getString("filename"), item.getString("sha256")))
                    }
                    report.put("migrated", completed)
                }
                report.put("passed", true)
            } catch (failure: Throwable) { report.put("error", failure.toString()) }
            File(targetContext.filesDir, "runtime-storage-tests.json").writeText(report.toString(2))
            finish(if (report.getBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
            return
        }
        if (transientServiceOnly) {
            try { report.put("service", ServiceChecks(this).runTransient()).put("passed", true) }
            catch (failure: Throwable) { report.put("error", failure.toString()) }
            File(targetContext.filesDir, "runtime-transient-service-tests.json").writeText(report.toString(2))
            finish(if (report.getBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
            return
        }
        if (serviceOnly) {
            try { report.put("service", ServiceChecks(this).run()).put("passed", true) }
            catch (failure: Throwable) { report.put("error", failure.toString()) }
            File(targetContext.filesDir, "runtime-service-tests.json").writeText(report.toString(2))
            finish(if (report.getBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
            return
        }
        try {
            report.put("ephemeral", EphemeralChecks.run(this))
            val ornithSha = "70c112196e0b7023803c9762752e46d29e612a92c83f995bc3ba1ceb07e8fab6"
            fun admission(available: Long, low: Boolean = false, size: Int = 8192) = LoadAdmission.evaluate(
                5780090816, ornithSha, size, 512, 128, 11843080192, available, 226492416, low)
            check(!admission(6500000000).getBoolean("accepted"))
            check(admission(7500000000).getBoolean("accepted"))
            check(admission(7500000000, size = 128).getLong("estimated_context_bytes") == 61079552L)
            check(admission(7500000000, size = 4096).getLong("estimated_context_bytes") == 186908672L)
            check(!admission(10000000000, true).getBoolean("accepted"))
            val larger = admission(10000000000, size = 16384)
            check(!larger.getBoolean("profile_qualified"))
            check(larger.getString("profile") == "conservative-unqualified-extrapolation")
            check(larger.getLong("required_bytes") >= admission(10000000000).getLong("required_bytes"))
            val mapped = LoadAdmission.evaluate(5780090816, ornithSha, 8192, 512, 128, 11843080192, 6500000000, 226492416, false, loadMode = "mmap")
            check(mapped.getBoolean("accepted") && mapped.getBoolean("mapped_weight_window"))
            check(mapped.getLong("weight_budget_bytes") == 1024L * 1024 * 1024)
            val gpuConfig = JSONObject().put("backend", "opencl").put("gpu_layers", 8)
                .put("load_mode", "mmap").put("mmap_prefault", false)
            val gpuPlanFixture = JSONObject().put("no_alloc", true).put("gpu_model_bytes", 100L * 1024 * 1024)
                .put("context_bytes", 2L * 1024 * 1024).put("compute_bytes", 3L * 1024 * 1024)
                .put("largest_cpu_tensor_bytes", 1L * 1024 * 1024).put("largest_gpu_source_bytes", 800L * 1024 * 1024)
            val gpuAdmission = GpuAdmission.evaluate(mapped, gpuPlanFixture, ornithSha, gpuConfig)
            check(gpuAdmission.getBoolean("accepted") && !gpuAdmission.getBoolean("profile_qualified"))
            check(gpuAdmission.getLong("required_bytes") == 2980L * 1024 * 1024)
            val oversizedGpu = JSONObject(gpuPlanFixture.toString()).put("gpu_model_bytes", 8L * 1024 * 1024 * 1024)
            check(!GpuAdmission.evaluate(mapped, oversizedGpu, ornithSha, gpuConfig).getBoolean("accepted"))
            val prefix = LoadAdmission.evaluate(5780090816, ornithSha, 8192, 512, 128,
                11843080192, 6500000000, 226492416, false, loadMode = "mmap", prefixCacheMiB = 128)
            check(prefix.getBoolean("accepted") && !prefix.getBoolean("profile_qualified"))
            check(prefix.getLong("required_bytes") == mapped.getLong("required_bytes") + 128L * 1024 * 1024)
            check(prefix.getLong("prefix_cache_reserve_bytes") == 128L * 1024 * 1024)
            val gemmaPrefix = LoadAdmission.evaluate(4977171584, "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87",
                8192, 512, 128, 11843080192, 10000000000, 226492416, false, loadMode = "mmap", prefixCacheMiB = 128)
            check(!gemmaPrefix.getBoolean("accepted") && !gemmaPrefix.getBoolean("prefix_cache_supported"))
            val exactGemmaPrefix = LoadAdmission.evaluate(4977171584, "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87",
                8192, 512, 128, 11843080192, 10000000000, 226492416, false, loadMode = "mmap", prefixCacheMiB = 128, exactStateLayout = true)
            check(exactGemmaPrefix.getBoolean("accepted") && !exactGemmaPrefix.getBoolean("profile_qualified"))
            val gemmaTransient = LoadAdmission.evaluate(4977171584, "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87",
                8192, 512, 128, 11843080192, 10000000000, 226492416, false, loadMode = "mmap", transientRepack = true, transientRepackCompiled = true)
            check(gemmaTransient.getBoolean("accepted") && !gemmaTransient.getBoolean("profile_qualified"))
            val rejectedCompact = LoadAdmission.evaluate(4977171584, "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87",
                8192, 512, 128, 11843080192, 10000000000, 226492416, false, loadMode = "mmap", swaFull = false)
            check(!rejectedCompact.getBoolean("accepted") && !rejectedCompact.getBoolean("compact_swa_supported"))
            val joint = LoadAdmission.evaluate(5780090816, ornithSha, 8192, 512, 128,
                11843080192, 6500000000, 226492416, false, loadMode = "mmap", prefixCacheMiB = 128,
                transientRepack = true, transientRepackCompiled = true)
            check(joint.getBoolean("accepted") && !joint.getBoolean("profile_qualified"))
            check(joint.getLong("required_bytes") == mapped.getLong("required_bytes") + 209L * 1024 * 1024)
            for (contextSize in listOf(16384,32768,65536)) {
                val extended = LoadAdmission.evaluate(5780090816, ornithSha, contextSize, 512, 128,
                    11843080192, 10000000000, 226492416, false, loadMode = "mmap", transientRepack = true, transientRepackCompiled = true)
                check(extended.getBoolean("accepted") == (contextSize <= 32768))
                check(!extended.getBoolean("profile_qualified"))
            }
            fun transientBudget(compiled: Boolean = true, available: Long = 6500000000, ub: Int = 128) =
                LoadAdmission.evaluate(5780090816, ornithSha, 8192, 512, ub, 11843080192, available, 226492416, false,
                    loadMode = "mmap", transientRepack = true, transientRepackCompiled = compiled, transientMinRows = BuildConfig.TRANSIENT_MIN_ROWS)
            val transient = transientBudget()
            check(transient.getBoolean("accepted") && !transient.getBoolean("profile_qualified"))
            check(transient.getLong("required_bytes") == mapped.getLong("required_bytes") + 81L * 1024 * 1024)
            check(transient.getLong("headroom_bytes") == mapped.getLong("headroom_bytes"))
            check(!transientBudget(compiled = false).getBoolean("accepted"))
            check(!transientBudget(available = mapped.getLong("required_bytes")).getBoolean("accepted"))
            check(!transientBudget(ub = 512).getBoolean("accepted"))
            check(!transientBudget(ub = BuildConfig.TRANSIENT_MIN_ROWS - 1).getBoolean("accepted"))
            check(transient.getInt("transient_min_rows") == BuildConfig.TRANSIENT_MIN_ROWS)
            fun repackBudget(available: Long = 9000000000, size: Int = 8192, mode: String = "mmap", sha: String = ornithSha) =
                LoadAdmission.evaluate(5780090816, sha, size, 512, 128, 11843080192, available, 226492416, false,
                    loadMode = mode, weightBuffers = "cpu_repack")
            val packed = repackBudget()
            check(!packed.getBoolean("accepted") && !packed.getBoolean("profile_qualified"))
            check(packed.getString("rejection_reason") == "full_ornith_repack_failed_stability")
            check(!packed.getBoolean("mapped_weight_window"))
            check(packed.getLong("weight_budget_bytes") == 5041766400L + 572129280 + 834355200)
            check(packed.getLong("required_bytes") > mapped.getLong("required_bytes"))
            check(!repackBudget(6500000000).getBoolean("accepted"))
            check(!repackBudget(mode = "read").getBoolean("accepted"))
            check(!repackBudget(size = 16384).getBoolean("accepted"))
            check(!repackBudget(sha = "unverified").getBoolean("accepted"))
            val streamed = LoadAdmission.evaluate(5780090816, ornithSha, 8192, 512, 128, 11843080192, 7000000000, 226492416, false,
                loadMode = "mmap", weightBuffers = "cpu_repack", streamedRepackSources = true)
            check(!streamed.getBoolean("accepted") && !streamed.getBoolean("profile_qualified"))
            check(streamed.getLong("phase_budget_before_headroom_bytes") == 6076848672L)
            check(streamed.getLong("headroom_bytes") == packed.getLong("headroom_bytes"))
            val insufficient = LoadAdmission.evaluate(5780090816, ornithSha, 8192, 512, 128, 11843080192, 6500000000, 226492416, false,
                loadMode = "mmap", weightBuffers = "cpu_repack", streamedRepackSources = true)
            check(!insufficient.getBoolean("accepted"))
            val selective = LoadAdmission.evaluate(5780090816, ornithSha, 8192, 512, 128, 11843080192, 5000000000, 226492416, false,
                loadMode = "mmap", weightBuffers = "cpu_repack_q6", streamedRepackSources = true)
            check(selective.getBoolean("accepted") && !selective.getBoolean("profile_qualified"))
            check(selective.getLong("packed_weight_bytes") == 1839022080L)
            check(selective.getLong("required_bytes") == 4181023264L)
            check(selective.getLong("headroom_bytes") == packed.getLong("headroom_bytes"))
            val selectiveRefused = LoadAdmission.evaluate(5780090816, ornithSha, 8192, 512, 128, 11843080192, 4000000000, 226492416, false,
                loadMode = "mmap", weightBuffers = "cpu_repack_q6", streamedRepackSources = true)
            check(!selectiveRefused.getBoolean("accepted"))
            val q4 = LoadAdmission.evaluate(5780090816, ornithSha, 8192, 512, 128, 11843080192, 7000000000, 226492416, false,
                loadMode = "mmap", weightBuffers = "cpu_repack_q4", streamedRepackSources = true)
            check(!q4.getBoolean("accepted") && !q4.getBoolean("profile_qualified"))
            check(!q4.getBoolean("weight_policy_supported"))
            check(q4.getString("rejection_reason") == "q4_ornith_repack_failed_stability")
            check(q4.getLong("packed_weight_bytes") == 3202744320L)
            check(q4.getLong("transient_source_window_bytes") == 28311552L)
            check(q4.getLong("required_bytes") == 6618487328L)
            check(q4.getLong("headroom_bytes") == packed.getLong("headroom_bytes"))
            val q4Refused = LoadAdmission.evaluate(5780090816, ornithSha, 8192, 512, 128, 11843080192, 6500000000, 226492416, false,
                loadMode = "mmap", weightBuffers = "cpu_repack_q4", streamedRepackSources = true)
            check(!q4Refused.getBoolean("accepted"))
            report.put("repack_admission", "Experimental known-model mmap only; committed/source/native windows additive; refuses insufficient memory and unsupported geometry")
            report.put("admission_policy", "Measured <=8K profile screens low available memory; system lowMemory always refuses; larger/unknown profiles explicitly unqualified")
            // A long answer must not turn debug metrics into an oversized Binder
            // transaction. Keep the full report intact, send only UI counters.
            val many = org.json.JSONArray()
            repeat(140000) { many.put(123456789) }
            val detailed = JSONObject().put("generated_tokens", 140000).put("finish_reason", "length")
                .put("inter_token_us", many).put("trace", JSONObject().put("generated_ids", many))
            val source = JSONObject().put("state", "ready").put("request", JSONObject().put("raw_prompt", "private fixture"))
                .put("result", detailed)
            check(source.toString().toByteArray().size > 1024 * 1024)
            val summary = RuntimeService.clientSnapshot(source)
            check(!summary.has("request") && !summary.getJSONObject("result").has("trace"))
            check(summary.getJSONObject("result").getInt("generated_tokens") == 140000)
            check(detailed.getJSONArray("inter_token_us").length() == 140000) { "Diagnostic report was truncated" }
            val parcel = android.os.Parcel.obtain()
            try {
                Bundle().apply { putString("snapshot", summary.toString()) }.writeToParcel(parcel, 0)
                check(parcel.dataSize() < 65536) { "Oversized Binder status" }
                report.put("long_answer_ipc", "Full diagnostic >1MiB retained; UI status parcel ${parcel.dataSize()} bytes")
            } finally { parcel.recycle() }
            val config = JSONObject().put("context", 128).put("threads", 2).put("batch", 128)
                .put("micro_batch", 64).put("load_mode", "read")
            val request = JSONObject().put("chat", false).put("raw_prompt", "Once upon a time")
                .put("max_tokens", 16).put("temperature", 0.0).put("seed", 42)
            report.put("initialization", NativeRuntime.initialize(targetContext))
            val model = File(targetContext.filesDir, "models/stories260K.gguf").path
            var reference: ByteArray? = null
            val samples = org.json.JSONArray()
            repeat(30) { cycle ->
                NativeRuntime.load(model, config, NativeRuntime.ticket())
                val output = ByteArrayOutputStream()
                val result = NativeRuntime.generate(request, NativeRuntime.ticket(), object : NativeRuntime.Sink {
                    override fun onBytes(bytes: ByteArray) { output.write(bytes) }
                })
                report.put("arm64_fpcr", result.optLong("arm64_fpcr", -1))
                check(result.getInt("generated_tokens") == 16) { "Wrong output count" }
                if (reference == null) reference = output.toByteArray()
                else check(reference!!.contentEquals(output.toByteArray())) { "Greedy output changed on cycle $cycle" }
                NativeRuntime.unload(); NativeRuntime.unload()
                if (cycle % 5 == 0 || cycle == 29) {
                    val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                    samples.put(JSONObject().put("cycle", cycle).put("pss_kib", memory.totalPss)
                        .put("native_heap_bytes", Debug.getNativeHeapAllocatedSize()))
                }
            }
            fun callerMask() = File("/proc/thread-self/status").readLines().first { it.startsWith("Cpus_allowed_list:") }.substringAfter(':').trim()
            val originalMask = callerMask()
            val allowedCpus = originalMask.split(',').flatMap { part ->
                val range = part.split('-').map { it.toInt() }
                (range.first()..range.last()).toList()
            }
            check(allowedCpus.size >= 3)
            val selected = allowedCpus.takeLast(3)
            val scheduled = JSONObject(config.toString()).put("threads", 2).put("threads_batch", 3)
                .put("cpu_affinity", org.json.JSONArray(selected))
            val schedulingChecks = org.json.JSONArray()
            repeat(3) {
                val loadedScheduling = NativeRuntime.load(model, scheduled, NativeRuntime.ticket())
                check(loadedScheduling.getBoolean("explicit_threadpools"))
                check(callerMask() == originalMask) { "Load leaked caller affinity" }
                repeat(2) {
                    val output = ByteArrayOutputStream()
                    val result = NativeRuntime.generate(request, NativeRuntime.ticket(), object : NativeRuntime.Sink {
                        override fun onBytes(bytes: ByteArray) { output.write(bytes) }
                    })
                    check(reference!!.contentEquals(output.toByteArray())) { "CPU placement changed greedy output" }
                    check(callerMask() == originalMask) { "Generation leaked caller affinity" }
                    val threads = result.getJSONObject("cpu_scheduling").getJSONArray("threads")
                    check(threads.length() == 4) { "Missing caller/worker placement evidence" }
                    for (index in 0 until threads.length()) check(threads.getJSONObject(index).getJSONArray("cpus").toString() == org.json.JSONArray(selected).toString())
                    schedulingChecks.put(result.getJSONObject("cpu_scheduling"))
                }
                var unavailableRejected = false
                try { NativeRuntime.load(model, JSONObject(scheduled.toString()).put("cpu_affinity", org.json.JSONArray(listOf(127))), NativeRuntime.ticket()) }
                catch (failure: NativeRuntime.Failure) { unavailableRejected = true }
                check(unavailableRejected && callerMask() == originalMask)
                var oversubscriptionRejected = false
                try {
                    NativeRuntime.load(model, JSONObject(scheduled.toString()).put("threads", allowedCpus.size + 1)
                        .put("cpu_affinity", org.json.JSONArray()), NativeRuntime.ticket())
                } catch (failure: NativeRuntime.Failure) { check(failure.message!!.contains("oversubscription")); oversubscriptionRejected = true }
                check(oversubscriptionRejected && callerMask() == originalMask)
                report.put("oversubscription_rejected_before_model_replacement", true)
                var overflowRejected = false
                try {
                    NativeRuntime.generate(JSONObject(request.toString()).put("raw_prompt", "hello ".repeat(200)), NativeRuntime.ticket(),
                        object : NativeRuntime.Sink { override fun onBytes(bytes: ByteArray) {} })
                } catch (failure: NativeRuntime.Failure) { check(failure.kind == "context_overflow"); overflowRejected = true }
                check(overflowRejected && callerMask() == originalMask) { "Exception leaked affinity" }
                var cancelledScheduling = false
                try {
                    NativeRuntime.generate(request, NativeRuntime.ticket(), object : NativeRuntime.Sink {
                        override fun onBytes(bytes: ByteArray) { NativeRuntime.cancel() }
                    })
                } catch (failure: NativeRuntime.Failure) { check(failure.kind == "cancelled"); cancelledScheduling = true }
                check(cancelledScheduling && callerMask() == originalMask) { "Cancellation leaked affinity" }
                val recoveredOutput = ByteArrayOutputStream()
                val recoveredScheduling = NativeRuntime.generate(request, NativeRuntime.ticket(), object : NativeRuntime.Sink {
                    override fun onBytes(bytes: ByteArray) { recoveredOutput.write(bytes) }
                })
                check(reference!!.contentEquals(recoveredOutput.toByteArray())) { "Scheduling rejection/cancellation damaged loaded model" }
                check(callerMask() == originalMask)
                schedulingChecks.put(recoveredScheduling.getJSONObject("cpu_scheduling"))
                NativeRuntime.unload()
                check(callerMask() == originalMask)
            }
            // A shared non-paused pool must re-apply caller placement after
            // load and after the preceding request restored the executor.
            scheduled.put("threads", 3)
            NativeRuntime.load(model, scheduled, NativeRuntime.ticket())
            repeat(2) {
                val output = ByteArrayOutputStream()
                val result = NativeRuntime.generate(request, NativeRuntime.ticket(), object : NativeRuntime.Sink {
                    override fun onBytes(bytes: ByteArray) { output.write(bytes) }
                })
                check(reference!!.contentEquals(output.toByteArray()))
                check(callerMask() == originalMask)
                check(result.getJSONObject("cpu_scheduling").getJSONArray("threads").length() == 3)
                schedulingChecks.put(result.getJSONObject("cpu_scheduling"))
            }
            NativeRuntime.unload()
            report.put("cpu_scheduling", schedulingChecks)
            val pooled = JSONObject(config.toString()).put("threads", minOf(7, allowedCpus.size))
                .put("threads_batch", minOf(4, allowedCpus.size)).put("cpu_affinity", org.json.JSONArray())
            val poolSamples = org.json.JSONArray()
            fun taskCount() = File("/proc/self/task").list()!!.size
            fun fdCount() = File("/proc/self/fd").list()!!.size
            val tasksBeforePools = taskCount()
            val fdsBeforePools = fdCount()
            repeat(30) { cycle ->
                NativeRuntime.load(model, pooled, NativeRuntime.ticket())
                val output = ByteArrayOutputStream()
                NativeRuntime.generate(request, NativeRuntime.ticket(), object : NativeRuntime.Sink {
                    override fun onBytes(bytes: ByteArray) { output.write(bytes) }
                })
                check(reference!!.contentEquals(output.toByteArray())) { "Pooled lifecycle changed output" }
                NativeRuntime.unload()
                check(callerMask() == originalMask)
                val tasks = taskCount(); val descriptors = fdCount()
                check(tasks <= tasksBeforePools + 1) { "CPU workers leaked across unload: $tasksBeforePools -> $tasks" }
                check(descriptors <= fdsBeforePools + 2) { "FDs leaked across pooled unload: $fdsBeforePools -> $descriptors" }
                if (cycle % 5 == 0 || cycle == 29) poolSamples.put(JSONObject().put("cycle", cycle)
                    .put("tasks", tasks).put("fds", descriptors).put("native_heap_bytes", Debug.getNativeHeapAllocatedSize()))
            }
            report.put("cpu_pool_lifecycle", JSONObject().put("cycles", 30).put("config", pooled)
                .put("tasks_before", tasksBeforePools).put("fds_before", fdsBeforePools).put("samples", poolSamples))
            NativeRuntime.load(model, config, NativeRuntime.ticket())
            fun expect(kind: String, action: () -> Unit) {
                var rejected = false
                try { action() } catch (failure: NativeRuntime.Failure) { check(failure.kind == kind); rejected = true }
                check(rejected) { "Expected $kind" }
            }
            val sink = object : NativeRuntime.Sink { override fun onBytes(bytes: ByteArray) {} }
            val stale = NativeRuntime.ticket()
            NativeRuntime.cancel()
            expect("cancelled") { NativeRuntime.generate(request, stale, sink) }
            expect("context_overflow") {
                NativeRuntime.generate(JSONObject(request.toString()).put("raw_prompt", "hello ".repeat(200)), NativeRuntime.ticket(), sink)
            }
            expect("runtime") {
                NativeRuntime.generate(JSONObject(request.toString()).put("max_tokens", 1.5), NativeRuntime.ticket(), sink)
            }
            expect("cancelled") {
                NativeRuntime.generate(request, NativeRuntime.ticket(), object : NativeRuntime.Sink {
                    override fun onBytes(bytes: ByteArray) { NativeRuntime.cancel() }
                })
            }
            val recovered = ByteArrayOutputStream()
            NativeRuntime.generate(request, NativeRuntime.ticket(), object : NativeRuntime.Sink {
                override fun onBytes(bytes: ByteArray) { recovered.write(bytes) }
            })
            check(reference!!.contentEquals(recovered.toByteArray())) { "Cancellation/overflow damaged state" }
            // Exercise the callback-exception path: the original Java exception must survive JNI.
            var callbackError = false
            try {
                NativeRuntime.generate(request, NativeRuntime.ticket(), object : NativeRuntime.Sink {
                    override fun onBytes(bytes: ByteArray) { throw IllegalStateException("fixture callback failure") }
                })
            } catch (error: IllegalStateException) { callbackError = error.message == "fixture callback failure" }
            check(callbackError) { "JNI swallowed the Java callback exception" }
            NativeRuntime.generate(JSONObject(request.toString()).put("raw_prompt", "Привет 🌍"), NativeRuntime.ticket(), sink)
            NativeRuntime.unload()
            report.put("service", ServiceChecks(this).run())
            report.put("passed", true).put("cycles", 30).put("memory_samples", samples)
                .put("reference", String(reference!!, Charsets.UTF_8))
                .put("checks", "repeat load/generate/unload; double unload; queued/decode cancellation; explicit overflow; invalid JSON type; recovery; callback exception; Unicode JNI")
        } catch (failure: Throwable) { report.put("error", failure.toString()) }
        finally { try { NativeRuntime.unload() } catch (_: Throwable) {} }
        File(targetContext.filesDir, "runtime-tests.json").writeText(report.toString(2))
        finish(if (report.getBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
    }
}
