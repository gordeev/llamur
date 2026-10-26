package org.motoai.runtime

import android.app.Instrumentation
import android.content.*
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Public scored fixture through the ordinary service worker. No speed promotion. */
internal class PrefixChecks(private val test: Instrumentation) {
    fun run(spec: JSONObject, id: String, modelName: String, transient: Boolean = false, swaFull: Boolean = true): JSONObject {
        require(modelName in listOf("Ornith-1.5-9B-Q4_K_M.gguf", "gemma-4-E4B-it-Q4_K_M.gguf"))
        require(id.matches(Regex("prefix-[a-zA-Z0-9_-]{1,64}")))
        val context = test.targetContext
        val events = LinkedBlockingQueue<JSONObject>()
        val connected = CountDownLatch(1)
        lateinit var remote: Messenger
        val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
            message.data.getString("snapshot")?.let { events.offer(JSONObject(it)) }; true
        })
        fun send(command: Int, body: Bundle = Bundle()) {
            remote.send(Message.obtain(null, command).apply { data = body; replyTo = incoming })
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = Messenger(binder); send(RuntimeService.REGISTER); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        fun await(seconds: Int = 30, condition: (JSONObject) -> Boolean): JSONObject {
            val end = SystemClock.elapsedRealtime() + seconds * 1000L
            var last: JSONObject? = null
            while (SystemClock.elapsedRealtime() < end) {
                val item = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
                last = item
                if (condition(item)) return item
            }
            error("Prefix service deadline; last=$last")
        }
        val observations = File(context.filesDir, "$id.jsonl")
        check(!observations.exists()) { "Never replay a previous prefix test" }
        val trials = JSONArray()
        val request = JSONObject(spec.getJSONObject("request").toString())
        check(request.getJSONArray("score_continuation_ids").length() > 0)
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        check(context.bindService(Intent(context, RuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        check(connected.await(15, TimeUnit.SECONDS))
        var trial = 0
        try {
            await { it.optString("state") == "unloaded" }
            fun load(cache: Int) {
                events.clear()
                context.startForegroundService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.ACTION_SESSION))
                send(RuntimeService.LOAD, Bundle().apply {
                    putString("model", modelName)
                    putString("config", JSONObject().put("context", 4096).put("threads", 4).put("batch", 512)
                        .put("micro_batch", 128).put("load_mode", "mmap").put("prefix_cache_mib", cache)
                        .put("transient_repack", transient && cache > 0).put("swa_full", if (cache > 0) swaFull else true).toString())
                })
                val ready = await(90) { it.optString("state") == "ready" || it.has("error_kind") }
                check(!ready.has("error_kind")) { ready.toString() }
                observations.appendText(JSONObject().put("kind", "load").put("snapshot", ready).toString() + "\n")
            }
            fun run(label: String, body: JSONObject = request, cancel: Boolean = false): JSONObject {
                val runId = "$id-${trial++}"
                events.clear()
                val started = SystemClock.elapsedRealtimeNanos()
                send(RuntimeService.GENERATE, Bundle().apply {
                    putString("request", body.toString()); putString("report_id", runId)
                    putBoolean("_benchmark_output", true); putLong("client_sent_ns", started)
                })
                if (cancel) {
                    await { it.optString("state") == "generating" && it.optString("native_phase") == "prefill" }
                    send(RuntimeService.STOP)
                }
                val completed = await(480) { it.optString("state") == "ready" && (it.has("result") || it.has("error_kind")) }
                if (cancel) check(completed.optString("error_kind") == "cancelled") else check(!completed.has("error_kind"))
                val file = File(context.filesDir, "service-result-$runId.json")
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (!file.exists() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
                val raw = JSONObject(file.readText())
                val row = JSONObject().put("name", label).put("started_ns", started).put("ended_ns", SystemClock.elapsedRealtimeNanos()).put("snapshot", raw)
                observations.appendText(row.toString() + "\n"); trials.put(row)
                return raw
            }
            fun same(reference: JSONObject, actual: JSONObject) {
                val a = reference.getJSONObject("result"); val b = actual.getJSONObject("result")
                for (key in listOf("token_nll", "prompt_tokens", "generated_tokens", "finish_reason"))
                    check(a.get(key).toString() == b.get(key).toString()) { "Prefix numeric mismatch: $key" }
                for (key in listOf("ids", "values"))
                    check(a.getJSONObject("first_logits_top8").getJSONArray(key).toString() ==
                        b.getJSONObject("first_logits_top8").getJSONArray(key).toString()) { "Prefix top8 mismatch: $key" }
                check(a.getJSONObject("trace").getJSONArray("prompt_ids").toString() == b.getJSONObject("trace").getJSONArray("prompt_ids").toString())
                check(a.getJSONObject("trace").getJSONArray("generated_ids").toString() == b.getJSONObject("trace").getJSONArray("generated_ids").toString())
                check(File(context.filesDir, reference.getString("output_file")).readBytes().contentEquals(
                    File(context.filesDir, actual.getString("output_file")).readBytes()))
            }
            load(0)
            request.put("cache_session", "").put("trace", true).put("check_logits", true)
            val reference = run("cache_disabled")
            load(128)
            request.put("cache_session", "$id-A")
            val miss = run("first_miss"); same(reference, miss)
            check(miss.getJSONObject("result").getInt("reused_prompt_tokens") == 0)
            val hit = run("exact_prefix_hit"); same(reference, hit)
            check(hit.getJSONObject("result").getInt("reused_prompt_tokens") >= 512)
            check(hit.getJSONObject("result").getLong("prefix_cache_bytes") in 1..128L * 1024 * 1024)
            run("cancel_after_restore", cancel = true)
            val recovery = run("restore_cancel_recovery"); same(reference, recovery)
            check(recovery.getJSONObject("result").getInt("reused_prompt_tokens") >= 512)
            request.put("cache_session", "$id-B")
            val isolated = run("different_session_miss"); same(reference, isolated)
            check(isolated.getJSONObject("result").getInt("reused_prompt_tokens") == 0)
            send(RuntimeService.UNLOAD); await { it.optString("state") == "unloaded" }
            load(128)
            val fresh = run("unload_clears_state"); same(reference, fresh)
            check(fresh.getJSONObject("result").getInt("reused_prompt_tokens") == 0)
            send(RuntimeService.UNLOAD); await { it.optString("state") == "unloaded" }
            return JSONObject().put("passed", true).put("artifact", observations.name).put("trials", trials.length())
                .put("scope", "Ordinary APK service correctness and cancellation; no matched speed/profile acceptance")
        } finally {
            runCatching { send(RuntimeService.STOP); send(RuntimeService.UNLOAD); send(RuntimeService.UNREGISTER) }
            context.unbindService(connection); test.runOnMainSync { activity.finish() }
        }
    }
}
