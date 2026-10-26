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

/** Public-fixture endurance only: one model load, ordinary complete requests, no forced EOS. */
internal class SustainedChecks(private val test: Instrumentation) {
    fun run(spec: JSONObject, seconds: Int, id: String): JSONObject {
        require(seconds in 900..3600 && id.matches(Regex("sustained-[a-zA-Z0-9_-]{1,64}")))
        val context = test.targetContext
        val events = LinkedBlockingQueue<JSONObject>()
        val remote = AtomicReference<Messenger?>()
        val connected = CountDownLatch(1)
        val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == RuntimeService.EVENT) events.offer(JSONObject(message.data.getString("snapshot")!!))
            true
        })
        fun send(command: Int, data: Bundle = Bundle()) {
            checkNotNull(remote.get()).send(Message.obtain(null, command).apply { this.data = data; replyTo = incoming })
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote.set(Messenger(binder)); send(RuntimeService.REGISTER); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote.set(null) }
        }
        fun await(timeout: Long, condition: (JSONObject) -> Boolean): JSONObject {
            val deadline = SystemClock.elapsedRealtime() + timeout * 1000
            while (SystemClock.elapsedRealtime() < deadline) {
                checkNotNull(remote.get()) { "Inference process disconnected" }
                val event = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
                check(!event.has("error_kind")) { "Service error: $event" }
                if (condition(event)) return event
            }
            error("Sustained service timeout")
        }
        fun telemetry(): JSONObject {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val power = context.getSystemService(PowerManager::class.java)
            val manager = context.getSystemService(BatteryManager::class.java)
            val policies = JSONObject()
            File("/sys/devices/system/cpu/cpufreq").listFiles()?.sortedBy { it.name }?.forEach { policy ->
                if (policy.name.startsWith("policy")) policies.put(policy.name, JSONObject().apply {
                    for (key in listOf("related_cpus", "scaling_max_freq", "scaling_cur_freq"))
                        put(key, runCatching { File(policy, key).readText().trim() }.getOrElse { "unavailable" })
                })
            }
            return JSONObject().put("at_ns", SystemClock.elapsedRealtimeNanos())
                .put("battery_level", battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
                .put("battery_scale", battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
                .put("battery_status", battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
                .put("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
                .put("temperature_tenths_c", battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1))
                .put("charge_counter_uah", manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
                .put("current_now_ua", manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
                .put("interactive", power.isInteractive)
                .put("thermal_status", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else JSONObject.NULL)
                .put("policies", policies)
        }
        val file = File(context.filesDir, "$id.jsonl")
        check(!file.exists()) { "Refusing to overwrite an endurance artifact" }
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        check(context.bindService(Intent(context, RuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        check(connected.await(15, TimeUnit.SECONDS))
        try {
            await(15) { it.optString("state") == "unloaded" }
            events.clear()
            context.startForegroundService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.ACTION_SESSION))
            send(RuntimeService.LOAD, Bundle().apply {
                putString("model", "Ornith-1.5-9B-Q4_K_M.gguf"); putString("config", spec.getJSONObject("config").toString())
            })
            val loaded = await(90) { it.optString("state") == "ready" }
            val pid = loaded.getInt("pid")
            file.appendText(JSONObject().put("kind", "load").put("snapshot", loaded).put("telemetry", telemetry()).toString() + "\n")
            var measuredStart = 0L
            var count = 0
            // Exactly one excluded warmup; timed requests follow without unloading or cooling.
            while (measuredStart == 0L || SystemClock.elapsedRealtimeNanos() - measuredStart < seconds * 1_000_000_000L) {
                val warmup = measuredStart == 0L
                val requestId = "$id-${if (warmup) "warmup" else count.toString()}"
                val before = telemetry()
                val level = before.getInt("battery_level")
                val scale = before.getInt("battery_scale")
                check(level >= 0 && scale > 0 && level * 100L >= scale * 10L) { "Battery below running threshold" }
                val started = SystemClock.elapsedRealtimeNanos()
                events.clear()
                send(RuntimeService.GENERATE, Bundle().apply {
                    putString("request", spec.getJSONObject("request").toString()); putString("report_id", requestId)
                    putLong("client_sent_ns", started)
                })
                val completed = await(180) { it.optString("state") == "ready" && it.has("result") }
                check(completed.getInt("pid") == pid)
                val raw = File(context.filesDir, "service-result-$requestId.json")
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (!raw.exists() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
                val result = JSONObject(raw.readText())
                check(result.getBoolean("passed"))
                val ended = SystemClock.elapsedRealtimeNanos()
                file.appendText(JSONObject().put("kind", "request").put("warmup", warmup).put("index", count)
                    .put("started_ns", started).put("ended_ns", ended).put("before", before).put("after", telemetry())
                    .put("snapshot", result).toString() + "\n")
                // Only this run's public-fixture scratch. Full observations are now in its JSONL.
                File(context.filesDir, result.getString("output_file")).delete()
                raw.delete(); File(context.filesDir, "service-progress-$requestId.json").delete()
                if (warmup) measuredStart = SystemClock.elapsedRealtimeNanos() else count++
            }
            send(RuntimeService.UNLOAD)
            await(30) { it.optString("state") == "unloaded" }
            return JSONObject().put("passed", true).put("requests", count).put("pid", pid)
                .put("measured_start_ns", measuredStart).put("seconds", seconds).put("artifact", file.name)
        } finally {
            runCatching { send(RuntimeService.UNLOAD); send(RuntimeService.UNREGISTER) }
            context.unbindService(connection)
            test.runOnMainSync { activity.finish() }
        }
    }
}
