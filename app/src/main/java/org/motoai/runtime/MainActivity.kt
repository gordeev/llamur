package org.motoai.runtime

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors

/** Minimal M1 harness: one explicit request, no hidden history truncation or prompt rewriting. */
class MainActivity : Activity() {
    private object Draft { var prompt = "" }
    private lateinit var models: Spinner
    private lateinit var contextInput: EditText
    private lateinit var threadsInput: EditText
    private lateinit var tokensInput: EditText
    private lateinit var prompt: EditText
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var active: TextView
    private lateinit var importStatus: TextView
    private lateinit var importButton: Button
    private lateinit var moveButton: Button
    private lateinit var cancelImport: Button
    private lateinit var load: Button
    private lateinit var generate: Button
    private lateinit var stop: Button
    private lateinit var unload: Button
    private var entries = emptyList<ModelStore.Entry>()
    private var remote: Messenger? = null
    private var bound = false
    private var state = "unloaded"
    private var loadedContext = 4096
    private val reader = Executors.newSingleThreadExecutor()
    private var renderEpoch = 0L
    private var debugStarted = false
    private var debugGenerated = false
    private var debugFinished = false
    private var debugMode = false
    private var debugSuppressed = false
    private var debugSpec: JSONObject? = null
    private var deadRuntime = false
    private var pendingLoad: Bundle? = null
    private var storageBusy = false
    private var memoryOutputId = ""
    private var memoryOutput = java.io.ByteArrayOutputStream()
    private var memoryReadPending = false
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == RuntimeService.EVENT && message.data.containsKey("output_chunk")) {
            memoryReadPending = false
            val data = message.data
            if (data.getString("output_id") == memoryOutputId && data.getInt("output_offset") == memoryOutput.size()) {
                memoryOutput.write(data.getByteArray("output_chunk") ?: byteArrayOf())
                output.text = utf8Prefix(memoryOutput.toByteArray(), state == "ready" && data.getInt("output_total") == memoryOutput.size())
                if (data.getInt("output_total") > memoryOutput.size()) readMemoryOutput()
            }
        } else if (message.what == RuntimeService.EVENT) {
            try { display(JSONObject(message.data.getString("snapshot") ?: "{}")) }
            catch (failure: Exception) { status.text = UiText.get(R.string.ui_state_error, (failure.message)) }
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            remote = Messenger(binder)
            send(RuntimeService.REGISTER)
            pendingLoad?.let { pendingLoad = null; send(RuntimeService.LOAD, it) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            remote = null; deadRuntime = true
            state = "unloaded"
            status.text = UiText.get(R.string.ui_model_process_ended_by_android_or_the)
            buttons()
        }
        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
            // Android will not reconnect a dead binding. The next explicit
            // Load rebinds; a prompt is never replayed.
            if (bound) { unbindService(this); bound = false }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && intent.getBooleanExtra("nativeSmoke", false)) {
            startActivity(Intent(this, DiagnosticsActivity::class.java).putExtras(intent))
            finish(); return
        }
        debugMode = debuggable && intent.getBooleanExtra("serviceSmoke", false)
        debugSuppressed = debugMode && !DebugRuns.claim(this, intent.getStringExtra("runId"))
        if (debugSuppressed) debugMode = false
        if (debugMode && intent.hasExtra("requestFile")) {
            val name = intent.getStringExtra("requestFile") ?: error("Missing fixture filename")
            require(RuntimeService.validName(name))
            val file = File(filesDir, "bench/$name")
            require(file.length() <= 128 * 1024) { "Fixture file exceeds diagnostic budget" }
            debugSpec = JSONObject(file.readText())
        }
        debugStarted = savedInstanceState?.getBoolean("debugStarted") ?: false
        debugGenerated = savedInstanceState?.getBoolean("debugGenerated") ?: false
        debugFinished = savedInstanceState?.getBoolean("debugFinished") ?: false
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 20) }
        fun label(text: String) = TextView(this).apply { this.text = text; layout.addView(this) }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { action() }; layout.addView(this)
        }
        fun number(label: String, value: String) = EditText(this).apply {
            hint = label; setText(value); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            contentDescription = label; layout.addView(this)
        }
        label(UiText.get(R.string.ui_llamur_local_cpu_runtime))
        label(UiText.get(R.string.ui_m1_test_interface_one_request_thinking_enabled))
        importButton = button(UiText.get(R.string.ui_import_gguf_single_file)) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, 1)
        }
        importStatus = label("")
        cancelImport = button(UiText.get(R.string.ui_cancel_import)) { ModelStore.cancel() }
        models = Spinner(this).also { layout.addView(it) }
        moveButton = button(UiText.get(R.string.ui_move_selected_model_to_downloads_llamur)) {
            entries.getOrNull(models.selectedItemPosition)?.let { ModelStore.move(this, it.id) }
        }
        contextInput = number(UiText.get(R.string.ui_context_prompt_template_tokens_response_reserve), savedInstanceState?.getString("context") ?: "4096")
        threadsInput = number("CPU threads", savedInstanceState?.getString("threads") ?: "4")
        tokensInput = number(UiText.get(R.string.ui_response_reserve_tokens_within_context), savedInstanceState?.getString("tokens") ?: "2048")
        label(UiText.get(R.string.ui_cpu_weights_read_without_mmap_batch_512))
        load = button(UiText.get(R.string.ui_load_into_memory)) { loadSelected() }
        prompt = EditText(this).apply {
            hint = UiText.get(R.string.ui_prompt); minLines = 2; maxLines = 6; isSingleLine = false
            setText(Draft.prompt)
            layout.addView(this)
        }
        generate = button(UiText.get(R.string.ui_run)) { generate() }
        stop = button(UiText.get(R.string.ui_stop_response)) { send(RuntimeService.STOP) }
        unload = button(UiText.get(R.string.ui_unload_model)) { send(RuntimeService.UNLOAD) }
        button(UiText.get(R.string.ui_cpu_gpu_diagnostics)) { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        active = label(UiText.get(R.string.ui_active_model_none))
        status = label(if (debugSuppressed) UiText.get(R.string.ui_old_debug_run_will_not_be_repeated) else UiText.get(R.string.ui_connecting_to_runtime))
        output = label("").apply { setTextIsSelectable(true); textSize = 16f }
        setContentView(ScrollView(this).apply {
            addView(layout)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        })
        refreshModels()
        buttons()
    }

    override fun onStart() {
        super.onStart()
        if (!::models.isInitialized) return
        ModelStore.observe { text, busy ->
            storageBusy = busy
            importStatus.text = text
            cancelImport.visibility = if (busy) View.VISIBLE else View.GONE
            if (!busy) refreshModels()
            buttons()
        }
        bound = bindService(Intent(this, RuntimeService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (::prompt.isInitialized) Draft.prompt = prompt.text.toString()
        memoryReadPending = false
        pendingLoad = null
        if (bound) { send(RuntimeService.UNREGISTER); unbindService(connection); bound = false; remote = null }
        ModelStore.observe(null)
        super.onStop()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        if (!::prompt.isInitialized) return
        Draft.prompt = prompt.text.toString(); out.putString("context", contextInput.text.toString())
        out.putString("threads", threadsInput.text.toString()); out.putString("tokens", tokensInput.text.toString())
        out.putBoolean("debugStarted", debugStarted); out.putBoolean("debugGenerated", debugGenerated); out.putBoolean("debugFinished", debugFinished)
    }

    override fun onDestroy() { reader.shutdown(); super.onDestroy() }

    private fun refreshModels() {
        val selected = entries.getOrNull(models.selectedItemPosition)?.id
        entries = ModelStore.entries(this)
        models.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, entries.map { it.label })
        val index = entries.indexOfFirst { it.id == selected }
        if (index >= 0) models.setSelection(index)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (remote != null) send(RuntimeService.REGISTER)
    }

    private fun send(command: Int, values: Bundle = Bundle()): Boolean {
        if (command == RuntimeService.REGISTER) values.putBoolean("ui_visible", true)
        val connection = remote ?: return false
        return try {
            connection.send(Message.obtain(null, command).apply { data = values; replyTo = incoming })
            true
        } catch (_: RemoteException) { status.text = UiText.get(R.string.ui_runtime_connection_lost); remote = null; buttons(); false }
    }

    private fun startSession() {
        startForegroundService(Intent(this, RuntimeService::class.java).setAction(RuntimeService.ACTION_SESSION))
    }

    private fun loadSelected() {
        try {
            val name = if (debugMode) intent.getStringExtra("model") ?: error("Missing model")
                else entries.getOrNull(models.selectedItemPosition)?.id ?: error(UiText.get(R.string.ui_import_a_model_first))
            val config = debugSpec?.getJSONObject("config") ?: JSONObject().put("context", if (debugMode) intent.getIntExtra("context", 4096) else contextInput.text.toString().toInt())
                .put("threads", if (debugMode) intent.getIntExtra("threads", 4) else threadsInput.text.toString().toInt())
                .put("batch", 512).put("micro_batch", 128)
                .put("load_mode", if (debugMode) intent.getStringExtra("loadMode") ?: "read" else "read")
            require(config.getInt("context") in 128..262144 && config.getInt("threads") in 1..64) { UiText.get(R.string.ui_check_context_and_cpu_threads) }
            if (!bound) bound = bindService(Intent(this, RuntimeService::class.java), connection, BIND_AUTO_CREATE)
            startSession()
            val payload = Bundle().apply {
                putString("model", name); putString("config", config.toString())
                if (debugMode) putString("report_id", intent.getStringExtra("runId"))
            }
            if (!send(RuntimeService.LOAD, payload)) pendingLoad = payload
        } catch (failure: Exception) { status.text = failure.message }
    }

    private fun generate() {
        if (!debugMode) {
            intent.removeExtra(AnswerNotifications.EXTRA)
            val preferences = getSharedPreferences("settings", MODE_PRIVATE)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !preferences.getBoolean("asked_notifications", false)) {
                preferences.edit().putBoolean("asked_notifications", true).apply()
                android.app.AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_background_responses))
                    .setMessage(UiText.get(R.string.ui_computation_continues_when_you_minimize_the_app))
                    .setPositiveButton(UiText.get(R.string.ui_allow)) { _, _ -> requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }
                    .setNegativeButton(UiText.get(R.string.ui_later), null).show()
            }
        }
        val clientStarted = SystemClock.elapsedRealtimeNanos()
        try {
            val tokens = debugSpec?.getJSONObject("request")?.getInt("max_tokens") ?: if (debugMode) intent.getIntExtra("tokens", 32) else tokensInput.text.toString().toInt()
            require(tokens in 1..loadedContext) { UiText.get(R.string.ui_response_reserve_must_fit_the_loaded_context) }
            send(RuntimeService.GENERATE, Bundle().apply {
                putLong("client_sent_ns", clientStarted)
                putString("prompt", if (debugMode) intent.getStringExtra("prompt") ?: "Hello" else prompt.text.toString())
                putBoolean("trace", debugMode && intent.getBooleanExtra("trace", false))
                putInt("tokens", tokens); putBoolean("raw", debugMode && intent.getBooleanExtra("raw", false))
                if (debugMode) {
                    putString("report_id", intent.getStringExtra("runId"))
                    debugSpec?.getJSONObject("request")?.let { putString("request", it.toString()) }
                }
            })
        } catch (failure: Exception) { status.text = failure.message }
    }

    private fun utf8Prefix(bytes: ByteArray, complete: Boolean = false): String {
        val chars = CharBuffer.allocate(bytes.size)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        decoder.decode(ByteBuffer.wrap(bytes), chars, complete)
        if (complete) decoder.flush(chars)
        chars.flip()
        return chars.toString()
    }

    private fun readMemoryOutput() {
        if (memoryReadPending || memoryOutputId.isEmpty()) return
        memoryReadPending = send(RuntimeService.READ_OUTPUT, Bundle().apply {
            putString("output_id", memoryOutputId); putInt("offset", memoryOutput.size()); putBoolean("acknowledge", true)
        })
    }

    private fun display(snapshot: JSONObject) {
        snapshot.optJSONObject("config")?.let { loadedContext = it.optInt("context", 4096) }
        state = snapshot.optString("state", "unloaded")
        if (!deadRuntime || state != "unloaded") status.text = snapshot.optString("message")
        if (debugSuppressed) status.append(UiText.get(R.string.ui_old_debug_run_will_not_be_repeated_44d3a4))
        if (state != "unloaded") deadRuntime = false
        if (state == "generating" && snapshot.has("native_phase")) status.append(" (${snapshot.optString("native_phase")})")
        val loaded = snapshot.optJSONObject("load")
        active.text = if (snapshot.optString("model").isEmpty() || state == "unloaded") UiText.get(R.string.ui_active_model_none)
            else UiText.get(R.string.ui_context_cpu_threads_f16_kv, (snapshot.optString("model")), (loaded?.optInt("context") ?: snapshot.optJSONObject("config")?.optInt("context")), (loaded?.optInt("threads") ?: snapshot.optJSONObject("config")?.optInt("threads")))
        val metrics = snapshot.optJSONObject("result")
        if (metrics != null) status.append(UiText.get(R.string.ui_tokens_prefill_ms_decode_ms, (metrics.optInt("generated_tokens")), (metrics.optLong("prefill_us") / 1000), (metrics.optLong("decode_us") / 1000)))
        buttons()
        val filename = snapshot.optString("output_file")
        val epoch = ++renderEpoch
        val requested = intent.getStringExtra(AnswerNotifications.EXTRA)
        if (!requested.isNullOrEmpty() && snapshot.optString("output_id") != requested) {
            memoryOutputId = ""; memoryReadPending = false; output.text = ""
            status.append(UiText.get(R.string.ui_this_temporary_response_is_no_longer_available))
        } else if (snapshot.optString("output_transport") == "memory") {
            val id = snapshot.getString("output_id")
            if (memoryOutputId != id) {
                memoryOutputId = id; memoryOutput = java.io.ByteArrayOutputStream(); memoryReadPending = false
                output.text = ""
            }
            if (snapshot.optLong("output_bytes") > memoryOutput.size()) readMemoryOutput()
            else if (state == "ready") output.text = utf8Prefix(memoryOutput.toByteArray(), true)
        } else if (RuntimeService.validName(filename) && filename.startsWith("output-")) {
            reader.execute {
                val text = try {
                    val file = File(filesDir, filename)
                    if (file.isFile) {
                        val bytes = file.readBytes()
                        val chars = CharBuffer.allocate(bytes.size)
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                            .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(bytes), chars, false)
                        chars.flip(); chars.toString()
                    } else ""
                } catch (_: Exception) { UiText.get(R.string.ui_could_not_read_response) }
                runOnUiThread { if (!isDestroyed && epoch == renderEpoch) output.text = text }
            }
        } else output.text = ""
        if (debugMode) {
            if (!debugStarted && state == "unloaded") { debugStarted = true; loadSelected() }
            else if (debugStarted && !debugGenerated && state == "ready") { debugGenerated = true; generate() }
            else if (debugGenerated && !debugFinished && state == "ready" && (metrics != null || snapshot.has("error_kind"))) {
                debugFinished = true; send(RuntimeService.UNLOAD)
            }
        }
    }

    private fun buttons() {
        val connected = remote != null
        val busy = state in listOf("loading", "generating", "stopping", "unloading")
        load.isEnabled = !busy && !storageBusy && (connected || deadRuntime)
        importButton.isEnabled = !storageBusy && state == "unloaded"
        moveButton.isEnabled = !storageBusy && state == "unloaded" && entries.isNotEmpty()
        generate.isEnabled = connected && state == "ready"
        stop.isEnabled = connected && state in listOf("loading", "generating", "stopping")
        unload.isEnabled = connected && state != "unloaded" && state != "unloading"
        models.isEnabled = !busy && !storageBusy
        contextInput.isEnabled = !busy; threadsInput.isEnabled = !busy
    }

    @Deprecated("Platform Activity result API is sufficient for this dependency-free harness")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) data?.data?.let { ModelStore.import(this, it) }
    }
}
