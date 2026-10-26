package org.motoai.runtime

import android.app.Instrumentation
import android.content.*
import android.os.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.*

/** Explicit developer-only LAN/Aider session. Credentials removed on exit, never a release feature. */
internal class ApiSession(private val test:Instrumentation) {
    fun run(args:Bundle):JSONObject {
        val id=args.getString("runId")!!;require(id.matches(Regex("api-[a-f0-9]{32}")))
        val context=test.targetContext;val events=LinkedBlockingQueue<JSONObject>();val connected=CountDownLatch(1)
        val replies=ConcurrentHashMap<String,CompletableFuture<JSONObject>>()
        lateinit var remote:Messenger
        val incoming=Messenger(Handler(Looper.getMainLooper()) {message->
            message.data.getString("snapshot")?.let {events.offer(JSONObject(it))}
            message.data.getString("product_id")?.let {key->message.data.getParcelable<ParcelFileDescriptor>("payload")?.let {fd->
                ProductPipe.read(fd,4*1024*1024) {r->r.fold({replies.remove(key)?.complete(JSONObject(String(it,Charsets.UTF_8)))},{replies.remove(key)?.completeExceptionally(it)})}
            }};true
        })
        fun send(command:Int,data:Bundle=Bundle()) {remote.send(Message.obtain(null,command).apply {this.data=data;replyTo=incoming})}
        fun product(body:JSONObject):JSONObject {
            val key=UUID.randomUUID().toString();val future=CompletableFuture<JSONObject>();replies[key]=future
            val fd=ProductPipe.write(body.toString().toByteArray(Charsets.UTF_8))
            try {send(RuntimeService.PRODUCT,Bundle().apply {putString("id",key);putParcelable("payload",fd)})}finally{fd.close()}
            val r=future.get(30,TimeUnit.SECONDS);check(r.getBoolean("ok")) {r.optString("message")};return r
        }
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder) {remote=Messenger(binder);send(RuntimeService.REGISTER);connected.countDown()}
            override fun onServiceDisconnected(name:ComponentName) {}
        }
        val activity=test.startActivitySync(Intent(context,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        check(context.bindService(Intent(context,RuntimeService::class.java),connection,Context.BIND_AUTO_CREATE));check(connected.await(15,TimeUnit.SECONDS))
        val ready=File(context.filesDir,"$id-ready.json");val stop=File(context.filesDir,"$id.stop")
        check(!ready.exists() && !stop.exists()) {"Never replay a prior API session"}
        try {
            val model=args.getString("model")!!;require(RuntimeService.validName(model) && model.endsWith(".gguf"))
            context.startForegroundService(Intent(context,RuntimeService::class.java).setAction(RuntimeService.ACTION_SESSION))
            send(RuntimeService.LOAD,Bundle().apply {
                putString("model",model);putString("config",JSONObject().put("context",8192).put("threads",4).put("batch",512).put("micro_batch",128)
                    .put("load_mode","mmap").put("mmap_prefault",false).put("weight_buffers","native").put("swa_full",true)
                    .put("transient_repack",args.getString("transient")=="true").put("prefix_cache_mib",if(args.getString("cache")=="true")128 else 0).toString())
            })
            val deadline=SystemClock.elapsedRealtime()+120000
            while(true) {check(SystemClock.elapsedRealtime()<deadline) {"API model load timeout"};val snapshot=events.poll(100,TimeUnit.MILLISECONDS)?:continue
                check(!snapshot.has("error_kind")) {snapshot.optString("message")};if(snapshot.optString("state")=="ready")break}
            val server=product(JSONObject().put("action","api_start").put("lan",true).put("port",18080))
            ready.writeText(server.toString()) // Explicit private developer side channel; never copied into public result logs.
            val end=SystemClock.elapsedRealtime()+60*60*1000
            while(!stop.exists()) {check(SystemClock.elapsedRealtime()<end) {"Bounded API test session expired"};SystemClock.sleep(250)}
            return JSONObject().put("passed",true).put("scope","Explicit developer API session; coding acceptance is in the host report")
        }finally{
            ready.delete();stop.delete();runCatching {product(JSONObject().put("action","api_stop"));send(RuntimeService.UNLOAD);send(RuntimeService.UNREGISTER)}
            context.unbindService(connection);test.runOnMainSync {activity.finish()}
        }
    }
}
