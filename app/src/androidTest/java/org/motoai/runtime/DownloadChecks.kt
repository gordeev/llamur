package org.motoai.runtime

import android.app.Instrumentation
import android.os.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real HTTPS/HF LFS and foreground transfer with an explicit public developer fixture. */
internal class DownloadChecks(private val test:Instrumentation) {
    fun run():JSONObject {
        val context=test.targetContext
        val repo="ggml-org/models-moved";val file="tinyllamas/stories260K.gguf";val revision="499bc8821c6b12b4e53c5bffcb21ec206f212d81"
        val sha="270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d";val key="hf-$sha"
        val info=HfDownload.inspect(repo,file,revision,"")
        check(info.sha==sha && info.commit==revision && info.bytes==1185376L)
        val token="hf_public_fixture_not_a_real_credential"
        LocalCredentials.save(context,token);check(LocalCredentials.read(context)==token)
        val storage=File(context.applicationInfo.dataDir,"shared_prefs/credentials.xml").readText()
        check(!storage.contains(token));LocalCredentials.forget(context);check(LocalCredentials.read(context).isEmpty())
        // Seed exactly the original public file's first256KiB. An initial explicit
        // cancel must retain it; a subsequent ordinary FGS download may resume it.
        val existing=ModelStore.entries(context).firstOrNull {it.id=="$sha.gguf"}
        check(existing==null) {"Fixture ID already exists; never delete an unrelated user's model for a test"}
        PendingModel.resume(context,key)?.discard()
        val pending=PendingModel.create(context,"stories260K.gguf",key)
        val prefix=File(context.filesDir,"models/stories260K.gguf").inputStream().use {input->ByteArray(256*1024).also {check(input.read(it)==it.size)}}
        ParcelFileDescriptor.AutoCloseOutputStream(pending.descriptor(true)).use {it.write(prefix);it.fd.sync()}
        var cancelled=false
        try {HfDownload.run(context,repo,file,revision,"",AtomicBoolean(true)) {}}
        catch(_:IllegalStateException){cancelled=true}
        check(cancelled && PendingModel.resume(context,key)!!.length()==prefix.size.toLong())
        val done=CountDownLatch(1);val started=AtomicBoolean(false);var status=""
        test.runOnMainSync {
            ModelStore.observe {text,busy->status=text;if(busy)started.set(true);if(!busy && started.get())done.countDown()}
            ModelTransferService.download(context,repo,file,revision,"")
        }
        check(done.await(120,TimeUnit.SECONDS)) {"Foreground HF transfer timeout: $status"}
        test.runOnMainSync {ModelStore.observe(null)}
        val entry=ModelStore.entries(context).singleOrNull {it.id=="$sha.gguf"} ?: error("HF download failed: $status")
        try {
            check(entry.bytes==info.bytes && entry.sha256==sha)
            check(entry.location==if(Build.VERSION.SDK_INT>=29)"Downloads/MotoAI/Models" else "Внутреннее хранилище")
            check(Build.VERSION.SDK_INT<29 || !File(context.filesDir,"models/${entry.id}").exists()) {"Duplicate full private model left behind"}
            check(PendingModel.resume(context,key)==null)
            // Actual re-read verifies the managed destination, not merely metadata.
            check(ModelStore.verifiedExisting(context,sha,info.bytes)!=null)
            return JSONObject().put("passed",true).put("anonymous_hf",true).put("immutable_revision",revision).put("sha256",sha)
                .put("bytes",entry.bytes).put("cancelled_partial_retained",prefix.size).put("foreground_transfer",true).put("private_duplicate",false)
                .put("credential_storage","AndroidKeyStore AES-GCM roundtrip; no plaintext in prefs; explicit deletion")
                .put("scope","Public test-only GGUF, never bundled. No claim of valid private/gated account access without a supplied authorized token.")
        }finally{ModelStore.deleteOwned(context,entry.id);LocalCredentials.forget(context)}
    }
}
