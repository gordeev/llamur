package org.motoai.runtime

import android.os.ParcelFileDescriptor
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Bounded framed RAM pipes; length+SHA rejects a writer dying mid-transfer. No temporary files. */
internal object ProductPipe {
    private val writers = Executors.newFixedThreadPool(2)
    private val readers = Executors.newFixedThreadPool(2)
    fun write(bytes: ByteArray): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        writers.execute {
            try { DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])).use { output ->
                output.writeInt(bytes.size); output.write(MessageDigest.getInstance("SHA-256").digest(bytes)); output.write(bytes)
            } } catch (_: Exception) { runCatching { pipe[1].close() } }
        }
        return pipe[0]
    }
    fun read(fd: ParcelFileDescriptor, limit: Int, done: (Result<ByteArray>) -> Unit) {
        readers.execute { done(runCatching { DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(fd)).use { input ->
            val length = input.readInt(); require(length in 0..limit) { UiText.get(R.string.ui_message_exceeds_transport_budget) }
            val sha = ByteArray(32); input.readFully(sha)
            val bytes = ByteArray(length); input.readFully(bytes)
            require(input.read() == -1 && MessageDigest.isEqual(sha, MessageDigest.getInstance("SHA-256").digest(bytes))) { UiText.get(R.string.ui_transport_message_truncated_or_damaged) }
            bytes
        } }) }
    }
}
