package org.motoai.runtime

import android.app.Activity
import android.os.Bundle
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Base64
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

class DiagnosticsActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private lateinit var probe: Button
    @Volatile private var nativeActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setTextIsSelectable(true); textSize = 14f }
        probe = Button(this).apply {
            text = "Check CPU / GPU capabilities"
            setOnClickListener { probe() }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(probe)
            addView(ScrollView(this@DiagnosticsActivity).apply { addView(output) })
        }
        setContentView(layout)
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 && intent.getBooleanExtra("nativeSmoke", false)) {
            if (DebugRuns.claim(this, intent.getStringExtra("runId"))) nativeSmoke()
            else output.text = UiText.get(R.string.ui_this_debug_run_was_already_used_or)
        } else probe()
    }

    private fun utf8Prefix(bytes: ByteArray): String {
        val input = ByteBuffer.wrap(bytes)
        val chars = CharBuffer.allocate(bytes.size)
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(input, chars, false)
        chars.flip()
        return chars.toString()
    }

    private fun nativeSmoke() {
        probe.isEnabled = false
        output.text = "Native runtime smoke: loading…"
        val context = applicationContext
        val modelName = intent.getStringExtra("model") ?: "stories260K.gguf"
        val runId = intent.getStringExtra("runId") ?: "manual"
        if (!modelName.matches(Regex("[a-zA-Z0-9._-]+")) || !runId.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) {
            output.text = "Invalid debug filename or run ID"
            return
        }
        val config = JSONObject().put("context", intent.getIntExtra("context", 4096))
            .put("threads", intent.getIntExtra("threads", 4)).put("batch", 512)
            .put("micro_batch", intent.getIntExtra("microBatch", 128))
            .put("load_mode", intent.getStringExtra("loadMode") ?: "read")
        val raw = intent.getBooleanExtra("raw", false)
        val prompt = intent.getStringExtra("prompt") ?: "Write a Python function that adds two integers."
        val request = JSONObject().put("chat", !raw).put("raw_prompt", if (raw) prompt else "")
            .put("max_tokens", intent.getIntExtra("tokens", 32)).put("temperature", 0.0)
            .put("seed", 42).put("thinking", true).put("trace", intent.getBooleanExtra("trace", false))
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", "You are a helpful assistant."))
                .put(JSONObject().put("role", "user").put("content", prompt)))
        worker.execute {
            val report = JSONObject().put("run_id", runId).put("model", modelName)
                .put("config", config).put("request", request).put("passed", false)
            val collected = ByteArrayOutputStream()
            var source: ModelStore.OpenModel? = null
            try {
                report.put("backend", NativeRuntime.initialize(context))
                nativeActive = true
                if (isDestroyed) throw IllegalStateException("Activity was destroyed before load")
                val ticket = NativeRuntime.ticket()
                source = ModelStore.open(context, modelName)
                report.put("load", NativeRuntime.load(source.path, config, ticket).put("model_location", source.location))
                var updated = 0L
                val result = NativeRuntime.generate(request, ticket, object : NativeRuntime.Sink {
                    override fun onBytes(bytes: ByteArray) {
                        collected.write(bytes)
                        val now = SystemClock.elapsedRealtime()
                        if (updated == 0L || now - updated >= 100) {
                            updated = now
                            val text = utf8Prefix(collected.toByteArray())
                            runOnUiThread { if (!isDestroyed) output.text = text }
                        }
                    }
                })
                report.put("result", result).put("passed", result.getInt("generated_tokens") > 0)
            } catch (error: Throwable) {
                report.put("error", error.toString())
            } finally {
                if (nativeActive) {
                    try { NativeRuntime.unload() } catch (error: Throwable) { report.put("cleanup_error", error.toString()).put("passed", false) }
                    nativeActive = false
                }
                source?.close()
            }
            report.put("output", utf8Prefix(collected.toByteArray()))
                .put("output_base64", Base64.encodeToString(collected.toByteArray(), Base64.NO_WRAP))
            try {
                context.openFileOutput("native-result-$runId.json", MODE_PRIVATE).use {
                    it.write(report.toString(2).toByteArray(Charsets.UTF_8))
                }
            } catch (error: Exception) { report.put("save_error", error.toString()) }
            runOnUiThread { if (!isDestroyed) { output.text = report.toString(2); probe.isEnabled = true } }
        }
    }

    private fun probe() {
        probe.isEnabled = false
        output.text = "Checking capabilities from app UID…"
        val context = applicationContext
        worker.execute {
            var report = try {
                System.loadLibrary("motoai-probe")
                String(nativeProbe(), Charsets.UTF_8)
            } catch (failure: LinkageError) {
                "Native library unavailable: ${failure.message}"
            } catch (failure: Exception) {
                "Probe failed: ${failure.message}"
            }
            try {
                context.openFileOutput("capabilities.txt", MODE_PRIVATE).use {
                    it.write(report.toByteArray(Charsets.UTF_8))
                }
            } catch (failure: Exception) {
                report += "\nUnable to save report: ${failure.message}"
            }
            runOnUiThread {
                if (!isDestroyed) {
                    output.text = report
                    probe.isEnabled = true
                }
            }
        }
    }

    private external fun nativeProbe(): ByteArray

    override fun onDestroy() {
        if (nativeActive) NativeRuntime.cancel()
        worker.shutdown()
        super.onDestroy()
    }
}
