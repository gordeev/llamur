package org.motoai.runtime

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.os.*
import android.view.*
import android.widget.*
import org.json.*
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.*

/** Public deterministic HTTP fixtures, separate debug data, and the real tokenizer on a tiny GGUF. */
internal class CompactionChecks(private val test:Instrumentation) {
    private val context=test.targetContext
    private var checks=0
    val progress=JSONObject()
    private fun verify(ok:Boolean,why:String){check(ok){why};checks++}
    private fun rejects(why:String,block:()->Unit)=verify(runCatching(block).isFailure,why)
    private fun answer(text:String=MEMORY,reason:String="eog")=JSONObject().put("assistant",JSONObject().put("role","assistant").put("content",text)).put("finish_reason",reason)
    private fun fill(store:ChatStore,id:String,turns:Int=6) {repeat(turns){n->store.begin(id,"$id-$n","Project PRJ-731; port 19091; keep original files. "+"Public fact $n. ".repeat(130));store.finish(id,JSONObject().put("role","assistant").put("content","Decision $n: preserve exact weights. "+"Fixture discussion. ".repeat(40)).put("reasoning_content","PRIVATE-THOUGHT").put("remote_payload",JSONObject().put("type","opaque-reasoning")),"eog",UUID.randomUUID().toString())}}
    private fun storeChecks() {
        val store=ChatStore();val c=store.create("fixture");val id=c.getString("id");fill(store,id);val original=c.getJSONArray("messages").toString();val engine=ContextCompactor(c,8192,8)
        var count=0
        while(!engine.done){val m=engine.next {a,reserve->ContextCompactor.estimate(a)+reserve<=8192};val quoted=m.toString();verify(!quoted.contains("PRIVATE-THOUGHT") && !quoted.contains("opaque-reasoning"),"Summary must use visible history, never hidden reasoning");verify(m.getJSONObject(0).getString("content")==ContextCompactor.INSTRUCTION,"Conversation data must not become system instructions");engine.accept(answer());count++}
        verify(count>1,"Long old history must summarize in bounded multiple calls")
        val candidate=engine.candidate(12000,2400);store.compactStart(id);store.compactEnd(id,candidate)
        verify(store.preview(id,"continue").toString().contains("Project PRJ-731"),"Pending summary must not replace history")
        store.compactApply(id);val working=store.preview(id,"continue")
        verify(working.length()==8 && working.getJSONObject(1).getString("content").contains(MEMORY),"Applied view must contain memory plus retained recent turns")
        verify(!working.toString().contains("PRIVATE-THOUGHT") && !working.toString().contains("opaque-reasoning"),"Old signed/hidden reasoning must not cross context epochs")
        verify(c.getJSONArray("messages").toString()==original,"Apply must preserve original messages byte-for-byte")
        store.begin(id,"after-compact","follow-up");store.finish(id,JSONObject().put("role","assistant").put("content","New turn").put("remote_payload",JSONObject().put("type","new-opaque")),"eog",UUID.randomUUID().toString())
        verify(c.getJSONArray("messages").getJSONObject(12).getString("context_epoch")==candidate.getString("id"),"New turns must identify their context epoch")
        store.compactUndo(id);val restored=store.preview(id,"next")
        verify(restored.length()==16 && c.getJSONArray("messages").length()==14 && c.optBoolean("compact_auto_paused"),"Undo must restore originals and later turns, pausing automatic recompaction")
        verify(!restored.toString().contains("new-opaque") && restored.toString().contains("opaque-reasoning"),"Undo must restore only reasoning from the matching original epoch")
        store.compactResume(id);verify(!c.optBoolean("compact_auto_paused"),"Resume clears per-chat pause")
        val bad=ContextCompactor(c,8192,10);bad.next {_,_->true};rejects("Truncated summary must not be accepted"){bad.accept(answer(reason="length"))};rejects("Empty summary must not be accepted"){bad.accept(answer(""))}
        store.compactStart(id);store.compactEnd(id,candidate);store.begin(id,"stale","new question");store.finish(id,null,"eog",UUID.randomUUID().toString());rejects("Sending a new question invalidates pending preview"){store.compactApply(id)}
        verify(c.getJSONArray("messages").length()==16,"Stale preview rejection must keep all messages")
        val name="fixture-compact-${UUID.randomUUID()}";var disk=HistoryDisk(context,"$name.db",name)
        try {val saved=ChatStore(disk);saved.policy("keep",86400000);val p=saved.create("fixture",permanent=true);val pid=p.getString("id");fill(saved,pid,2);val e=ContextCompactor(p,8192,2);e.next {_,_->true};e.accept(answer());saved.compactStart(pid);saved.compactEnd(pid,e.candidate(4000,1500));saved.compactApply(pid);val bytes=p.getJSONArray("messages").toString()
            val temp=saved.create("fixture");val tid=temp.getString("id");fill(saved,tid,1);disk.close();disk=HistoryDisk(context,"$name.db",name);val reopened=ChatStore(disk);val body=reopened.get(pid)
            verify(body.getJSONArray("messages").toString()==bytes && body.getJSONObject("compaction").getString("text")==MEMORY,"Encrypted permanent reopen must preserve original and summary")
            rejects("Temporary originals and summary must not reach disk"){reopened.get(tid)};reopened.compactUndo(pid);verify(reopened.preview(pid,"hi").length()==6,"Undo after reopen restores full original context")
            reopened.compactStart(pid);disk.close();disk=HistoryDisk(context,"$name.db",name);val interrupted=ChatStore(disk).get(pid);verify(!interrupted.optBoolean("active") && !interrupted.optBoolean("compacting") && interrupted.optBoolean("compact_auto_paused"),"Process death must end compaction without replay or loss")
        }finally{disk.erase()}
    }
    private class Server:Closeable {
        val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));val port get()=socket.localPort
        val requests=CopyOnWriteArrayList<JSONObject>();@Volatile var delay=false;@Volatile var fail=false;@Volatile var truncated=false
        private val executor=Executors.newSingleThreadExecutor()
        init {executor.execute {while(!socket.isClosed)try {socket.accept().use {c->c.soTimeout=10000;val input=c.getInputStream()
            fun line():String {val b=ByteArrayOutputStream();while(true){val n=input.read();if(n<0 || n==10)break;b.write(n)};return b.toString("US-ASCII").trimEnd('\r')}
            line();var size=0;while(true){val h=line();if(h.isEmpty())break;if(h.startsWith("Content-Length:",true))size=h.substringAfter(':').trim().toInt()};require(size in 1..ChatFiles.REQUEST_BYTES);val bytes=ByteArray(size);DataInputStream(input).readFully(bytes);val req=JSONObject(String(bytes));requests.add(req)
            while(delay && !socket.isClosed)Thread.sleep(20)
            val summary=req.getJSONArray("messages").getJSONObject(0).optString("content")==ContextCompactor.INSTRUCTION
            val body=if(fail)"""{"error":{"message":"fixture service unavailable"}}""" else JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("role","assistant").put("content",if(summary)MEMORY else "OK. "+"Public discussion. ".repeat(45))).put("finish_reason",if(truncated && summary)"length"else "stop"))).toString()
            val b=body.toByteArray();c.getOutputStream().apply {write("HTTP/1.1 ${if(fail)503 else 200} Fixture\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray());write(b);flush()}
        }}catch(_:Exception){if(socket.isClosed)break}}}
        fun profile()=JSONObject().put("name","Compaction public fixture").put("base_url","http://127.0.0.1:$port").put("allow_local_http",true).put("protocol","chat").put("options","{}").put("max_output",128).put("model","fixture").put("requires_key",false)
        override fun close(){delay=false;socket.close();executor.shutdownNow()}
    }
    private lateinit var endpoint:Messenger
    private val replies=ConcurrentHashMap<String,CompletableFuture<JSONObject>>()
    private var snapshot=JSONObject()
    private val receiver=Messenger(Handler(Looper.getMainLooper()){m->m.data.getString("snapshot")?.let {snapshot=JSONObject(it)};m.data.getString("product_id")?.let {id->m.data.getParcelable<ParcelFileDescriptor>("payload")?.let {fd->ProductPipe.read(fd,34*1024*1024){r->r.fold({replies.remove(id)?.complete(JSONObject(String(it)))},{replies.remove(id)?.completeExceptionally(it)})}}};true})
    private fun command(what:Int,b:Bundle=Bundle())=endpoint.send(Message.obtain(null,what).apply {replyTo=receiver;data=b})
    private fun async(v:JSONObject):CompletableFuture<JSONObject>{val id=UUID.randomUUID().toString();val f=CompletableFuture<JSONObject>();replies[id]=f;val fd=ProductPipe.write(v.toString().toByteArray());try{command(RuntimeService.PRODUCT,Bundle().apply {putString("id",id);putParcelable("payload",fd)})}finally{fd.close()};return f}
    private fun call(v:JSONObject,ok:Boolean=true)=async(v).get(60,TimeUnit.SECONDS).also {if(ok)check(it.optBoolean("ok")){it.optString("message")}}
    private fun get(id:String)=call(JSONObject().put("action","get").put("chat_id",id)).getJSONObject("chat")
    private fun act(action:String,id:String)=call(JSONObject().put("action",action).put("chat_id",id))
    private fun waitFor(seconds:Int=40,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+seconds*1000;while(!condition()){check(SystemClock.elapsedRealtime()<end){"Compaction deadline: $progress"};Thread.sleep(80)}}
    fun run():JSONObject {
        verify(context.packageName.endsWith(".debug"),"Separate debug package required")
        storeChecks();progress.put("stage","service")
        val prefs=context.getSharedPreferences("preferences",Context.MODE_PRIVATE);val previous=prefs.all["auto_compact"];prefs.edit().remove("auto_compact").commit();verify(!prefs.getBoolean("auto_compact",false),"Automatic compaction must default off")
        val activity=test.startActivitySync(Intent(context,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));val ready=CountDownLatch(1)
        val connection=object:ServiceConnection{override fun onServiceConnected(n:ComponentName,b:IBinder){endpoint=Messenger(b);ready.countDown()};override fun onServiceDisconnected(n:ComponentName){}}
        check(context.bindService(Intent(context,RuntimeService::class.java),connection,Context.BIND_AUTO_CREATE));check(ready.await(10,TimeUnit.SECONDS));command(RuntimeService.REGISTER,Bundle().apply {putBoolean("ui_visible",true)})
        val ids=mutableListOf<String>();var provider=""
        try {Server().use {server->
            provider=call(JSONObject().put("action","provider_save").put("profile",server.profile())).getJSONObject("provider").getString("id")
            fun create():String {val id=call(JSONObject().put("action","remote_new").put("provider_id",provider).put("model","fixture")).getJSONObject("chat").getString("id");ids.add(id);call(JSONObject().put("action","compact_window").put("chat_id",id).put("context",8192));return id}
            fun send(id:String,text:String,auto:Boolean=false):JSONObject=call(JSONObject().put("action","remote_send").put("chat_id",id).put("request_id",UUID.randomUUID().toString()).put("text",text).put("auto_compact",auto))
            val id=create();repeat(6){send(id,"PRJ-731 port 19091. "+"Keep weights unchanged. ".repeat(90));waitFor {!get(id).optBoolean("active")}}
            val original=get(id).getJSONArray("messages").toString();verify(server.requests.none {it.getJSONArray("messages").getJSONObject(0).optString("content")==ContextCompactor.INSTRUCTION},"Auto disabled must not make summary calls even with long context")
            val start=server.requests.size;act("compact_start",id);waitFor {!get(id).optBoolean("active")};val preview=get(id)
            verify(preview.has("compact_preview") && !preview.has("compaction") && preview.getJSONArray("messages").toString()==original,"Manual service must preview and preserve full original")
            verify(server.requests.size-start>1,"Manual service must run multiple bounded HTTP calls")
            verify((start until server.requests.size).all {server.requests[it].getString("model")=="fixture" && server.requests[it].getInt("max_tokens")<=2048},"All summary calls must use selected model and bounded output")
            ui(activity,id)
            // UI applied and then undid the summary; apply the fresh pending preview once more.
            act("compact_start",id);waitFor {!get(id).optBoolean("active")};act("compact_apply",id);send(id,"after manual");waitFor {!get(id).optBoolean("active")}
            val sent=server.requests.last().getJSONArray("messages");verify(sent.toString().contains(MEMORY) && sent.length()<14 && sent.getJSONObject(sent.length()-1).getString("content")=="after manual","Following request must use summary rather than the covered original prefix")
            act("compact_undo",id);verify(get(id).getJSONArray("messages").length()==14 && !get(id).has("compaction"),"Service undo retains original and all later turns")
            act("compact_resume",id);progress.put("stage","auto continuation");val before=server.requests.size;send(id,"Auto compact this follow-up",true);waitFor {!get(id).optBoolean("active")}
            val auto=get(id);verify(auto.has("compaction") && auto.getJSONArray("messages").length()==16,"Auto must compact and then admit the original question exactly once")
            verify(server.requests.size>before+1 && server.requests.last().getJSONArray("messages").toString().contains("Auto compact this follow-up"),"Auto continuation must be a separate same-source answer after summaries")
            val retained=auto.getJSONArray("messages").toString();val oldMemory=auto.getJSONObject("compaction").toString()
            call(JSONObject().put("action","draft").put("chat_id",id).put("text","Unsaved next question"))
            server.delay=true;val calls=server.requests.size;act("compact_start",id);waitFor {server.requests.size>calls};command(RuntimeService.STOP);server.delay=false;waitFor {!get(id).optBoolean("active")};val cancelled=get(id)
            verify(cancelled.getJSONArray("messages").toString()==retained && cancelled.getJSONObject("compaction").toString()==oldMemory && cancelled.getString("draft")=="Unsaved next question","Cancellation preserves full history, current summary and draft")
            server.truncated=true;act("compact_start",id);waitFor {!get(id).optBoolean("active")};verify(get(id).getJSONObject("compaction").toString()==oldMemory && get(id).optString("compaction_error").contains("не завершена"),"Truncated remote result must not replace active memory");server.truncated=false
            act("compact_undo",id);act("compact_resume",id)
            server.delay=true;val waitingStart=server.requests.size;val waiting=async(JSONObject().put("action","remote_send").put("chat_id",id).put("request_id",UUID.randomUUID().toString()).put("text","Cancelled auto question").put("auto_compact",true));waitFor {server.requests.size>waitingStart};command(RuntimeService.STOP);server.delay=false
            verify(!waiting.get(30,TimeUnit.SECONDS).optBoolean("ok") && get(id).getJSONArray("messages").toString()==retained && get(id).getString("draft")=="Unsaved next question" && server.requests.size==waitingStart+1,"Cancelling auto must release waiting IPC and preserve draft/history without answering or retrying")
            act("compact_resume",id);server.fail=true;val failStart=server.requests.size;val failed=call(JSONObject().put("action","remote_send").put("chat_id",id).put("request_id",UUID.randomUUID().toString()).put("text","Must remain draft").put("auto_compact",true),false);server.fail=false
            verify(!failed.optBoolean("ok") && server.requests.size==failStart+1 && get(id).getJSONArray("messages").toString()==retained && get(id).optBoolean("compact_auto_paused"),"Failed auto must neither admit question nor retry paid summary; pause automatic retry")
            val unknown=call(JSONObject().put("action","remote_new").put("provider_id",provider).put("model","fixture")).getJSONObject("chat").getString("id");ids.add(unknown);send(unknown,"A first turn");waitFor {!get(unknown).optBoolean("active")}
            val unknownStart=server.requests.size;val noWindow=call(JSONObject().put("action","compact_start").put("chat_id",unknown),false);verify(!noWindow.optBoolean("ok") && !get(unknown).optBoolean("active") && server.requests.size==unknownStart,"Unknown API window must require explicit configuration before any summary request")
            act("compact_start",id);waitFor {!get(id).optBoolean("active")};verify(get(id).has("compact_preview"),"Fresh preview after failure must be available");act("compact_discard",id);verify(!get(id).has("compact_preview") && get(id).getJSONArray("messages").toString()==retained,"Discarding preview must preserve original history")
            act("compact_start",id);waitFor {!get(id).optBoolean("active")};call(JSONObject().put("action","provider_save").put("profile",server.profile().put("id",provider).put("max_output",129)))
            val stale=call(JSONObject().put("action","compact_apply").put("chat_id",id),false);verify(!stale.optBoolean("ok") && !get(id).has("compaction") && get(id).has("compact_preview"),"Source changes after preview must reject apply without losing summary or originals")
            progress.put("stage","native tokenizer");native()
        }}finally {runCatching {command(RuntimeService.UNLOAD)};ids.forEach {runCatching {act("delete",it)}};if(provider.isNotEmpty())runCatching {call(JSONObject().put("action","provider_delete").put("provider_id",provider))};context.unbindService(connection);test.runOnMainSync {activity.finish()};if(previous==null)prefs.edit().remove("auto_compact").commit()else prefs.edit().putBoolean("auto_compact",previous as Boolean).commit()}
        return JSONObject().put("checks",checks).put("passed",true).put("scope","Deterministic HTTP fixture + real native tokenizer. Summary semantics depend on chosen model; no paid/private inference.")
    }
    private fun ui(activity:Activity,id:String) {
        fun invoke(method:String,vararg args:Any){test.runOnMainSync {val f=ChatActivity::class.java.declaredMethods.first {it.name==method && it.parameterTypes.size==args.size};f.isAccessible=true;f.invoke(activity,*args)}}
        fun views():List<View> {val a=mutableListOf<View>();fun walk(v:View){if(v.visibility!=View.VISIBLE)return;a.add(v);if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))};if(Looper.myLooper()==Looper.getMainLooper())walk(activity.window.decorView)else test.runOnMainSync {walk(activity.window.decorView)};return a}
        fun tap(name:String){test.runOnMainSync {val v=views().first {(it as? TextView)?.text?.toString()==name || it.contentDescription?.toString()==name};check(v.performClick())};test.waitForIdleSync()}
        fun capture(name:String){test.waitForIdleSync();Thread.sleep(200);test.uiAutomation.takeScreenshot()?.let {b->File(context.filesDir,"compaction-$name.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        invoke("selectChat",get(id));invoke("showCompaction",id,false);waitFor {views().filterIsInstance<TextView>().any {it.text.toString()=="Предпросмотр · ещё не применяется"}};capture("preview")
        verify(views().filterIsInstance<TextView>().any {it.text.toString()==MEMORY && it.isTextSelectable},"Summary preview must be visible and selectable")
        tap("Применить сводку");waitFor {get(id).has("compaction")};waitFor {views().filterIsInstance<TextView>().any {it.text.toString()=="Активная сводка"}};capture("active")
        tap("Отменить сжатие");invoke("showTab",2);waitFor {!get(id).has("compaction")};verify(get(id).optBoolean("compact_auto_paused"),"UI undo must pause this chat's auto compact")
        invoke("showTab",2);val pagesField=ChatActivity::class.java.getDeclaredField("pages").apply {isAccessible=true};test.runOnMainSync {(pagesField.get(activity) as SettingsPages).clear()}
        waitFor {views().filterIsInstance<Switch>().any {it.text.toString()=="Автоматически сжимать контекст"}}
        val toggle=views().filterIsInstance<Switch>().first {it.text.toString()=="Автоматически сжимать контекст"};verify(!toggle.isChecked,"Settings switch must default off");test.runOnMainSync {toggle.performClick()};verify(context.getSharedPreferences("preferences",Context.MODE_PRIVATE).getBoolean("auto_compact",false),"Switch must persist opt-in");test.runOnMainSync {toggle.performClick()};capture("settings");verify(!((pagesField.get(activity)) as SettingsPages).showing && toggle.isShown,"Late compaction replies must not reopen a page after navigating to settings")
    }
    private fun native() {
        context.startForegroundService(Intent(context,RuntimeService::class.java).setAction(RuntimeService.ACTION_SESSION))
        command(RuntimeService.LOAD,Bundle().apply {putString("model","stories260K.gguf");putString("config",JSONObject().put("context",2048).put("threads",2).put("batch",128).put("micro_batch",64).put("load_mode","read").toString())})
        waitFor {snapshot.optString("state")=="ready" && snapshot.optString("model")=="stories260K.gguf"}
        val chat=call(JSONObject().put("action","new")).getJSONObject("chat");val id=chat.getString("id")
        try {val count=call(JSONObject().put("action","count").put("chat_id",id).put("text","word ".repeat(5000)).put("thinking",false).put("max_tokens",128));verify(!count.getBoolean("fits") && count.getLong("prompt_tokens")>2048,"Native overflow must report exact token count, without truncation")
            val failed=call(JSONObject().put("action","send").put("chat_id",id).put("request_id",UUID.randomUUID().toString()).put("text","word ".repeat(5000)).put("thinking",false).put("max_tokens",128),false);verify(!failed.optBoolean("ok") && get(id).getJSONArray("messages").length()==0,"Overflow admission must preserve draft and avoid appending failed turn")
            call(JSONObject().put("action","send").put("chat_id",id).put("request_id",UUID.randomUUID().toString()).put("text","Once upon a time "+"a red bird was happy. ".repeat(80)).put("thinking",false).put("max_tokens",16));waitFor {!get(id).optBoolean("active")};val original=get(id).getJSONArray("messages").toString()
            act("compact_start",id);waitFor(90){!get(id).optBoolean("active")};val compact=get(id);verify(compact.getJSONArray("messages").toString()==original,"Real local summary pipeline must preserve all original messages")
            verify(compact.has("compact_preview") || compact.optString("compaction_error").isNotEmpty(),"Local pipeline must finish with explicit preview or a quality-gate failure, never hang")
            progress.put("native_preview",compact.has("compact_preview")).put("native_message",compact.optString("compaction_error"))
        }finally {runCatching {act("delete",id)};command(RuntimeService.UNLOAD);waitFor {snapshot.optString("state")=="unloaded"}}
    }
    companion object {const val MEMORY="PRJ-731: keep original files and model weights unchanged. Serve on port 19091. Continue the user's open tasks; verify behavior before installation."}
}
