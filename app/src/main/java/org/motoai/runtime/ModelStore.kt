package org.motoai.runtime

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.content.ContentValues
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Imports are streamed and survive Activity recreation. An interrupted copy is never listed as GGUF. */
internal object ModelStore {
    data class Entry(val id: String, val label: String, val bytes: Long, val location: String, val sha256: String?, val external: Boolean = false)
    class OpenModel(val descriptor: ParcelFileDescriptor, val location: String) : java.io.Closeable {
        val path get() = "/proc/self/fd/${descriptor.fd}"
        val bytes get() = descriptor.statSize
        override fun close() = descriptor.close()
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private var verifiedSource: String? = null
    private var verifiedSourceSha: String? = null
    private var verifiedSourceLabel = ""
    fun pendingDeletionSource() = verifiedSource?.let { Uri.parse(it) }
    fun clearDeletionOffer() { verifiedSource = null; verifiedSourceSha = null; verifiedSourceLabel = "" }
    fun deletionLabel(uri:Uri) = verifiedSourceLabel.takeIf {verifiedImport(uri)}?:UiText.get(R.string.ui_selected_document)
    fun deletionDigest(uri: Uri) = verifiedSourceSha.takeIf { verifiedImport(uri) }
    fun deleteVerifiedOriginal(context: Context, uri: Uri, sha: String) {
        val digest=MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use {input->val buffer=ByteArray(1024*1024)
            while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
        }?:error(UiText.get(R.string.ui_source_no_longer_available))
        require(digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}==sha) { UiText.get(R.string.ui_source_changed_after_import_it_was_kept) }
        check(android.provider.DocumentsContract.deleteDocument(context.contentResolver,uri)) {UiText.get(R.string.ui_document_provider_did_not_allow_deletion)}
    }
    fun verifiedImport(uri: Uri) = verifiedSource == uri.toString()
    private var listener: ((String, Boolean) -> Unit)? = null
    private var running = false
    private var status = UiText.get(R.string.ui_models_can_be_moved_to_downloads_llamur)

    fun startExternal() { check(!running) { UiText.get(R.string.ui_a_model_operation_is_already_running) }; cancelled.set(false); update(UiText.get(R.string.ui_preparing_download), true) }
    fun finishExternal() { update(status, false) }
    fun safeToOfferSourceDeletion(context: Context, uri: Uri): Boolean {
        if (!verifiedImport(uri)) return false
        if (entries(context).any { sharedMetadata(context, it.id)?.optString("uri") == uri.toString() }) return false
        val document = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrDefault("")
        if (document.substringAfter(':').startsWith("Download/MotoAI/Models/", true) || document.substringAfter(':').startsWith("Downloads/MotoAI/Models/", true)) return false // Preserve original canonical files during rename.
        if (document.substringAfter(':').startsWith("Download/llamur/Models/", true) || document.substringAfter(':').startsWith("Downloads/llamur/Models/", true)) return false
        return runCatching { context.contentResolver.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
            it.moveToFirst() && (it.getLong(0) and android.provider.DocumentsContract.Document.FLAG_SUPPORTS_DELETE.toLong()) != 0L
        } ?: false }.getOrDefault(false)
    }
    fun transferState() = status to running
    fun externalStatus(text: String) { status = text; listener?.invoke(status, running) }
    fun observe(callback: ((String, Boolean) -> Unit)?) {
        listener = callback
        callback?.invoke(status, running)
    }

    private fun update(text: String, busy: Boolean = running) {
        status = text; running = busy; listener?.invoke(status, running)
    }

    fun entries(context: Context): List<Entry> {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val ids = dir.listFiles().orEmpty().filter { it.isFile }.mapNotNull {
            when {
                it.name.endsWith(".gguf") -> it.name
                it.name.endsWith(".gguf.shared.json") -> it.name.removeSuffix(".shared.json")
                it.name.endsWith(".gguf.shared.json.bak") -> it.name.removeSuffix(".shared.json.bak")
                else -> null
            }
        }.distinct()
        return ids.map { id ->
            val shared = sharedMetadata(context, id)
            val metadata = File(dir, "$id.json")
            val info = runCatching { JSONObject(metadata.readText()) }.getOrNull()
            val label = info?.optString("name") ?: id
            val bytes = shared?.getLong("bytes") ?: File(dir, id).length()
            val external=shared?.optString("kind")=="external_saf"
            val location = if(external) UiText.get(R.string.ui_saf_no_copy) else if (shared != null) "Downloads/llamur/Models" else UiText.get(R.string.ui_internal_storage)
            val fixture = if (bytes == 1185376L && (id.contains("stories") || id.startsWith("270cba1b"))) UiText.get(R.string.ui_test_model) else ""
            Entry(id, "$fixture$label (${android.text.format.Formatter.formatShortFileSize(context, bytes)}; $location)", bytes, location,
                (shared?.optString("sha256") ?: info?.optString("sha256"))?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }, external)
        }.sortedBy { it.label }
    }

    private fun sharedMetadata(context: Context, id: String): JSONObject? {
        val file = AtomicFile(File(context.filesDir, "models/$id.shared.json"))
        return if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
            file.openRead().use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) } else null
    }

    fun open(context: Context, id: String): OpenModel {
        require(RuntimeService.validName(id) && id.endsWith(".gguf")) { UiText.get(R.string.ui_invalid_model_file) }
        val shared = sharedMetadata(context, id)
        val descriptor = if (shared != null) context.contentResolver.openFileDescriptor(Uri.parse(shared.getString("uri")), "r")
            ?: error(UiText.get(R.string.ui_could_not_open_model_in_downloads_llamur))
        else ParcelFileDescriptor.open(File(context.filesDir, "models/$id"), ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            require(OsConstants.S_ISREG(Os.fstat(descriptor.fileDescriptor).st_mode) && descriptor.statSize > 0) { UiText.get(R.string.ui_model_must_be_a_regular_file) }
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
            if (shared != null) require(descriptor.statSize == shared.getLong("bytes")) { UiText.get(R.string.ui_model_size_changed_check_the_file) }
            return OpenModel(descriptor, if(shared?.optString("kind")=="external_saf") "SAF" else if (shared != null) "Downloads/llamur/Models" else "private")
        } catch (failure: Throwable) { descriptor.close(); throw failure }
    }

    internal fun atomicJson(file: File, value: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(value.toString(2).toByteArray()); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }

    internal fun verifyOpened(opened: OpenModel, expected: String?, cancelled: () -> Boolean): String {
        val before=Os.fstat(opened.descriptor.fileDescriptor)
        Os.lseek(opened.descriptor.fileDescriptor,0,OsConstants.SEEK_SET)
        val digest=MessageDigest.getInstance("SHA-256")
        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(opened.descriptor.fileDescriptor)).use {input->
            val buffer=ByteArray(1024*1024)
            while(true) {
                if(cancelled()) throw NativeRuntime.Failure("cancelled",UiText.get(R.string.ui_file_verification_cancelled))
                val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)
            }
        }
        val after=Os.fstat(opened.descriptor.fileDescriptor)
        require(before.st_size==after.st_size && before.st_mtime==after.st_mtime) {UiText.get(R.string.ui_model_changed_during_verification)}
        val sha=digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
        require(expected==null || expected==sha) {UiText.get(R.string.ui_model_sha_256_changed_file_kept_import)}
        Os.lseek(opened.descriptor.fileDescriptor,0,OsConstants.SEEK_SET)
        return sha
    }
    private fun modelDigest(context: Context, id: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
        open(context, id).use { opened ->
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(opened.descriptor.fileDescriptor)).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) { check(!cancelled.get()) { UiText.get(R.string.ui_verification_cancelled) }; val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Moves only an app-owned internal copy. Never removes a user-selected SAF source. */
    fun publishToDownloads(context: Context, id: String, expectedSha: String? = null): JSONObject {
        if(Build.VERSION.SDK_INT < 29) throw UnsupportedOperationException(UiText.get(R.string.ui_downloads_llamur_requires_android_10_use_import))
        require(RuntimeService.validName(id) && id.endsWith(".gguf"))
        val directory = File(context.filesDir, "models")
        val registry = File(directory, "$id.shared.json")
        val source = File(directory, id)
        val pending = File(directory, "$id.pending.json")
        // Recover an interrupted transaction. A committed URI is never deleted.
        if (pending.exists() || File(pending.path + ".bak").exists()) {
            val transaction = AtomicFile(pending)
            val interrupted = transaction.openRead().use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
            if (sharedMetadata(context, id)?.optString("uri") != interrupted.getString("uri"))
                context.contentResolver.delete(Uri.parse(interrupted.getString("uri")), null, null)
            transaction.delete()
            check(!pending.exists()) { UiText.get(R.string.ui_could_not_remove_incomplete_transfer_entry) }
        }
        sharedMetadata(context, id)?.let { existing ->
            require(existing.optString("kind")!="external_saf") {UiText.get(R.string.ui_file_connected_without_copying_choose_import_for)}
            open(context, id).use { require(it.bytes == existing.getLong("bytes")) }
            if (expectedSha != null) require(existing.getString("sha256") == expectedSha)
            val verified = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(Uri.parse(existing.getString("uri")))!!.use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; verified.update(buffer, 0, n) }
            }
            require(verified.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == existing.getString("sha256")) { UiText.get(R.string.ui_downloads_file_sha_256_changed) }
            if (source.isFile) {
                val original = MessageDigest.getInstance("SHA-256")
                source.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; original.update(buffer, 0, n) }
                }
                require(original.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == existing.getString("sha256")) { UiText.get(R.string.ui_internal_copy_changed_it_was_kept) }
                check(source.delete()) { UiText.get(R.string.ui_could_not_delete_internal_copy) }
            }
            return existing
        }
        require(source.isFile) { UiText.get(R.string.ui_internal_model_copy_not_found) }
        val bytes = source.length()
        require(android.os.StatFs(Environment.getExternalStorageDirectory().path).availableBytes > bytes + 256L * 1024 * 1024) {
            UiText.get(R.string.ui_safe_transfer_temporarily_requires_mib_free_plus, (bytes / (1024 * 1024)))
        }
        val modified = source.lastModified()
        val metadata = runCatching { JSONObject(File(directory, "$id.json").readText()) }.getOrNull()
        val displayName = (metadata?.optString("name") ?: id).let {
            if (RuntimeService.validName(it) && it.endsWith(".gguf")) it else id
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/llamur/Models")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error(UiText.get(R.string.ui_could_not_create_file_in_downloads_llamur))
        var committed = false
        try {
            atomicJson(pending, JSONObject().put("uri", uri.toString()))
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val descriptor = context.contentResolver.openFileDescriptor(uri, "rw") ?: error(UiText.get(R.string.ui_could_not_open_new_file))
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        check(!cancelled.get()) { UiText.get(R.string.ui_transfer_cancelled) }
                        val n = input.read(buffer); if (n < 0) break
                        output.write(buffer, 0, n); digest.update(buffer, 0, n); total += n
                    }
                }
                output.fd.sync()
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(total == bytes && source.length() == bytes && source.lastModified() == modified) { UiText.get(R.string.ui_source_model_changed_during_transfer) }
            require(expectedSha == null || expectedSha == sha) { UiText.get(R.string.ui_sha_256_does_not_match_pinned_model) }
            require(metadata?.optString("sha256")?.takeIf { it.isNotEmpty() }?.let { it == sha } != false) { UiText.get(R.string.ui_internal_copy_sha_256_changed) }
            // Re-read the completed destination before discarding our internal copy.
            val verified = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) { check(!cancelled.get()) { UiText.get(R.string.ui_transfer_cancelled) }; val n = input.read(buffer); if (n < 0) break; verified.update(buffer, 0, n) }
            } ?: error(UiText.get(R.string.ui_could_not_verify_new_file))
            require(verified.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == sha) { UiText.get(R.string.ui_new_copy_failed_sha_256_verification) }
            check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
            val result = JSONObject().put("uri", uri.toString()).put("bytes", bytes).put("sha256", sha)
                .put("display_name", displayName).put("location", "Downloads/llamur/Models")
            // Diagnostic ADB integrity checks only; runtime always opens the URI via PFD.
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) result.put("diagnostic_file_path", it.getString(0))
            }
            atomicJson(registry, result)
            committed = true
            // Only this verified app-private staging copy, never an import's SAF original.
            check(source.delete()) { UiText.get(R.string.ui_file_is_available_in_downloads_could_not) }
            check(pending.delete())
            return result
        } finally {
            if (!committed) { context.contentResolver.delete(uri, null, null); pending.delete() }
        }
    }

    fun move(context: Context, id: String) {
        if (running) return
        cancelled.set(false); update(UiText.get(R.string.ui_moving_to_downloads_llamur_and_checking_sha), true)
        val app = context.applicationContext
        worker.execute {
            val message = try { publishToDownloads(app, id); UiText.get(R.string.ui_done_downloads_llamur_models_internal_copy_deleted) }
                catch (failure: Throwable) { failure.message ?: UiText.get(R.string.ui_could_not_move_model) }
            main.post { update(message, false) }
        }
    }

    fun cancel() { cancelled.set(true) }

    internal fun deleteOwned(context: Context, id: String) {
        require(RuntimeService.validName(id) && id.endsWith(".gguf"))
        require(entries(context).any {it.id==id}) {UiText.get(R.string.ui_model_not_found)}
        val shared=sharedMetadata(context,id)
        if(shared!=null) {
            require(shared.optString("kind")!="external_saf") {UiText.get(R.string.ui_external_files_can_be_disconnected_source_deletion)}
            check(context.contentResolver.delete(Uri.parse(shared.getString("uri")),null,null)>0) {UiText.get(R.string.ui_could_not_delete_model_in_downloads)}
        } else check(File(context.filesDir,"models/$id").delete()) {UiText.get(R.string.ui_could_not_delete_model)}
        listOf("$id.json","$id.shared.json","$id.shared.json.bak").forEach {File(context.filesDir,"models/$it").delete()}
    }
    fun partials(context: Context): List<Pair<String,Long>> = File(context.filesDir,"models").listFiles().orEmpty()
        .filter {it.name.startsWith(".hf-") && (it.name.endsWith(".transfer.json") || it.name.endsWith(".transfer.json.bak"))}
        .map {it.name.removePrefix(".").removeSuffix(".bak").removeSuffix(".transfer.json")}.distinct().map {key->
            val pending=PendingModel.resume(context,key)!!
            key to runCatching {pending.length()}.getOrDefault(0L)
        }
    fun discardPartial(context: Context, key: String) {
        check(!running) {UiText.get(R.string.ui_cancel_the_download_and_wait_for_it)}
        require(key.startsWith("hf-"));PendingModel.resume(context,key)?.discard()
    }
    internal fun registeredUri(context: Context, uri: Uri): Boolean = entries(context).any { sharedMetadata(context,it.id)?.optString("uri") == uri.toString() }
    internal fun verifiedExisting(context: Context, sha: String, bytes: Long): Entry? {
        val entry=entries(context).firstOrNull {it.sha256==sha && it.bytes==bytes}?:return null
        return try {require(modelDigest(context,entry.id)==sha) {UiText.get(R.string.ui_existing_model_failed_sha_256_verification)};entry}
        catch(_:java.io.FileNotFoundException){null}catch(_:SecurityException){null}
    }
    internal fun forgetExternal(context:Context,id:String) {
        require(RuntimeService.validName(id));val shared=sharedMetadata(context,id)?:error(UiText.get(R.string.ui_this_is_not_an_external_connection))
        require(shared.optString("kind")=="external_saf") {UiText.get(R.string.ui_delete_managed_models_using_storage_manager)}
        val uri=Uri.parse(shared.getString("uri"))
        listOf("$id.json","$id.shared.json","$id.shared.json.bak").forEach {File(context.filesDir,"models/$it").delete()}
        if(!registeredUri(context,uri))runCatching {context.contentResolver.releasePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)}
    }
    fun attachReference(context:Context,uri:Uri) {
        if(running)return
        cancelled.set(false);clearDeletionOffer();update(UiText.get(R.string.ui_checking_external_gguf_without_copying),true)
        val app=context.applicationContext
        worker.execute {
            try {
                val persisted=app.contentResolver.persistedUriPermissions.any {it.uri==uri && it.isReadPermission}
                // Own MediaStore sources are used only by the explicit framework test.
                val owned=if(Build.VERSION.SDK_INT>=29)runCatching {app.contentResolver.query(uri,arrayOf(android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME),null,null,null)?.use {it.moveToFirst() && it.getString(0)==app.packageName}?:false}.getOrDefault(false)else false
                require(persisted || owned) {UiText.get(R.string.ui_provider_did_not_grant_persistent_access_use)}
                val fd=app.contentResolver.openFileDescriptor(uri,"r")?:error(UiText.get(R.string.ui_document_unavailable))
                val opened=OpenModel(fd,"SAF")
                opened.use {
                    require(OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode) && fd.statSize in 24..64L*1024*1024*1024) {UiText.get(R.string.ui_a_regular_seekable_gguf_is_required)}
                    val sha=verifyOpened(it,null) {cancelled.get()};GgufGuard.validate(fd)
                    val existing=verifiedExisting(app,sha,it.bytes)
                    if(existing==null) {
                        val id="$sha.gguf";var label=UiText.get(R.string.ui_gguf_without_copying)
                        app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {c->if(c.moveToFirst())label=c.getString(0).take(200)}
                        atomicJson(File(app.filesDir,"models/$id.json"),JSONObject().put("name",label).put("sha256",sha).put("bytes",it.bytes).put("source","User-selected persistable SAF reference; no copy"))
                        atomicJson(File(app.filesDir,"models/$id.shared.json"),JSONObject().put("uri",uri.toString()).put("kind","external_saf").put("sha256",sha).put("bytes",it.bytes))
                    }
                }
                main.post {update(UiText.get(R.string.ui_file_connected_without_copying_do_not_delete),false)}
            }catch(failure:Exception){main.post {update(failure.message?:UiText.get(R.string.ui_could_not_connect_gguf),false)}}
        }
    }
    fun import(context: Context, uri: Uri) {
        if (running) return
        cancelled.set(false); verifiedSource = null; verifiedSourceSha = null
        update(UiText.get(R.string.ui_preparing_import), true)
        val app = context.applicationContext
        worker.execute {
            var target: PendingModel? = null
            try {
                PendingModel.cleanupInterruptedImports(app)
                var name = "Imported.gguf"; var expected = -1L
                app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { cursor ->
                    if(cursor.moveToFirst()) {
                        val n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);val size=cursor.getColumnIndex(OpenableColumns.SIZE)
                        if(n>=0 && !cursor.isNull(n))name=cursor.getString(n).take(200)
                        if(size>=0 && !cursor.isNull(size))expected=cursor.getLong(size)
                    }
                }
                require(!Regex(".*-[0-9]{5}-of-[0-9]{5}.*").matches(name)) {UiText.get(R.string.ui_split_gguf_is_not_supported_yet_choose)}
                val space=if(Build.VERSION.SDK_INT>=29) File(Environment.getExternalStorageDirectory().path) else app.filesDir
                val reserve=256L*1024*1024
                if(expected>=0)require(expected<=space.usableSpace-reserve) {UiText.get(R.string.ui_not_enough_space_for_an_independent_model)}
                val pending=PendingModel.create(app,name);target=pending
                val digest=MessageDigest.getInstance("SHA-256");var total=0L;var last=0L
                app.contentResolver.openInputStream(uri)?.use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(pending.descriptor(true)).use { output ->
                        val buffer=ByteArray(1024*1024)
                        while(true) {
                            check(!cancelled.get()) {UiText.get(R.string.ui_import_cancelled)};val n=input.read(buffer);if(n<0)break
                            require(n<=64L*1024*1024*1024-total) {UiText.get(R.string.ui_model_exceeds_64_gib)}
                            require(space.usableSpace>reserve+n) {UiText.get(R.string.ui_not_enough_free_space)}
                            output.write(buffer,0,n);digest.update(buffer,0,n);total+=n
                            val now=android.os.SystemClock.elapsedRealtime()
                            if(now-last>=500) {last=now;val copied=total;main.post {update(UiText.get(R.string.ui_import_mib, (copied/(1024*1024)))+if(expected>0)UiText.get(R.string.ui_mib, (expected/(1024*1024))) else "")}}
                        };output.fd.sync()
                    }
                } ?: error(UiText.get(R.string.ui_could_not_open_document))
                require(expected<0 || expected==total) {UiText.get(R.string.ui_source_size_changed_during_import)}
                val sha=digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
                main.post {update(UiText.get(R.string.ui_re_reading_and_verifying_gguf_copy_sha))}
                pending.verify(total,sha) {cancelled.get()}
                val existing=verifiedExisting(app,sha,total)
                if(existing!=null && !existing.external) {
                    pending.discard();target=null;verifiedSource=uri.toString();verifiedSourceSha=sha;verifiedSourceLabel="$name · ${android.text.format.Formatter.formatFileSize(app,total)} · ${uri.authority}";verifiedSourceLabel="$name · ${android.text.format.Formatter.formatFileSize(app,total)} · ${uri.authority}"
                    main.post {update(UiText.get(R.string.ui_model_already_exists_no_duplicate_copy_created, (existing.location)),false)}
                } else {
                    pending.commit(name,total,sha,JSONObject().put("source","SAF independent copy; local SHA-256, publisher unverified"));target=null
                    verifiedSource=uri.toString();verifiedSourceSha=sha;verifiedSourceLabel="$name · ${android.text.format.Formatter.formatFileSize(app,total)} · ${uri.authority}"
                    main.post {update(UiText.get(R.string.ui_import_verified_sha_256, (if(Build.VERSION.SDK_INT>=29)"Downloads/llamur/Models" else UiText.get(R.string.ui_internal_storage)), (sha)),false)}
                }
            }catch(failure:Throwable) {
                runCatching {target?.discard()}
                main.post {update(failure.message?:UiText.get(R.string.ui_import_error),false)}
            }
        }
    }
}
