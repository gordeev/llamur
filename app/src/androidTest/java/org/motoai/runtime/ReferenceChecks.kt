package org.motoai.runtime

import android.app.Instrumentation
import android.content.ContentValues
import android.os.*
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Own public fixture only: no-copy regular FD lifetime, persistence, and unlink safety. */
internal class ReferenceChecks(private val test:Instrumentation) {
    fun run():JSONObject {
        val context=test.targetContext
        val sha="270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d";val id="$sha.gguf"
        check(ModelStore.entries(context).none {it.id==id}) {"Never replace an unrelated fixture/model"}
        val bytes=File(context.filesDir,"models/stories260K.gguf").readBytes()
        val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,"reference-${UUID.randomUUID()}.gguf");put(MediaStore.Downloads.RELATIVE_PATH,"Download/MotoAI/Models");put(MediaStore.Downloads.IS_PENDING,1)
        })?:error("No reference fixture")
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(context.contentResolver.openFileDescriptor(uri,"rw")!!).use {it.write(bytes);it.fd.sync()}
            context.contentResolver.update(uri,ContentValues().apply {put(MediaStore.Downloads.IS_PENDING,0)},null,null)
            val done=CountDownLatch(1);val started=AtomicBoolean(false);var status=""
            test.runOnMainSync {ModelStore.observe {text,busy->status=text;if(busy)started.set(true);if(!busy && started.get())done.countDown()};ModelStore.attachReference(context,uri)}
            check(done.await(30,TimeUnit.SECONDS)) {status};test.runOnMainSync {ModelStore.observe(null)}
            val entry=ModelStore.entries(context).singleOrNull {it.id==id} ?: error("No-copy registration failed: $status");check(entry.external && !File(context.filesDir,"models/$id").exists())
            NativeRuntime.initialize(context)
            ModelStore.open(context,id).use {opened->
                check(ModelStore.verifyOpened(opened,sha){false}==sha)
                NativeRuntime.load(opened.path,JSONObject().put("context",128).put("threads",2).put("load_mode","mmap").put("mmap_prefault",false),NativeRuntime.ticket())
                try {val result=NativeRuntime.generate(JSONObject().put("chat",false).put("raw_prompt","Once upon a time").put("max_tokens",16),NativeRuntime.ticket(),object:NativeRuntime.Sink {override fun onBytes(bytes:ByteArray){}});check(result.getInt("generated_tokens")==16)}finally{NativeRuntime.unload()}
            }
            var refused=false;try {ModelStore.deleteOwned(context,id)}catch(_:IllegalArgumentException){refused=true};check(refused) {"No-copy source deletion allowed"}
            ModelStore.forgetExternal(context,id)
            check(ModelStore.entries(context).none {it.id==id})
            context.contentResolver.openFileDescriptor(uri,"r")!!.use {check(it.statSize==bytes.size.toLong())} // Disconnect never deletes source.
            return JSONObject().put("passed",true).put("sha256",sha).put("private_copy",false).put("mmap_generation",true).put("source_preserved",true)
                .put("scope","Own real MediaStore regular-FD no-copy registration, SHA/reread/mmap lifetime, source-deletion refusal and disconnect preservation. External provider persistable grant UI/reboot is separate.")
        }finally {runCatching {ModelStore.forgetExternal(context,id)};context.contentResolver.delete(uri,null,null)}
    }
}
