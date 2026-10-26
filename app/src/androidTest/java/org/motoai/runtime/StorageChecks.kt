package org.motoai.runtime

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real MediaStore/FD/mmap round trips; only own uniquely named fixtures are removed. */
internal object StorageChecks {
    fun run(context: Context): JSONObject {
        check(Build.VERSION.SDK_INT >= 29)
        val directory = File(context.filesDir, "models")
        val id = "storage-check-${UUID.randomUUID()}.gguf"
        val source = File(directory, id)
        val bytes = File(directory, "stories260K.gguf").readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        var uri: Uri? = null
        val config = JSONObject().put("context", 128).put("threads", 2).put("batch", 128).put("micro_batch", 64)
        val request = JSONObject().put("chat", false).put("raw_prompt", "Once upon a time").put("max_tokens", 16)
        fun generate(mode: String): ByteArray {
            ModelStore.open(context, id).use { opened ->
                check(opened.bytes == bytes.size.toLong())
                NativeRuntime.load(opened.path, config.put("load_mode", mode), NativeRuntime.ticket())
                try {
                    val output = ByteArrayOutputStream()
                    NativeRuntime.generate(request, NativeRuntime.ticket(), object : NativeRuntime.Sink {
                        override fun onBytes(bytes: ByteArray) { output.write(bytes) }
                    })
                    return output.toByteArray()
                } finally { NativeRuntime.unload() }
            }
        }
        try {
            source.writeBytes(bytes)
            NativeRuntime.initialize(context)
            android.os.ParcelFileDescriptor.open(source,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use {GgufGuard.validate(it)}
            val truncated=File(directory,"guard-truncated-${UUID.randomUUID()}.gguf")
            try {
                truncated.writeBytes(bytes.copyOf(bytes.size/2));var refused=false
                try {android.os.ParcelFileDescriptor.open(truncated,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use {GgufGuard.validate(it)}}catch(_:Exception){refused=true}
                check(refused) {"Truncated GGUF payload accepted before import registration"}
            }finally{truncated.delete()}
            val reference = generate("read")
            check(reference.contentEquals(generate("mmap")))
            var rejected = false
            try { ModelStore.publishToDownloads(context, id, "0".repeat(64)) }
            catch (_: IllegalArgumentException) { rejected = true }
            check(rejected && source.isFile && !File(directory, "$id.shared.json").exists())
            // Simulate a process interrupted after allocating its own pending row.
            val interrupted = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "interrupted-$id")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/MotoAI/Models")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }) ?: error("No pending fixture")
            File(directory, "$id.pending.json").writeText(JSONObject().put("uri", interrupted.toString()).toString())
            check(File(directory, "$id.pending.json").renameTo(File(directory, "$id.pending.json.bak")))
            val registered = ModelStore.publishToDownloads(context, id, sha)
            uri = Uri.parse(registered.getString("uri"))
            context.contentResolver.query(interrupted, arrayOf(MediaStore.Downloads._ID), null, null, null)?.use {
                check(!it.moveToFirst()) { "Interrupted pending row leaked" }
            }
            check(!source.exists()) { "Persistent duplicate left behind" }
            check(ModelStore.entries(context).single { it.id == id }.location == "Downloads/MotoAI/Models")
            check(File(directory, "$id.shared.json").renameTo(File(directory, "$id.shared.json.bak")))
            check(ModelStore.entries(context).single { it.id == id }.location == "Downloads/MotoAI/Models")
            check(ModelStore.publishToDownloads(context, id, sha).getString("uri") == uri.toString()) { "Duplicate publication" }
            // A crash after the registry commit but before unlinking the staging file.
            source.writeBytes(bytes)
            File(directory, "$id.pending.json").writeText(JSONObject().put("uri", uri.toString()).toString())
            check(ModelStore.publishToDownloads(context, id, sha).getString("uri") == uri.toString())
            check(!source.exists()) { "Interrupted committed move left a duplicate" }
            val beforeImport = directory.listFiles().orEmpty().filter { it.name.endsWith(".gguf") }.map { it.name }.toSet()
            val finished = java.util.concurrent.CountDownLatch(1)
            val started = java.util.concurrent.atomic.AtomicBoolean(false)
            val importStatus = java.util.concurrent.atomic.AtomicReference("")
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            main.post {
                ModelStore.observe { text, busy ->
                    if (busy) started.set(true)
                    if (!busy && started.get()) { importStatus.set(text); finished.countDown() }
                }
                ModelStore.import(context, uri!!)
            }
            check(finished.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "Duplicate import did not finish" }
            main.post { ModelStore.observe(null) }
            check(importStatus.get().contains("Повторная копия не создана")) { importStatus.get() }
            check(directory.listFiles().orEmpty().filter { it.name.endsWith(".gguf") }.map { it.name }.toSet() == beforeImport)
            val descriptorsBefore = File("/proc/self/fd").list()!!.size
            val modes = JSONArray()
            repeat(10) { cycle ->
                val mode = if (cycle % 2 == 0) "read" else "mmap"
                check(reference.contentEquals(generate(mode))) { "Shared FD output changed in $mode" }
                modes.put(mode)
            }
            val descriptorsAfter = File("/proc/self/fd").list()!!.size
            check(descriptorsAfter <= descriptorsBefore + 1) { "Model descriptor leak: $descriptorsBefore -> $descriptorsAfter" }
            context.contentResolver.delete(uri, null, null)
            var missing = false
            try { ModelStore.open(context, id).close() } catch (_: java.io.FileNotFoundException) { missing = true }
            check(missing) { "Removed shared file silently fell back" }
            return JSONObject().put("passed", true).put("modes", modes).put("sha256", sha)
                .put("descriptors_before", descriptorsBefore).put("descriptors_after", descriptorsAfter)
                .put("checks", "private/shared FD read+mmap output identity; SHA mismatch preserves source; interrupted pending recovery; one canonical file; idempotent publication; repeated import creates no new copy; deleted file fails explicitly")
        } finally {
            NativeRuntime.unload()
            uri?.let { context.contentResolver.delete(it, null, null) }
            source.delete(); File(directory, "$id.shared.json").delete(); File(directory, "$id.pending.json").delete()
        }
    }
}
