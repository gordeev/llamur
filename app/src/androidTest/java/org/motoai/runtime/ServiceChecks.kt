package org.motoai.runtime

import android.app.Instrumentation
import android.content.*
import android.os.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class ServiceChecks(private val test: Instrumentation) {
    private fun views(view: android.view.View): List<android.view.View> = listOf(view) +
        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private val context get() = test.targetContext
    private val events = LinkedBlockingQueue<JSONObject>()
    private val outputChunks = LinkedBlockingQueue<Bundle>()
    private val tokenCounts = LinkedBlockingQueue<Bundle>()
    private val remote = AtomicReference<Messenger?>()
    private val connected = CountDownLatch(1)
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == RuntimeService.EVENT) {
            if (message.data.containsKey("snapshot")) events.offer(JSONObject(message.data.getString("snapshot")!!))
            else if (message.data.containsKey("output_chunk")) outputChunks.offer(Bundle(message.data))
            else if (message.data.containsKey("count_result")) tokenCounts.offer(Bundle(message.data))
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            remote.set(Messenger(binder)); send(RuntimeService.REGISTER); connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) { remote.set(null) }
    }
    private fun send(command: Int, values: Bundle = Bundle()) {
        if (command == RuntimeService.GENERATE && !values.containsKey("_benchmark_output")) values.putBoolean("_benchmark_output", true)
        checkNotNull(remote.get()).send(Message.obtain(null, command).apply { data = values; replyTo = incoming })
    }
    private fun await(timeout: Long = 30, condition: (JSONObject) -> Boolean): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + timeout * 1000
        var last: JSONObject? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val event = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
            last = event
            if (condition(event)) return event
        }
        error("Service event timed out; last=$last")
    }
    private fun state(expected: String) = await { it.optString("state") == expected }
    private fun load(model: String = "stories260K.gguf", contextSize: Int = 128, pressureThreshold: Long? = null, loadMode: String = "read") {
        events.clear()
        context.startForegroundService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.ACTION_SESSION))
        send(RuntimeService.LOAD, Bundle().apply {
            putString("model", model)
            putString("config", JSONObject().put("context", contextSize).put("threads", 2)
                .put("batch", 128).put("micro_batch", 64).put("load_mode", loadMode)
                .also { if (pressureThreshold != null) it.put("_test_load_pressure_threshold_bytes", pressureThreshold) }.toString())
        })
    }
    private fun generate(prompt: String = "Once upon a time", tokens: Int = 16) {
        events.clear()
        send(RuntimeService.GENERATE, Bundle().apply { putString("prompt", prompt); putBoolean("raw", true); putInt("tokens", tokens) })
    }

    fun runTransient(): JSONObject {
        check(BuildConfig.TRANSIENT_REPACK) { "Transient model build required" }
        val model = ModelStore.entries(context).first { it.id == "Ornith-1.5-9B-Q4_K_M.gguf" }
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        check(context.bindService(Intent(context, RuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        check(connected.await(15, TimeUnit.SECONDS))
        val report = JSONObject()
        fun request(prompt: String, tokens: Int): Pair<String, JSONObject> {
            events.clear()
            val id = "transient-${java.util.UUID.randomUUID()}"
            val body = JSONObject().put("chat", false).put("raw_prompt", prompt).put("max_tokens", tokens)
                .put("temperature", 0.0).put("seed", 42).put("trace", true).put("check_logits", true)
            send(RuntimeService.GENERATE, Bundle().apply { putString("request", body.toString()); putString("report_id", id) })
            return id to body
        }
        fun result(id: String): JSONObject {
            val file = File(context.filesDir, "service-result-$id.json")
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!file.exists() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
            return JSONObject(file.readText()).also { check(it.getBoolean("passed")) }
        }
        try {
            state("unloaded")
            context.startForegroundService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.ACTION_SESSION))
            events.clear()
            send(RuntimeService.LOAD, Bundle().apply {
                putString("model", model.id)
                putString("config", JSONObject().put("context", 8192).put("threads", 4).put("batch", 512)
                    .put("micro_batch", 128).put("load_mode", "mmap").put("weight_buffers", "native")
                    .put("transient_repack", true).toString())
            })
            val ready = await(60) { it.optString("state") == "ready" }
            check(ready.getJSONObject("load").getBoolean("transient_repack"))
            check(ready.getJSONObject("load").getInt("transient_min_rows") == BuildConfig.TRANSIENT_MIN_ROWS)
            check(ready.getJSONObject("load_admission").getLong("cpu_workspace_reserve_bytes") == 81L * 1024 * 1024)
            val prompt = "Write a Python function that adds two integers."
            val firstId = request(prompt, 8).first
            await(60) { it.optString("state") == "ready" && it.optJSONObject("result")?.optInt("generated_tokens") == 8 }
            val first = result(firstId)
            val firstWorkspace = first.getJSONObject("result").getJSONObject("cpu_workspace")
            check(firstWorkspace.getLong("graph_nodes_marked") > 0)
            check(firstWorkspace.getLong("allocated_bytes") in 1..81L * 1024 * 1024)
            if (first.getJSONObject("result").getInt("prompt_tokens") < BuildConfig.TRANSIENT_MIN_ROWS) {
                check(firstWorkspace.getLong("allocated_bytes") < 10L * 1024 * 1024) { "Tiny request packed model weights" }
            }
            val firstOutput = File(context.filesDir, first.getString("output_file")).readBytes()
            val cancelledId = request("hello ".repeat(512), 32).first
            await(30) { it.optString("native_phase") == "prefill" }
            Thread.sleep(200)
            val stopAt = SystemClock.elapsedRealtime()
            send(RuntimeService.STOP)
            val cancelled = await(10) { it.optString("state") == "ready" && it.optString("error_kind") == "cancelled" }
            val stopMs = SystemClock.elapsedRealtime() - stopAt
            check(stopMs < 5000 && cancelled.optLong("output_bytes") == 0L)
            check(cancelled.getInt("pid") == ready.getInt("pid"))
            val recoveredId = request(prompt, 8).first
            await(60) { it.optString("state") == "ready" && it.optJSONObject("result")?.optInt("generated_tokens") == 8 }
            val recovered = result(recoveredId)
            check(firstOutput.contentEquals(File(context.filesDir, recovered.getString("output_file")).readBytes()))
            check(first.getJSONObject("result").getJSONObject("trace").getJSONArray("generated_ids").toString() ==
                recovered.getJSONObject("result").getJSONObject("trace").getJSONArray("generated_ids").toString())
            check(recovered.getJSONObject("result").getJSONObject("cpu_workspace").getLong("allocated_bytes") <= 81L * 1024 * 1024)
            send(RuntimeService.UNLOAD); state("unloaded")
            report.put("passed", true).put("stop_to_ready_ms", stopMs).put("same_pid", ready.getInt("pid"))
                .put("first_report_id", firstId).put("cancelled_report_id", cancelledId).put("recovered_report_id", recoveredId)
                .put("workspace", recovered.getJSONObject("result").getJSONObject("cpu_workspace"))
                .put("tiny_request_workspace_bytes", firstWorkspace.getLong("allocated_bytes"))
                .put("transient_min_rows", BuildConfig.TRANSIENT_MIN_ROWS)
                .put("checks", "Explicit Ornith native/workspace load, bounded scratch, prefill cancellation before output, identical tokens/output after cancellation, unload")
            return report
        } finally {
            try { send(RuntimeService.STOP); send(RuntimeService.UNLOAD); send(RuntimeService.UNREGISTER) } catch (_: Exception) {}
            context.unbindService(connection)
            test.runOnMainSync { activity.finish() }
        }
    }

    fun run(): JSONObject {
        val reusedId = "replay-check-${java.util.UUID.randomUUID()}"
        check(DebugRuns.claim(context, reusedId))
        check(!DebugRuns.claim(context, reusedId))
        check(!DebugRuns.claim(context, "../invalid"))
        val legacy = File(context.filesDir, "service-progress-legacy-$reusedId.json")
        legacy.writeText("{}")
        check(!DebugRuns.claim(context, "legacy-$reusedId"))
        legacy.delete()
        val outputsBeforeReuse = context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("output-") }.map { it.name }.toSet()
        val reused = test.startActivitySync(Intent(context, MainActivity::class.java)
            .putExtra("serviceSmoke", true).putExtra("runId", reusedId).putExtra("model", "stories260K.gguf")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        test.waitForIdleSync()
        test.runOnMainSync {
            check(views(reused.window.decorView).filterIsInstance<android.widget.TextView>()
                .any { it.text.toString().contains("Старый debug-запуск не повторяется") })
            reused.finish()
        }
        File(context.filesDir, "bench/started-$reusedId.marker").delete()
        check(context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("output-") }.map { it.name }.toSet() == outputsBeforeReuse)
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        check(context.bindService(Intent(context, RuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        check(connected.await(15, TimeUnit.SECONDS))
        try {
            state("unloaded")
            load(); send(RuntimeService.STOP)
            await { it.optString("state") == "unloaded" && it.optString("error_kind") == "cancelled" }
            load(); val ready = state("ready")
            check(ready.getInt("pid") != Process.myPid()) { "Native model was not isolated" }
            generate()
            val first = await { it.optJSONObject("result")?.optInt("generated_tokens") == 16 }
            val reference = File(context.filesDir, first.getString("output_file")).readBytes()
            fun count(prompt: String): JSONObject {
                val id = java.util.UUID.randomUUID().toString()
                send(RuntimeService.PREPARE, Bundle().apply {
                    putString("count_id", id)
                    putString("request", JSONObject().put("chat", false).put("raw_prompt", prompt).put("max_tokens", 16).toString())
                })
                val event = checkNotNull(tokenCounts.poll(5, TimeUnit.SECONDS)) { "Exact token count timed out" }
                check(event.getString("count_id") == id)
                return JSONObject(event.getString("count_result")!!).also { check(it.getBoolean("ok")) }
            }
            val exact = count("Once upon a time")
            check(exact.getInt("prompt_tokens") == first.getJSONObject("result").getInt("prompt_tokens"))
            check(exact.getBoolean("fits") && exact.getInt("context") == 128)
            check(exact.getInt("remaining_tokens") == 128 - exact.getInt("prompt_tokens") - 16)
            val tooLarge = count("hello ".repeat(200))
            check(!tooLarge.getBoolean("fits") && tooLarge.getInt("remaining_tokens") < 0)
            // Ordinary, non-benchmark response transport must never create a
            // persistent output. The existing file control is the exact oracle.
            val filesBeforeMemory = context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("output-") }.map { it.name }.toSet()
            events.clear(); outputChunks.clear()
            send(RuntimeService.GENERATE, Bundle().apply {
                putString("prompt", "Once upon a time"); putBoolean("raw", true); putInt("tokens", 16)
                putBoolean("_benchmark_output", false)
            })
            val memoryResult = await { it.optString("state") == "ready" && it.optJSONObject("result")?.optInt("generated_tokens") == 16 }
            check(memoryResult.getString("output_transport") == "memory" && !memoryResult.has("output_file"))
            val memoryBytes = java.io.ByteArrayOutputStream()
            val memoryId = memoryResult.getString("output_id")
            while (memoryBytes.size() < memoryResult.getLong("output_bytes")) {
                send(RuntimeService.READ_OUTPUT, Bundle().apply {
                    putString("output_id", memoryId); putInt("offset", memoryBytes.size())
                })
                val chunk = checkNotNull(outputChunks.poll(5, TimeUnit.SECONDS)) { "RAM output chunk timed out" }
                check(chunk.getString("output_id") == memoryId && chunk.getInt("output_offset") == memoryBytes.size())
                val bytes = checkNotNull(chunk.getByteArray("output_chunk")); check(bytes.isNotEmpty() && bytes.size <= 32768)
                memoryBytes.write(bytes)
            }
            check(memoryBytes.toByteArray().contentEquals(reference))
            check(context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("output-") }.map { it.name }.toSet() == filesBeforeMemory)

            // Reduce the debug-only budget; never allocate artificial pressure or
            // alter other apps. Refusal must preserve the already loaded model.
            events.clear()
            send(RuntimeService.LOAD, Bundle().apply {
                putString("model", "stories260K.gguf")
                putString("config", JSONObject().put("context", 128).put("_test_memory_budget_bytes", 0).toString())
            })
            val refused = await { it.optString("error_kind") == "memory_admission" }
            check(refused.optString("state") == "ready" && refused.getInt("pid") == ready.getInt("pid"))
            check(!refused.getJSONObject("load_admission").getBoolean("accepted"))
            generate()
            val afterRefusal = await { it.optJSONObject("result")?.optInt("generated_tokens") == 16 && !it.has("error_kind") }
            check(reference.contentEquals(File(context.filesDir, afterRefusal.getString("output_file")).readBytes()))
            // A known unstable profile must refuse before touching the small
            // live model, even when Android reports enough available memory.
            val ornith = ModelStore.entries(context).firstOrNull { it.id == "Ornith-1.5-9B-Q4_K_M.gguf" }
            if (ornith != null) {
                events.clear()
                send(RuntimeService.LOAD, Bundle().apply {
                    putString("model", ornith.id)
                    putString("config", JSONObject().put("context", 8192).put("threads", 4)
                        .put("batch", 512).put("micro_batch", 128).put("load_mode", "mmap")
                        .put("weight_buffers", "cpu_repack_q4").toString())
                })
                val unstable = await { it.optString("error_kind") == "unsupported_profile" }
                check(unstable.optString("state") == "ready" && unstable.getInt("pid") == ready.getInt("pid"))
                check(unstable.getJSONObject("load_admission").getString("rejection_reason") == "q4_ornith_repack_failed_stability")
                generate()
                val preserved = await { it.optJSONObject("result")?.optInt("generated_tokens") == 16 && !it.has("error_kind") }
                check(reference.contentEquals(File(context.filesDir, preserved.getString("output_file")).readBytes()))
            }
            generate("hello ".repeat(200))
            val overflow = await { it.optString("state") == "ready" && it.optString("error_kind") == "context_overflow" }
            check(overflow.optString("message").contains("exceeds configured context"))
            check(overflow.getJSONObject("error_details").getInt("context") == 128)
            check(overflow.getJSONObject("error_details").getInt("output_reserve") == 16)
            check(overflow.getJSONObject("error_details").getInt("prompt_tokens") > 128)
            generate(tokens = 64)
            state("generating")
            send(RuntimeService.STOP); send(RuntimeService.STOP)
            await { it.optString("state") == "ready" && it.optString("error_kind") == "cancelled" }
            generate()
            val recovery = await { it.optJSONObject("result")?.optInt("generated_tokens") == 16 && it.optString("state") == "ready" }
            check(reference.contentEquals(File(context.filesDir, recovery.getString("output_file")).readBytes()))
            events.clear()
            val systemMemory = android.app.ActivityManager.MemoryInfo().also {
                context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
            }
            send(RuntimeService.GENERATE, Bundle().apply {
                putString("prompt", "Once upon a time"); putBoolean("raw", true); putInt("tokens", 16)
                putLong("_test_generate_pressure_threshold_bytes", systemMemory.totalMem)
            })
            val pressure = await { it.optString("state") == "ready" && it.optString("error_kind") == "memory_pressure" }
            check(pressure.getJSONObject("generation_memory_watch").getBoolean("pressure_abort"))
            check(pressure.getInt("pid") == recovery.getInt("pid") && !pressure.has("result"))
            generate()
            val afterPressure = await { it.optString("state") == "ready" && it.has("result") && !it.has("error_kind") }
            check(reference.contentEquals(File(context.filesDir, afterPressure.getString("output_file")).readBytes()))
            check(!afterPressure.getJSONObject("generation_memory_watch").getBoolean("pressure_abort"))
            events.clear()
            send(RuntimeService.LOAD, Bundle().apply {
                putString("model", "stories260K.gguf")
                putString("config", JSONObject().put("context", 128).put("load_mode", "invalid-fixture-mode").toString())
            })
            await { it.optString("state") == "unloaded" && it.optString("message").contains("Unknown load mode") }
            load(); state("ready")
            val notifications = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            val notification = notifications.firstOrNull { it.id == RuntimeService.NOTIFICATION } ?: error("No runtime notification")
            notification.notification.actions.first().actionIntent.send()
            state("unloaded")
            send(RuntimeService.UNLOAD); state("unloaded")
            load(contextSize = 2048); state("ready")
            generate(tokens = 1536)
            val largerBudget = await { it.optString("state") == "ready" && it.has("result") }
            check(!largerBudget.has("error_kind")) { "Output budget above1024 was rejected" }
            generate(tokens = 2048)
            await { it.optString("error_kind") == "context_overflow" && it.optString("state") == "ready" }
            send(RuntimeService.UNLOAD); state("unloaded")
            // Real large-file cancellation, without loading another copy in the UI process.
            val gemma = ModelStore.entries(context).firstOrNull { it.id == "gemma-4-E4B-it-Q4_K_M.gguf" }
            var loadCancellation = "fixture not staged"
            var prefillCancellation = "fixture not staged"
            var pressureCancellation = "fixture not staged"
            if (gemma != null) {
                val total = android.app.ActivityManager.MemoryInfo().also { context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }.totalMem
                load(gemma.id, pressureThreshold = total, loadMode = "mmap"); state("loading")
                val pressure = await(45) { it.optString("state") == "unloaded" && it.optString("error_kind") == "memory_admission" }
                check(pressure.getJSONObject("load_memory_watch").getBoolean("pressure_abort"))
                pressureCancellation = "Gemma load cancelled with a stricter debug threshold; no artificial allocation or foreign-app change"
                load(gemma.id, loadMode = "mmap"); state("loading"); send(RuntimeService.STOP)
                await(45) { it.optString("state") == "unloaded" && it.optString("message").contains("отменена") }
                loadCancellation = "passed on Gemma"
                load(gemma.id, 128, loadMode = "mmap"); val gemmaReady = state("ready")
                val allocations = gemmaReady.getJSONObject("load").getJSONArray("buffer_allocations")
                val actualContext = (0 until allocations.length()).sumOf { allocations.getJSONObject(it).getLong("context_bytes") }
                check(actualContext == gemmaReady.getJSONObject("load_admission").getLong("estimated_context_bytes"))
                generate("hello ".repeat(64), 32)
                await { it.optString("native_phase") == "prefill" }
                Thread.sleep(100)
                val stopAt = SystemClock.elapsedRealtime()
                send(RuntimeService.STOP)
                val cancelled = await { it.optString("state") == "ready" && it.optString("error_kind") == "cancelled" }
                check(cancelled.optLong("output_bytes") == 0L) { "Expected cancellation before first output" }
                prefillCancellation = "passed on Gemma; stop-to-ready ${SystemClock.elapsedRealtime() - stopAt}ms"
                send(RuntimeService.UNLOAD); state("unloaded")
            }
            load(); val isolated = state("ready")
            val previousPid = isolated.getInt("pid")
            events.clear()
            // Both processes belong to this test application. Simulates a fatal native/LMKD termination.
            Process.killProcess(previousPid)
            val deathDeadline = SystemClock.elapsedRealtime() + 5000
            while (remote.get() != null && SystemClock.elapsedRealtime() < deathDeadline) Thread.sleep(10)
            check(remote.get() == null) { "No service disconnect after process kill" }
            // Recovery is an explicit user action, not a deadline on Android's
            // exponential automatic service restart backoff. Never replay a prompt.
            var loadEnabled = false
            val uiDeadline = SystemClock.elapsedRealtime() + 5000
            while (!loadEnabled && SystemClock.elapsedRealtime() < uiDeadline) {
                test.runOnMainSync {
                    loadEnabled = views(activity.window.decorView).filterIsInstance<android.widget.Button>()
                        .first { it.text.toString() == "Загрузить в память" }.isEnabled
                }
                if (!loadEnabled) Thread.sleep(10)
            }
            check(loadEnabled) { "User cannot tap Load after service death" }
            test.runOnMainSync {
                val all = views(activity.window.decorView)
                val spinner = all.filterIsInstance<android.widget.Spinner>().single()
                val index = (0 until spinner.adapter.count).first { spinner.adapter.getItem(it).toString().contains("stories260K") }
                spinner.setSelection(index)
                all.filterIsInstance<android.widget.EditText>().first { it.contentDescription?.startsWith("Контекст:") == true }.setText("128")
                all.filterIsInstance<android.widget.Button>().first { it.text.toString() == "Загрузить в память" }.also {
                    check(it.isEnabled && it.isShown) { "Recovery Load is not available to the user" }
                }.performClick()
            }
            val restored = await(30) { it.optString("state") == "ready" && it.optInt("pid") != previousPid && it.optInt("pid") != 0 }
            check(restored.getInt("pid") != Process.myPid())
            check(!restored.has("result") && !restored.has("output_file")) { "A prompt was replayed during recovery" }
            load(); state("ready"); generate()
            val afterDeath = await { it.optJSONObject("result")?.optInt("generated_tokens") == 16 }
            check(reference.contentEquals(File(context.filesDir, afterDeath.getString("output_file")).readBytes()))
            send(RuntimeService.UNLOAD); state("unloaded")
            val imported = "270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d.gguf"
            var importUi = "SAF fixture not imported"
            if (File(context.filesDir, "models/$imported").isFile) {
                test.runOnMainSync {
                    val all = views(activity.window.decorView)
                    val spinner = all.filterIsInstance<android.widget.Spinner>().single()
                    val index = (0 until spinner.adapter.count).first { spinner.adapter.getItem(it).toString().contains("MotoAI-stories260K") }
                    spinner.setSelection(index)
                    all.filterIsInstance<android.widget.EditText>().first { it.contentDescription?.startsWith("Контекст:") == true }.setText("256")
                    all.filterIsInstance<android.widget.EditText>().first { it.contentDescription?.startsWith("Резерв ответа") == true }.setText("16")
                    all.filterIsInstance<android.widget.EditText>().first { it.hint.toString() == "Запрос" }.setText("Hello")
                    all.filterIsInstance<android.widget.Button>().first { it.text.toString() == "Загрузить в память" }.performClick()
                }
                val uiLoaded = await { it.optString("state") == "ready" && it.optString("model") == imported }
                check(uiLoaded.getJSONObject("load").getInt("context") == 256)
                events.clear()
                test.runOnMainSync { views(activity.window.decorView).filterIsInstance<android.widget.Button>().first { it.text.toString() == "Запустить" }.performClick() }
                await { it.optString("state") == "ready" && it.optJSONObject("result")?.optInt("generated_tokens", 0)?.let { n -> n > 0 } == true }
                importUi = "SAF imported SHA-256 file loaded/generated through normal UI"
                send(RuntimeService.UNLOAD); state("unloaded")
            }
            return JSONObject().put("passed", true).put("isolated_pid", previousPid).put("recovered_pid", restored.getInt("pid"))
                .put("recovery_ui", "Visible enabled Load button after disconnect; explicit recovery; no prompt replay")
                .put("debug_run_reuse", "Previously claimed benchmark intent opens normal UI without automatic inference")
                .put("above_1024_budget", "1536 accepted; prompt+2048 correctly rejected in2048 context")
                .put("memory_admission", "Typed refusal with reduced debug budget; existing model/PID/output preserved")
                .put("q4_stability_refusal", if (ornith != null) "Passed before model load; existing model/PID/output preserved" else "Not run: Ornith fixture absent")
                .put("load_pressure_cancellation", pressureCancellation)
                .put("import_ui", importUi).put("notification_stop", "passed via notification PendingIntent").put("load_cancellation", loadCancellation).put("prefill_cancellation", prefillCancellation)
                .put("checks", "Messenger load/generate; injected generation-memory pressure/cancel and exact recovery; overflow; repeated Stop; cancellation recovery; double unload; isolated process death and reload")
        } finally {
            try { send(RuntimeService.UNLOAD); send(RuntimeService.UNREGISTER) } catch (_: Exception) {}
            context.unbindService(connection)
        }
    }
}
