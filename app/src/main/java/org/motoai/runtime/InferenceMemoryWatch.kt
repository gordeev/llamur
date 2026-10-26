package org.motoai.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Cancels this request under system memory pressure; never changes its input or context. */
internal class InferenceMemoryWatch(context: Context, private val handler: Handler,
                                    testThreshold: Long? = null, private val testThermalStatus: Int? = null, private val cancel: () -> Unit) {
    private val power = context.getSystemService(android.os.PowerManager::class.java)
    private val manager = context.getSystemService(ActivityManager::class.java)
    private val state = AtomicInteger(0) // active / completed / pressure
    private var minimumAvailable = Long.MAX_VALUE
    private var samples = 0
    private val initial = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
    private val threshold = testThreshold?.also {
        require(it in maxOf(512L * 1024 * 1024, initial.threshold * 2)..initial.totalMem)
    } ?: maxOf(512L * 1024 * 1024, initial.threshold * 2)
    val thermal get() = state.get() == 3
    val aborted get() = pressure || thermal
    val pressure get() = state.get() == 2
    private val sample = object : Runnable {
        override fun run() {
            if (state.get() != 0) return
            val memory = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
            minimumAvailable = minOf(minimumAvailable, memory.availMem); samples++
            val thermal = testThermalStatus ?: if (android.os.Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else 0
            if (thermal >= 3 && state.compareAndSet(0, 3)) cancel()
            if ((memory.lowMemory || memory.availMem < threshold) && state.compareAndSet(0, 2)) cancel()
            if (state.get() == 0) handler.postDelayed(this, 1000)
        }
    }
    fun start() = sample.run()
    fun finish() { state.compareAndSet(0, 1); handler.removeCallbacks(sample) }
    // Read on the same main handler that owns sample counters.
    fun report() = JSONObject().put("minimum_available_bytes", if (samples == 0) JSONObject.NULL else minimumAvailable)
        .put("threshold_bytes", threshold).put("poll_interval_ms", 1000).put("samples", samples)
        .put("pressure_abort", pressure).put("thermal_abort", thermal).put("thermal_stop_threshold", "SEVERE")
}
