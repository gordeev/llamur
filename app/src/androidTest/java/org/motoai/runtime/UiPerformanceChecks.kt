package org.motoai.runtime

import android.app.*
import android.content.*
import android.os.*
import android.widget.*
import org.json.*
import java.util.UUID
import java.util.concurrent.*

/** Same loaded engine/foreground Activity, public prompt; bounded UI overhead observation only. */
internal class UiPerformanceChecks(private val test:Instrumentation) {
    fun run(args:Bundle):JSONObject {
        val context=test.targetContext;val model=args.getString("model")!!
        val keyguard=context.getSystemService(android.app.KeyguardManager::class.java)
        check(!keyguard.isDeviceLocked && !keyguard.isKeyguardLocked) {"Unlock the handset normally before actual foreground UI performance testing"}
        require(model in listOf("Ornith-1.5-9B-Q4_K_M.gguf","gemma-4-E4B-it-Q4_K_M.gguf"))
        val events=LinkedBlockingQueue<JSONObject>();val chunks=LinkedBlockingQueue<Bundle>();val connected=CountDownLatch(1)
        val replies=ConcurrentHashMap<String,CompletableFuture<JSONObject>>();lateinit var remote:Messenger
        val incoming=Messenger(Handler(Looper.getMainLooper()){m->
            m.data.getString("snapshot")?.let {events.offer(JSONObject(it))}
            if(m.data.containsKey("output_chunk"))chunks.offer(Bundle(m.data))
            m.data.getString("product_id")?.let {key->m.data.getParcelable<ParcelFileDescriptor>("payload")?.let {fd->ProductPipe.read(fd,4*1024*1024){r->r.fold({replies.remove(key)?.complete(JSONObject(String(it,Charsets.UTF_8)))},{replies.remove(key)?.completeExceptionally(it)})}}};true
        })
        fun send(command:Int,body:Bundle=Bundle()){remote.send(Message.obtain(null,command).apply {data=body;replyTo=incoming})}
        fun product(body:JSONObject):JSONObject {
            val key=UUID.randomUUID().toString();val future=CompletableFuture<JSONObject>();replies[key]=future
            val fd=ProductPipe.write(body.toString().toByteArray(Charsets.UTF_8));try{send(RuntimeService.PRODUCT,Bundle().apply {putString("id",key);putParcelable("payload",fd)})}finally{fd.close()}
            return future.get(30,TimeUnit.SECONDS).also {check(it.getBoolean("ok")){it.toString()}}
        }
        fun await(seconds:Int=600,predicate:(JSONObject)->Boolean):JSONObject {
            val end=SystemClock.elapsedRealtime()+seconds*1000L;var last:JSONObject?=null
            while(SystemClock.elapsedRealtime()<end){val item=events.poll(100,TimeUnit.MILLISECONDS)?:continue;last=item
                check(!item.has("error_kind")){item.toString()};if(predicate(item))return item}
            error("UI performance deadline: $last")
        }
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder){remote=Messenger(binder);send(RuntimeService.REGISTER);connected.countDown()}
            override fun onServiceDisconnected(name:ComponentName){}
        }
        val prefs=context.getSharedPreferences("preferences",Context.MODE_PRIVATE)
        val saved=prefs.all.filterKeys {it in listOf("thinking","temperature","output:$model")}
        prefs.edit().putBoolean("thinking",true).putFloat("temperature",0f).putInt("output:$model",128).commit()
        val activity=test.startActivitySync(Intent(context,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        check(context.bindService(Intent(context,RuntimeService::class.java),connection,Context.BIND_AUTO_CREATE));check(connected.await(15,TimeUnit.SECONDS))
        fun requireVisibleForeground() {
            check(!keyguard.isDeviceLocked && !keyguard.isKeyguardLocked && context.getSystemService(android.os.PowerManager::class.java).isInteractive) {"UI timing requires an unlocked interactive handset"}
            test.runOnMainSync {check(activity.window.decorView.hasWindowFocus()) {"UI timing lost real Activity window focus"}}
        }
        fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
        fun until(condition:()->Boolean){val end=SystemClock.elapsedRealtime()+10000;while(!condition()){check(SystemClock.elapsedRealtime()<end);Thread.sleep(30)}}
        val text="Write a Python function that adds two integers."
        val body=JSONObject().put("chat",true).put("messages",JSONArray().put(JSONObject().put("role","system").put("content","You are a helpful assistant.")).put(JSONObject().put("role","user").put("content",text)))
            .put("max_tokens",128).put("temperature",0.0).put("seed",42).put("thinking",true).put("parse_response",true)
        fun raw(id:String):ByteArray {
            chunks.clear();send(RuntimeService.READ_OUTPUT,Bundle().apply {putString("output_id",id);putInt("offset",0)})
            val chunk=chunks.poll(10,TimeUnit.SECONDS)?:error("Missing RAM output")
            check(chunk.getString("output_id")==id);val bytes=chunk.getByteArray("output_chunk")!!
            check(bytes.size==chunk.getInt("output_total")){"Bounded fixture output exceeds one frame"};return bytes
        }
        val rows=JSONArray();var expected:ByteArray?=null
        try {
            events.clear() // Never delete owner history to prepare a performance fixture.
            context.startForegroundService(Intent(context,RuntimeService::class.java).setAction(RuntimeService.ACTION_SESSION))
            send(RuntimeService.LOAD,Bundle().apply {putString("model",model);putString("config",JSONObject().put("context",4096).put("threads",4).put("batch",512).put("micro_batch",128)
                .put("load_mode","mmap").put("mmap_prefault",false).put("weight_buffers","native").put("swa_full",true).put("transient_repack",false).put("prefix_cache_mib",0).toString())})
            val loaded=await(180){it.optString("state")=="ready"}
            fun trial(kind:String,warmup:Boolean=false) {
                var chat=""
                if(kind=="product") {
                    chat=product(JSONObject().put("action","blank")).getJSONObject("chat").getString("id")
                    val assigned=product(JSONObject().put("action","chat_model").put("chat_id",chat).put("model",model)).getJSONObject("chat")
                    test.runOnMainSync {val select=ChatActivity::class.java.getDeclaredMethod("selectChat",JSONObject::class.java);select.isAccessible=true;select.invoke(activity,assigned);views(activity.window.decorView).filterIsInstance<EditText>().single {it.hint.toString()=="Сообщение"}.setText(text)}
                    until {var ready=false;test.runOnMainSync {ready=views(activity.window.decorView).filterIsInstance<Button>().single {it.contentDescription?.toString()=="Отправить"}.isEnabled};ready}
                }
                requireVisibleForeground()
                events.clear();val start=SystemClock.elapsedRealtimeNanos()
                if(kind=="product")test.runOnMainSync {
                    val all=views(activity.window.decorView);all.filterIsInstance<EditText>().single {it.hint.toString()=="Сообщение"}.setText(text)
                    all.filterIsInstance<Button>().single {it.contentDescription?.toString()=="Отправить"}.performClick()
                }else send(RuntimeService.GENERATE,Bundle().apply {putString("request",JSONObject(body.toString()).put("max_tokens",if(warmup)16 else 128).toString());putLong("client_sent_ns",start);putBoolean("notify_on_completion",false)})
                val running=await {it.optString("state")=="generating"};val outputId=running.getString("output_id")
                val completed=await {it.optString("state")=="ready" && it.optString("output_id")==outputId && it.has("result")}
                val wall=(SystemClock.elapsedRealtimeNanos()-start)/1000
                requireVisibleForeground()
                val bytes=raw(outputId);check(bytes.isNotEmpty())
                if(!warmup){if(expected==null)expected=bytes else check(expected!!.contentEquals(bytes)){"UI changed the generated raw token pieces"}
                    rows.put(JSONObject().put("kind",kind).put("wall_us",wall).put("snapshot",completed).put("output_sha256",java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}))}
                if(chat.isNotEmpty()){
                    test.runOnMainSync {val home=ChatActivity::class.java.getDeclaredMethod("showTab",Int::class.javaPrimitiveType);home.isAccessible=true;home.invoke(activity,0)}
                    product(JSONObject().put("action","delete").put("chat_id",chat))
                }
            }
            trial("baseline",true)
            for(kind in listOf("baseline","product","product","baseline"))trial(kind)
            send(RuntimeService.UNLOAD);await(30){it.optString("state")=="unloaded"}
            return JSONObject().put("passed",true).put("load",loaded).put("rows",rows).put("raw_output_identical",true)
                .put("scope","B/P/P/B, same single loaded native CPU4/4K engine and foreground Activity, public short prompt128-token reserve, thinkingON, no cache/transient. One excluded16-token warmup. Two pairs are an overhead observation, not a speed promotion, general chat-quality or long-history UI certification.")
        }finally {
            runCatching {send(RuntimeService.UNLOAD);send(RuntimeService.UNREGISTER)};context.unbindService(connection);test.runOnMainSync {activity.finish()}
            val edit=prefs.edit();for(key in listOf("thinking","temperature","output:$model")){edit.remove(key);when(val old=saved[key]){is Boolean->edit.putBoolean(key,old);is Float->edit.putFloat(key,old);is Int->edit.putInt(key,old)}};edit.commit()
        }
    }
}
