package org.motoai.runtime

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** One staged canonical file, never a private+Downloads pair. Registry commit is recoverable. */
internal class PendingModel private constructor(private val context: Context, val journal: File, val record: JSONObject) {
    val uri: Uri? get() = record.optString("uri").takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
    private val privateFile get() = File(context.filesDir, "models/" + record.getString("private"))
    fun descriptor(write: Boolean = false): ParcelFileDescriptor = uri?.let {
        context.contentResolver.openFileDescriptor(it, if (write) "rw" else "r") ?: error(UiText.get(R.string.ui_incomplete_model_is_inaccessible))
    } ?: ParcelFileDescriptor.open(privateFile, if (write) ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE else ParcelFileDescriptor.MODE_READ_ONLY)
    fun length() = descriptor().use { it.statSize }
    fun checkpoint() = ModelStore.atomicJson(journal, record)
    fun verify(expectedBytes: Long?, expectedSha: String?, cancelled: () -> Boolean): Pair<Long,String> {
        val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
        ParcelFileDescriptor.AutoCloseInputStream(descriptor()).use { input ->
            val header = ByteArray(24); var offset = 0
            while (offset < header.size) { val n=input.read(header,offset,header.size-offset);require(n>0) {UiText.get(R.string.ui_gguf_truncated)};offset+=n }
            require(header.copyOfRange(0,4).contentEquals(byteArrayOf(71,71,85,70)) && header[4].toInt() in 2..3 && header.slice(5..7).all { it.toInt()==0 }) { UiText.get(R.string.ui_single_gguf_v2_v3_supported) }
            digest.update(header);total=24
            val buffer=ByteArray(1024*1024)
            while(true) { check(!cancelled()) {UiText.get(R.string.ui_verification_cancelled)};val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n);total+=n }
        }
        val sha=digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
        require(expectedBytes==null || total==expectedBytes) {UiText.get(R.string.ui_file_size_does_not_match)}
        require(expectedSha==null || sha==expectedSha) {UiText.get(R.string.ui_sha_256_mismatch)}
        descriptor().use { GgufGuard.validate(it) }
        return total to sha
    }
    fun commit(label:String,bytes:Long,sha:String,provenance:JSONObject):String {
        val id="$sha.gguf";val dir=File(context.filesDir,"models")
        val metadata=JSONObject(provenance.toString()).put("name",label).put("bytes",bytes).put("sha256",sha)
        if(uri!=null) {
            val registered=JSONObject().put("uri",uri.toString()).put("bytes",bytes).put("sha256",sha).put("display_name",label).put("location","Downloads/llamur/Models")
            // Keep the journal until both publication and the atomic registry exist.
            check(context.contentResolver.update(uri!!,ContentValues().apply {put(MediaStore.Downloads.IS_PENDING,0)},null,null)==1)
            ModelStore.atomicJson(File(dir,"$id.json"),metadata)
            ModelStore.atomicJson(File(dir,"$id.shared.json"),registered)
        } else {
            check(privateFile.renameTo(File(dir,id))) {UiText.get(R.string.ui_could_not_finish_import)}
            ModelStore.atomicJson(File(dir,"$id.json"),metadata)
        }
        check(journal.delete()) {UiText.get(R.string.ui_model_ready_could_not_remove_operation_journal)}
        return id
    }
    fun discard() {
        // A committed target is never removed during journal recovery.
        val owned=uri
        val registered=owned!=null && ModelStore.registeredUri(context,owned)
        if(!registered) { if(owned!=null) context.contentResolver.delete(owned,null,null) else privateFile.delete() }
        journal.delete();File(journal.path+".bak").delete()
    }
    companion object {
        fun create(context:Context,label:String,key:String="import-${UUID.randomUUID()}"):PendingModel {
            require(key.matches(Regex("[a-z0-9-]{1,100}")))
            val dir=File(context.filesDir,"models").apply {mkdirs()};val journal=File(dir,".$key.transfer.json")
            require(!journal.exists() && !File(journal.path+".bak").exists()) {UiText.get(R.string.ui_operation_already_exists)}
            val record=JSONObject().put("label",label).put("key",key)
            if(Build.VERSION.SDK_INT>=29) {
                val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME,label.substringAfterLast('/').take(180))
                    put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");put(MediaStore.Downloads.RELATIVE_PATH,"Download/llamur/Models");put(MediaStore.Downloads.IS_PENDING,1)
                })?:error(UiText.get(R.string.ui_could_not_create_model_in_downloads_llamur))
                record.put("uri",uri.toString())
            } else {val file=File(dir,".$key.part");check(file.createNewFile());record.put("private",file.name)}
            try {ModelStore.atomicJson(journal,record)}catch(failure:Throwable){record.optString("uri").takeIf {it.isNotEmpty()}?.let {context.contentResolver.delete(Uri.parse(it),null,null)};throw failure}
            return PendingModel(context,journal,record)
        }
        fun resume(context:Context,key:String):PendingModel? {
            require(key.matches(Regex("[a-z0-9-]{1,100}")))
            val file=File(context.filesDir,"models/.$key.transfer.json");val atomic=android.util.AtomicFile(file)
            if(!file.exists() && !File(file.path+".bak").exists())return null
            val record=atomic.openRead().use {JSONObject(String(it.readBytes(),Charsets.UTF_8))}
            require(record.getString("key")==key);return PendingModel(context,file,record)
        }
        fun cleanupInterruptedImports(context:Context) {
            File(context.filesDir,"models").listFiles().orEmpty().filter {it.name.startsWith(".import-") && (it.name.endsWith(".transfer.json") || it.name.endsWith(".transfer.json.bak"))}.map {
                it.name.removePrefix(".").removeSuffix(".bak").removeSuffix(".transfer.json")
            }.distinct().forEach {key->
                resume(context,key)?.discard()
            }
        }
    }
}
