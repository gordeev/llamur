package org.motoai.runtime

import android.os.Handler
import android.os.PowerManager

/** Timed leases renewed only while an explicit native operation is active. */
internal class InferenceWakeLease(manager: PowerManager, private val handler: Handler,
    private val timeoutMs: Long = 30 * 60 * 1000L, private val renewMs: Long = 10 * 60 * 1000L) : AutoCloseable {
    private val lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "llamur:inference")
    private var closed = false
    private val renew = object : Runnable {
        override fun run() {
            synchronized(this@InferenceWakeLease) {
                if (closed) return
                // A fresh timed hold avoids depending on repeated-acquire timeout
                // semantics. No hold survives completion, Stop or service destruction.
                if (lock.isHeld) lock.release()
                lock.acquire(timeoutMs)
                handler.postDelayed(this, renewMs)
            }
        }
    }
    init {
        require(renewMs > 0 && timeoutMs > renewMs)
        lock.setReferenceCounted(false)
        lock.acquire(timeoutMs)
        handler.postDelayed(renew, renewMs)
    }
    @Synchronized fun isHeld() = !closed && lock.isHeld
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(renew)
        if (lock.isHeld) lock.release()
    }
}
