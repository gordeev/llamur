package org.motoai.runtime

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable HF LFS download. Normal platform TLS validation, no cookies or token persistence. */
internal object HfDownload {
    private fun part(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun validate(repo: String, file: String, revision: String, token: String) {
        require(repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { UiText.get(R.string.ui_repository_must_be_author_model) }
        require(file.length in 6..512 && file.endsWith(".gguf") && !file.startsWith('/') && file.split('/').none { it in listOf("", ".", "..") } && file.none { it.code < 32 || it == '\\' }) { UiText.get(R.string.ui_invalid_gguf_path) }
        require(!Regex(".*-[0-9]{5}-of-[0-9]{5}.*").matches(file)) { UiText.get(R.string.ui_a_single_gguf_is_required_split_files) }
        require(revision.length in 1..128 && revision.none { it.code < 33 || it == '\\' }) { UiText.get(R.string.ui_invalid_revision) }
        require(token.length <= 4096 && token.none { it.code < 33 || it.code > 126 }) { UiText.get(R.string.ui_invalid_token) }
    }
    private fun connect(url: URL, token: String, range: Long = 0, etag: String = ""): HttpURLConnection {
        var target = url
        repeat(8) {
            require(target.protocol == "https" && target.userInfo == null && target.port in listOf(-1,443)) { UiText.get(R.string.ui_downloads_require_https) }
            val c = target.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 20000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "llamur/0.3")
            c.setRequestProperty("Accept-Encoding", "identity")
            // A CDN redirect must never receive the HF credential.
            if (target.host == "huggingface.co" && token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
            if (range > 0) { c.setRequestProperty("Range", "bytes=$range-"); if (etag.isNotEmpty()) c.setRequestProperty("If-Range", etag) }
            val code = c.responseCode
            if (code in listOf(301,302,303,307,308)) {
                val location = c.getHeaderField("Location") ?: run { c.disconnect(); error("HF redirect without Location") }
                val next = URL(target, location); c.disconnect()
                require(next.host == "huggingface.co" || next.host.endsWith(".huggingface.co") || next.host.endsWith(".hf.co")) { UiText.get(R.string.ui_unknown_hf_cdn_host_download_stopped) }
                target = next
            } else return c
        }
        error(UiText.get(R.string.ui_too_many_hf_redirects))
    }
    private fun readJson(url: URL, token: String): JSONObject {
        val c = connect(url, token)
        try {
            check(c.responseCode == 200) { UiText.get(R.string.ui_hf_metadata_http_for_a_gated_model, (c.responseCode)) }
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16384)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(n <= 4*1024*1024 - out.size()) { UiText.get(R.string.ui_hf_metadata_too_large) }; out.write(buffer,0,n) }; out.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { c.disconnect() }
    }
    data class Metadata(val repo:String,val file:String,val commit:String,val sha:String,val bytes:Long,val license:String,val gated:String)
    fun inspect(repo:String,file:String,revision:String,token:String):Metadata {
        validate(repo,file,revision,token)
        val api=readJson(URL("https://huggingface.co/api/models/$repo/revision/${part(revision)}?blobs=true"),token)
        val commit=api.getString("sha");require(commit.matches(Regex("[a-f0-9]{40}")))
        val siblings=api.getJSONArray("siblings")
        val entry=(0 until siblings.length()).map {siblings.getJSONObject(it)}.firstOrNull {it.getString("rfilename")==file} ?: error(UiText.get(R.string.ui_file_is_absent_from_the_pinned_revision))
        val lfs=entry.optJSONObject("lfs") ?: error(UiText.get(R.string.ui_hf_did_not_provide_lfs_sha_256))
        val sha=lfs.optString("sha256").ifEmpty {lfs.optString("oid").removePrefix("sha256:")};val bytes=lfs.getLong("size")
        require(sha.matches(Regex("[a-f0-9]{64}")) && bytes in 24..64L*1024*1024*1024)
        return Metadata(repo,file,commit,sha,bytes,api.optJSONObject("cardData")?.opt("license")?.toString()?:UiText.get(R.string.ui_not_specified_by_author),api.opt("gated")?.toString()?:UiText.get(R.string.ui_unknown_4f1ecc))
    }
    fun run(context: Context, repo: String, file: String, revision: String, token: String, cancelled: AtomicBoolean, progress: (String) -> Unit) {
        val info=inspect(repo,file,revision,token)
        val sha=info.sha;val bytes=info.bytes;val commit=info.commit;val key="hf-$sha"
        progress(UiText.get(R.string.ui_hf_checking_existing_file))
        if(ModelStore.verifiedExisting(context,sha,bytes)?.external==false)return
        val pending=PendingModel.resume(context,key)?:PendingModel.create(context,file.substringAfterLast('/'),key)
        pending.record.put("repo",repo).put("file",file).put("revision",commit).put("sha256",sha).put("bytes",bytes);pending.checkpoint()
        val space=if(Build.VERSION.SDK_INT>=29) android.os.Environment.getExternalStorageDirectory() else context.filesDir
        val reserve=256L*1024*1024
        val size=pending.length()
        require(size in 0..bytes) {UiText.get(R.string.ui_invalid_partial_download_size_remove_it_in)}
        require(space.usableSpace>=bytes-size+reserve) {UiText.get(R.string.ui_not_enough_space_to_download_the_model)}
        check(!cancelled.get()) {UiText.get(R.string.ui_download_cancelled)}
        if(size<bytes) {
            val c=connect(URL("https://huggingface.co/$repo/resolve/$commit/${file.split('/').joinToString("/") {part(it)}}?download=true"),token,size,pending.record.optString("etag"))
            try {
                val append=size>0 && c.responseCode==206
                check(c.responseCode==200 || c.responseCode==206) {UiText.get(R.string.ui_hf_download_http_for_401_403_check, (c.responseCode))}
                var total=if(append)size else 0L
                if(c.responseCode==206) {
                    val range=c.getHeaderField("Content-Range")?:error(UiText.get(R.string.ui_missing_content_range))
                    require(range=="bytes $total-${bytes-1}/$bytes") {UiText.get(R.string.ui_hf_returned_an_invalid_file_range)}
                }
                pending.record.put("etag",c.getHeaderField("ETag")?:"");pending.checkpoint()
                val descriptor=pending.descriptor(true)
                if(!append)android.system.Os.ftruncate(descriptor.fileDescriptor,0)
                android.system.Os.lseek(descriptor.fileDescriptor,total,android.system.OsConstants.SEEK_SET)
                var last=0L
                c.inputStream.use {input->android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use {output->
                    val buffer=ByteArray(1024*1024)
                    while(true) {
                        check(!cancelled.get()) {UiText.get(R.string.ui_download_cancelled_resume_or_remove_the_partial)}
                        val n=input.read(buffer);if(n<0)break
                        require(n<=bytes-total) {UiText.get(R.string.ui_hf_file_exceeds_its_declared_size)};require(space.usableSpace>reserve+n) {UiText.get(R.string.ui_not_enough_space_download_stopped)}
                        output.write(buffer,0,n);total+=n
                        val now=android.os.SystemClock.elapsedRealtime()
                        if(now-last>500){last=now;progress(UiText.get(R.string.ui_hf_mib, (total/(1024*1024)), (bytes/(1024*1024)), (total*100/bytes)))}
                    };output.fd.sync()
                }}
                require(total==bytes) {UiText.get(R.string.ui_file_truncated_repeat_the_same_download_to)}
            }finally{c.disconnect()}
        }
        progress(UiText.get(R.string.ui_re_reading_gguf_and_verifying_sha_256))
        try {pending.verify(bytes,sha) {cancelled.get()}}
        catch(failure:IllegalArgumentException){pending.discard();throw failure}
        pending.commit(file.substringAfterLast('/'),bytes,sha,JSONObject().put("source","https://huggingface.co/$repo/tree/$commit").put("revision",commit).put("license",info.license))
    }
}
