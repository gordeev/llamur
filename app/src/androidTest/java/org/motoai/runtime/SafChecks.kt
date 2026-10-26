package org.motoai.runtime

import android.app.Instrumentation
import android.content.*
import android.os.*
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Actual DocumentsUI grant + process/Activity recreation + reboot stage, emulator/public fixture only. */
internal class SafChecks(private val test:Instrumentation) {
    private val context get()=test.targetContext
    private val id="270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d.gguf"
    private fun until(stage:String="registered",condition:()->Boolean) {val end=SystemClock.elapsedRealtime()+30000
        while(!condition()){check(SystemClock.elapsedRealtime()<end) {"SAF UI deadline at $stage: ${test.uiAutomation.rootInActiveWindow?.let {describe(it)}}"};Thread.sleep(50)}}
    private fun describe(n:android.view.accessibility.AccessibilityNodeInfo):String = "${n.text ?: n.contentDescription ?: ""} " +(0 until n.childCount).joinToString(" ") {n.getChild(it)?.let {describe(it)}?:""}
    private fun click(label:String):Boolean {
        val root=test.uiAutomation.rootInActiveWindow?:return false;root.refresh()
        fun all(n:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> = listOf(n)+(0 until n.childCount).flatMap {n.getChild(it)?.let {all(it)}?:emptyList()}
        for(candidate in all(root).filter {it.text?.toString()?.equals(label,ignoreCase=true)==true}) {
            var node:android.view.accessibility.AccessibilityNodeInfo?=candidate
            repeat(12) {val current=node?:return@repeat;if(current.isClickable && current.isEnabled)return current.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);node=current.parent}
        };return false
    }
    private fun generate():String {
        NativeRuntime.initialize(context)
        ModelStore.open(context,id).use {opened->
            check(ModelStore.verifyOpened(opened,id.removeSuffix(".gguf")){false}==id.removeSuffix(".gguf"))
            NativeRuntime.load(opened.path,JSONObject().put("context",128).put("threads",2).put("load_mode","mmap").put("mmap_prefault",false),NativeRuntime.ticket())
            try {val out=java.io.ByteArrayOutputStream();val result=NativeRuntime.generate(JSONObject().put("chat",false).put("raw_prompt","Once upon a time").put("max_tokens",16),NativeRuntime.ticket(),object:NativeRuntime.Sink {override fun onBytes(bytes:ByteArray){out.write(bytes)}})
                check(result.getInt("generated_tokens")==16);return out.toString("UTF-8")
            }finally{NativeRuntime.unload()}
        }
    }
    fun copyDelete():JSONObject {
        check(Build.HARDWARE=="ranchu")
        check(ModelStore.entries(context).none {it.id==id}) {"Never replace another model"}
        val name="import-${UUID.randomUUID()}.gguf"
        val original=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.RELATIVE_PATH,"Download/MotoAI/Import-tests");put(MediaStore.Downloads.IS_PENDING,1)
        })?:error("Cannot create public import source")
        val activity=test.startActivitySync(Intent(context,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val bytes=File(context.filesDir,"models/stories260K.gguf").readBytes()
            fun write(data:ByteArray) {ParcelFileDescriptor.AutoCloseOutputStream(context.contentResolver.openFileDescriptor(original,"rw")!!).use {it.write(data);it.fd.sync()}}
            write(bytes);context.contentResolver.update(original,ContentValues().apply {put(MediaStore.Downloads.IS_PENDING,0)},null,null)
            test.runOnMainSync {activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type="*/*";addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download/MotoAI/Import-tests"))
            },1)}
            until("select import source") {click(name)};until("copy consent") {click("Копировать")}
            until("canonical import") {ModelStore.entries(context).any {it.id==id && !it.external} && !ModelStore.transferState().second}
            val document=ModelStore.pendingDeletionSource()?:error("Original deletion not offered")
            check(ModelStore.safeToOfferSourceDeletion(context,document))
            val sha=id.removeSuffix(".gguf")
            val changed=bytes.copyOf();changed[changed.lastIndex]=(changed.last().toInt() xor 1).toByte();write(changed)
            var rejected=false;try {ModelStore.deleteVerifiedOriginal(context,document,sha)}catch(_:IllegalArgumentException){rejected=true}
            check(rejected) {"Changed original was deleted"}
            context.contentResolver.openFileDescriptor(original,"r")!!.use {check(it.statSize==bytes.size.toLong())}
            ModelStore.open(context,id).use {check(ModelStore.verifyOpened(it,sha){false}==sha)}
            write(bytes)
            until("explicit original deletion") {click("Удалить источник")}
            until("original removed") {context.contentResolver.query(original,arrayOf(MediaStore.Downloads._ID),null,null,null)?.use {!it.moveToFirst()}?:true}
            ModelStore.open(context,id).use {check(ModelStore.verifyOpened(it,sha){false}==sha)}
            check(!File(context.filesDir,"models/$id").exists())
            return JSONObject().put("passed",true).put("actual_documents_ui_copy",true).put("changed_source_preserved",true).put("explicit_delete_ui",true).put("canonical_copy_sha_preserved",true).put("private_duplicate",false)
        }finally {
            test.runOnMainSync {activity.finish()};ModelStore.clearDeletionOffer()
            runCatching {ModelStore.deleteOwned(context,id)};runCatching {context.contentResolver.delete(original,null,null)}
        }
    }
    fun run(resume:Boolean):JSONObject {
        check(Build.HARDWARE=="ranchu") {"Actual picker automation is restricted to the public test emulator"}
        val state=File(context.filesDir,"saf-reboot-fixture.json")
        if(resume) {
            val fixture=JSONObject(state.readText());val doc=Uri.parse(fixture.getString("document_uri"));val original=Uri.parse(fixture.getString("original_uri"))
            try {
                check(context.contentResolver.persistedUriPermissions.any {it.uri==doc && it.isReadPermission}) {"Persistable read grant lost at reboot"}
                check(ModelStore.entries(context).single {it.id==id}.external)
                check(generate()==fixture.getString("output"))
                ModelStore.forgetExternal(context,id)
                context.contentResolver.openFileDescriptor(original,"r")!!.use {check(it.statSize==1185376L)}
                return JSONObject().put("passed",true).put("persisted_grant_after_reboot",true).put("exact_mmap_output",true).put("disconnect_preserves_source",true)
            }finally {runCatching {ModelStore.forgetExternal(context,id)};context.contentResolver.delete(original,null,null);state.delete()}
        }
        check(!state.exists() && ModelStore.entries(context).none {it.id==id}) {"Never overwrite another source/fixture"}
        val name="saf-${UUID.randomUUID()}.gguf"
        val original=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");put(MediaStore.Downloads.RELATIVE_PATH,"Download/MotoAI/Models");put(MediaStore.Downloads.IS_PENDING,1)
        })?:error("Cannot create public fixture")
        val activity=test.startActivitySync(Intent(context,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var complete=false
        try {
            val bytes=File(context.filesDir,"models/stories260K.gguf").readBytes()
            ParcelFileDescriptor.AutoCloseOutputStream(context.contentResolver.openFileDescriptor(original,"rw")!!).use {it.write(bytes);it.fd.sync()}
            context.contentResolver.update(original,ContentValues().apply {put(MediaStore.Downloads.IS_PENDING,0)},null,null)
            test.runOnMainSync {activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type="*/*";addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download/MotoAI/Models"))
            },1)}
            until("select $name") {click(name)}
            until("no-copy dialog") {click("Без копии")}
            until("attach confirmation") {click("Подключить")}
            until {ModelStore.entries(context).any {it.id==id && it.external} && !ModelStore.transferState().second}
            val registry=JSONObject(File(context.filesDir,"models/$id.shared.json").readText());val doc=Uri.parse(registry.getString("uri"))
            check(context.contentResolver.persistedUriPermissions.any {it.uri==doc && it.isReadPermission})
            check(!File(context.filesDir,"models/$id").exists())
            val output=generate()
            state.writeText(JSONObject().put("document_uri",doc.toString()).put("original_uri",original.toString()).put("output",output).toString())
            complete=true
            return JSONObject().put("passed",true).put("actual_documents_ui",true).put("persistable_read",true).put("private_copy",false).put("reboot_stage_ready",true)
        }finally {test.runOnMainSync {activity.finish()};if(!complete){runCatching {ModelStore.forgetExternal(context,id)};context.contentResolver.delete(original,null,null)}}
    }
}
