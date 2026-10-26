package org.motoai.runtime

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import android.system.Os
import android.system.OsConstants

/** A benchmark intent is one-shot, including after Activity/process/system recreation. */
internal object DebugRuns {
    fun claim(context: Context, id: String?): Boolean {
        if (id == null || !id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) return false
        // Also recognize attempts made by older APKs, before markers existed.
        if (listOf("service-progress", "service-result", "native-result").any { prefix ->
                listOf("", ".bak", ".new").any { suffix -> File(context.filesDir, "$prefix-$id.json$suffix").exists() }
            }) return false
        val directory = File(context.filesDir, "bench").apply { mkdirs() }
        val marker = File(directory, "started-$id.marker")
        return runCatching {
            if (!marker.createNewFile()) return false
            FileOutputStream(marker).use { it.fd.sync() }
            val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try {
                check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode))
                Os.fsync(descriptor)
            } finally { Os.close(descriptor) }
            true
        }.getOrDefault(false) // Fail closed: an uncertain marker must never start inference.
    }
}
