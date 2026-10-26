package org.motoai.runtime

import android.app.Instrumentation
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal object EphemeralChecks {
    fun run(test: Instrumentation): JSONObject {
        val expected = ("Привет 🌍\n".repeat(12000)).toByteArray(Charsets.UTF_8)
        val output = EphemeralOutput("public-fixture", expected.size)
        var offset = 0
        while (offset < expected.size) {
            val count = minOf(1237, expected.size - offset)
            output.append(expected.copyOfRange(offset, offset + count)); offset += count
        }
        val read = ByteArrayOutputStream()
        while (read.size() < expected.size) read.write(output.read(read.size()))
        check(expected.contentEquals(read.toByteArray()))
        check(output.read(4093, 27).contentEquals(expected.copyOfRange(4093, 4120)))
        check(output.read(output.size()).isEmpty())
        var rejected = false
        try { output.append(byteArrayOf(1)) } catch (_: IllegalArgumentException) { rejected = true }
        check(rejected && output.size() == expected.size)
        for (position in listOf(-1, expected.size + 1)) {
            rejected = false
            try { output.read(position) } catch (_: IllegalArgumentException) { rejected = true }
            check(rejected)
        }
        val lease = InferenceWakeLease(test.targetContext.getSystemService(PowerManager::class.java), Handler(Looper.getMainLooper()), 1000, 200)
        try { SystemClock.sleep(1300); check(lease.isHeld()) }
        finally { lease.close(); lease.close() }
        check(!lease.isHeld())
        val thermalCancelled = java.util.concurrent.CountDownLatch(1)
        lateinit var thermalWatch: InferenceMemoryWatch
        test.runOnMainSync {
            thermalWatch = InferenceMemoryWatch(test.targetContext, Handler(Looper.getMainLooper()), testThermalStatus = 3) { thermalCancelled.countDown() }
            thermalWatch.start()
        }
        check(thermalCancelled.await(2, java.util.concurrent.TimeUnit.SECONDS) && thermalWatch.thermal && !thermalWatch.pressure)
        test.runOnMainSync { thermalWatch.finish() }
        return JSONObject().put("passed", true).put("bytes", expected.size)
            .put("checks", "Paged byte-exact UTF8 transport across page/read boundaries; atomic overflow; bounds; renewed timed wake hold beyond initial timeout; idempotent release")
    }
}
