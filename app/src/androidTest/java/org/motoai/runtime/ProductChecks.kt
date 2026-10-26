package org.motoai.runtime

import android.app.Instrumentation
import android.content.*
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.*

/** Public tiny fixture: product dialogs/API contract/large transport; no claim about 9B coding ability. */
internal class ProductChecks(private val test: Instrumentation) {
    private val context get()=test.targetContext
    private val events=LinkedBlockingQueue<JSONObject>()
    private val replies=ConcurrentHashMap<String,CompletableFuture<JSONObject>>()
    private lateinit var remote:Messenger
    private val connectionReady=CountDownLatch(1)
    private val incoming=Messenger(Handler(Looper.getMainLooper()) { m ->
        val b=m.data
        b.getString("snapshot")?.let { events.offer(JSONObject(it)) }
        b.getString("product_id")?.let { id ->
            b.getParcelable<ParcelFileDescriptor>("payload")?.let { fd -> ProductPipe.read(fd,34*1024*1024) { result ->
                runCatching { JSONObject(String(result.getOrThrow(),Charsets.UTF_8)) }.fold({ replies.remove(id)?.complete(it) }, { replies.remove(id)?.completeExceptionally(it) })
            } }
        };true
    })
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(n:ComponentName,b:IBinder) { remote=Messenger(b);send(RuntimeService.REGISTER);connectionReady.countDown() }
        override fun onServiceDisconnected(n:ComponentName) {}
    }
    private fun send(command:Int,body:Bundle=Bundle()) { remote.send(Message.obtain(null,command).apply { data=body;replyTo=incoming }) }
    private fun product(body:JSONObject,ok:Boolean=true):JSONObject {
        val id=UUID.randomUUID().toString();val future=CompletableFuture<JSONObject>();replies[id]=future
        val fd=ProductPipe.write(body.toString().toByteArray(Charsets.UTF_8))
        try { send(RuntimeService.PRODUCT,Bundle().apply { putString("id",id);putParcelable("payload",fd) }) }finally{fd.close()}
        val result=future.get(30,TimeUnit.SECONDS);check(result.optBoolean("ok")==ok) { "Product response: $result" };return result
    }
    private fun await(predicate:(JSONObject)->Boolean):JSONObject {
        val end=SystemClock.elapsedRealtime()+30000;var last:JSONObject?=null
        while(SystemClock.elapsedRealtime()<end) { val r=events.poll(100,TimeUnit.MILLISECONDS)?:continue;last=r;if(predicate(r))return r }
        error("Product service deadline: $last")
    }
    private fun http(url:String,token:String?,body:JSONObject?=null):Pair<Int,String> {
        val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=5000;c.readTimeout=30000
        if(token!=null)c.setRequestProperty("Authorization","Bearer $token")
        if(body!=null) { c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
        try { val status=c.responseCode;return status to (if(status<400)c.inputStream else c.errorStream).bufferedReader().use { it.readText() } }finally{c.disconnect()}
    }
    fun run():JSONObject {
        check(StrictJson.parse("{\"text\":\"Русский 🦉\"}".toByteArray()).getString("text")=="Русский 🦉")
        for(invalid in listOf("{'a':1}","{\"a\":1,\"a\":2}","{\"a\":1} trailing","{\"a\":NaN}","[1]", "{\"a\":"+"[".repeat(40)+"0"+"]".repeat(40)+"}")) {
            var rejected=false;try {StrictJson.parse(invalid.toByteArray())}catch(_:Exception){rejected=true};check(rejected) {"Invalid network JSON accepted"}
        }
        val store=ChatStore();val c=store.create("m");val id=c.getString("id")
        store.begin(id,"request1","Первый вопрос 🦉")
        store.output(id,JSONObject().put("content","Ответ").put("reasoning_content","мысль"));store.finish(id,null,"eog","aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val second=store.begin(id,"request2","Уточнение")
        check(second.getJSONArray("messages").length()==4)
        check(second.getJSONArray("messages").getJSONObject(2).getString("reasoning_content")=="мысль")
        check(store.duplicate("request1")==id);store.finish(id,null,"interrupted","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        check(store.findAnswer("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")==id);check(store.findAnswer("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")==id)
        store.delete(id);check(store.list().length()==0)
        var activity=test.startActivitySync(Intent(context,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        check(context.bindService(Intent(context,RuntimeService::class.java),connection,Context.BIND_AUTO_CREATE))
        check(connectionReady.await(15,TimeUnit.SECONDS))
        val result=JSONObject()
        try {
            context.startForegroundService(Intent(context,RuntimeService::class.java).setAction(RuntimeService.ACTION_SESSION))
            send(RuntimeService.LOAD,Bundle().apply {putString("model","stories260K.gguf");putString("config",JSONObject().put("context",2048).put("threads",2).put("batch",128).put("micro_batch",64).put("load_mode","read").toString())})
            await {it.optString("state")=="ready"}
            val dialog=product(JSONObject().put("action","new")).getJSONObject("chat");val dialogId=dialog.getString("id")
            val count=product(JSONObject().put("action","count").put("chat_id",dialogId).put("text","Hello").put("max_tokens",16))
            check(count.getBoolean("fits") && count.getInt("prompt_tokens")>0)
            check(product(JSONObject().put("action","get").put("chat_id",dialogId)).getJSONObject("chat").getJSONArray("messages").length()==0)
            val overflowCount=product(JSONObject().put("action","count").put("chat_id",dialogId).put("text","Hello ".repeat(3000)).put("max_tokens",16))
            check(!overflowCount.getBoolean("fits"))
            val large="Пример 🦉\n".repeat(12000)
            product(JSONObject().put("action","draft").put("chat_id",dialogId).put("text",large))
            check(product(JSONObject().put("action","get").put("chat_id",dialogId)).getJSONObject("chat").getString("draft")==large)
            var previousOutput=""
            for(turn in 0..1) {
                events.clear();val requestId=UUID.randomUUID().toString()
                val body=JSONObject().put("action","send").put("chat_id",dialogId).put("text",if(turn==0)"Hello" else "Next story").put("request_id",requestId).put("max_tokens",16)
                product(body);previousOutput=await {it.optString("state")=="ready" && it.has("result") && it.optString("output_id")!=previousOutput && it.optString("chat_id")==dialogId}.getString("output_id")
                val a=product(JSONObject().put("action","get").put("chat_id",dialogId)).getJSONObject("chat")
                check(a.getJSONArray("messages").length()==(turn+1)*2 && !a.getBoolean("active"))
                val answer=a.getJSONArray("messages").getJSONObject((turn+1)*2-1);check(answer.getString("content").isNotEmpty())
                product(body)
                check(product(JSONObject().put("action","get").put("chat_id",dialogId)).getJSONObject("chat").getJSONArray("messages").length()==(turn+1)*2)
            }
            events.clear()
            send(RuntimeService.GENERATE,Bundle().apply {putString("prompt","Once upon a time");putBoolean("raw",true);putInt("tokens",64);putInt("_test_thermal_status",3)})
            val thermal=await {it.optString("state")=="ready" && it.optString("error_kind")=="thermal_pressure"}
            check(thermal.getJSONObject("generation_memory_watch").getBoolean("thermal_abort") && !thermal.has("result"))
            result.put("thermal","Injected SEVERE cancels only current request, no successful answer; next API request recovers")
            result.put("dialogs","RAM multi-turn, reasoning retention, 192+KiB Unicode pipe draft, accepted-request deduplication")
            val api=product(JSONObject().put("action","api_start").put("port",18080).put("lan",false))
            val base=api.getString("url");val token=api.getString("token")
            check(http("$base/models",null).first==401)
            val models=http("$base/models",token);check(models.first==200)
            val model=JSONObject(models.second).getJSONArray("data").getJSONObject(0).getString("id")
            fun body()=JSONObject().put("model",model).put("messages",JSONArray().put(JSONObject().put("role","user").put("content","Hello"))).put("max_tokens",16)
            val plain=http("$base/chat/completions",token,body());check(plain.first==200) {plain.toString()}
            val json=JSONObject(plain.second);check(json.getJSONObject("usage").getInt("completion_tokens")==16)
            val streamed=http("$base/chat/completions",token,body().put("stream",true).put("stream_options",JSONObject().put("include_usage",true)))
            check(streamed.first==200 && streamed.second.endsWith("data: [DONE]\n\n")) {streamed.toString()}
            val chunks=streamed.second.lineSequence().filter {it.startsWith("data: {")}.map {JSONObject(it.removePrefix("data: "))}.toList()
            val text=StringBuilder()
            chunks.forEach {chunk->val choices=chunk.optJSONArray("choices");if(choices!=null && choices.length()>0)text.append(choices.getJSONObject(0).getJSONObject("delta").optString("content"))}
            check(text.toString()==json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
            val original = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            val stop = original.take(8); check(stop.isNotEmpty())
            val stopped = http("$base/chat/completions",token,body().put("stop",stop))
            check(stopped.first==200) { stopped.toString() }
            val stoppedChoice = JSONObject(stopped.second).getJSONArray("choices").getJSONObject(0)
            check(stoppedChoice.getString("finish_reason")=="stop" && stoppedChoice.getJSONObject("message").getString("content").isEmpty())
            check(http("$base/chat/completions",token,body().put("model","absent")).first==404)
            check(http("$base/chat/completions",token,body().put("tools",JSONArray())).first==400)
            check(http("$base/chat/completions",token,body().put("max_tokens",1.5)).first==400)
            check(http("$base/chat/completions",token,body().put("messages",JSONArray().put(JSONObject().put("role","user").put("content","Hello ".repeat(3000))))).first==400)
            events.clear()
            val uri=java.net.URI(base)
            java.net.Socket(uri.host,uri.port).use {socket->
                socket.soTimeout=10000
                val request=body().put("stream",true).put("max_tokens",1024).put("messages",JSONArray().put(JSONObject().put("role","user").put("content","Once upon a time ".repeat(128)))).toString().toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write(("POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $token\r\nContent-Type: application/json\r\nContent-Length: ${request.size}\r\n\r\n").toByteArray());write(request);flush()
                }
                val reader=socket.getInputStream().bufferedReader()
                check(reader.readLine().contains("200"))
                while(reader.readLine().isNotEmpty()){}
                var first="";while(!first.startsWith("data: "))first=reader.readLine()?:error("Missing SSE admission")
                check(JSONObject(first.removePrefix("data: ")).getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getString("role")=="assistant")
                val busy=http("$base/chat/completions",token,body());check(busy.first==429) {"Concurrent engine request was not rejected: $busy"}
                // Reset rather than graceful half-close so the server observes a
                // disconnected streaming reader while the original job is active.
                socket.setSoLinger(true,0)
            }
            await {it.optString("state")=="ready" && it.optString("error_kind")=="cancelled"}
            val recovered=http("$base/chat/completions",token,body());check(recovered.first==200)
            product(JSONObject().put("action","api_stop"))
            check(!product(JSONObject().put("action","api_info")).getBoolean("running"))
            val alias=product(JSONObject().put("action","api_start").put("port",18080).put("lan",false).put("model_id","motoai-public-test").put("model_name","Public fixture"))
            val aliasBase=alias.getString("url");val aliasToken=alias.getString("token")
            val listed=JSONObject(http("$aliasBase/models",aliasToken).second).getJSONArray("data").getJSONObject(0)
            check(listed.getString("id")=="motoai-public-test" && listed.getString("name")=="Public fixture")
            val aliasReply=http("$aliasBase/chat/completions",aliasToken,body().put("model","motoai-public-test"))
            check(aliasReply.first==200 && JSONObject(aliasReply.second).getString("model")=="motoai-public-test")
            check(http("$aliasBase/chat/completions",aliasToken,body()).first==404)
            product(JSONObject().put("action","api_stop"));result.put("api_alias","Custom public ID/name with exact native source admission and completion model identity")
            result.put("api","Authenticated loopback HTTP, models, nonstream/SSE text equality and usage/DONE, malformed/model/tool/overflow errors, recovery, explicit stop")
            result.put("tls",checkTls())
            val prefs=context.getSharedPreferences("preferences",Context.MODE_PRIVATE)
            val outputKey="output:stories260K.gguf";val previous=if(prefs.contains(outputKey))prefs.getInt(outputKey,16) else null
            prefs.edit().putInt(outputKey,16).commit()
            try {
                fun views(view:android.view.View):List<android.view.View> = listOf(view)+if(view is android.view.ViewGroup)(0 until view.childCount).flatMap {views(view.getChildAt(it))}else emptyList()
                val dialogsBefore=product(JSONObject().put("action","list")).getJSONArray("chats").length()
                // New composer requires an explicit model choice; prepare the public loaded fixture.
                val blank=product(JSONObject().put("action","blank")).getJSONObject("chat")
                val assigned=product(JSONObject().put("action","chat_model").put("chat_id",blank.getString("id")).put("model","stories260K.gguf")).getJSONObject("chat")
                test.runOnMainSync {val select=ChatActivity::class.java.getDeclaredMethod("selectChat",JSONObject::class.java);select.isAccessible=true;select.invoke(activity,assigned)}
                val question="UI первая реплика 🦉"
                test.runOnMainSync {views(activity.window.decorView).filterIsInstance<android.widget.EditText>().single {it.hint.toString()=="Сообщение"}.setText(question)}
                val monitor=test.addMonitor(ChatActivity::class.java.name,null,false)
                try {
                    test.runOnMainSync {activity.recreate()}
                    activity=monitor.waitForActivityWithTimeout(10000)?:error("Composer recreation deadline")
                    test.waitForIdleSync()
                    var draft="";test.runOnMainSync {draft=views(activity.window.decorView).filterIsInstance<android.widget.EditText>().single {it.hint.toString()=="Сообщение"}.text.toString()}
                    check(draft==question) {"Unsent first draft lost during Activity recreation"}
                    val end=SystemClock.elapsedRealtime()+10000
                    while(true) {var ready=false;test.runOnMainSync {ready=views(activity.window.decorView).filterIsInstance<android.widget.Button>().single {it.contentDescription?.toString()=="Отправить"}.isEnabled};if(ready)break;check(SystemClock.elapsedRealtime()<end);Thread.sleep(30)}
                    result.put("ui_draft_recreation","Unsent first Unicode draft survives real Activity recreation in RAM")
                }finally{test.removeMonitor(monitor)}
                events.clear()
                test.runOnMainSync {
                    val all=views(activity.window.decorView)
                    all.filterIsInstance<android.widget.EditText>().single {it.hint.toString()=="Сообщение"}.setText(question)
                    val send=all.filterIsInstance<android.widget.Button>().single {it.contentDescription?.toString()=="Отправить"};check(send.isEnabled);send.performClick();val submit=ChatActivity::class.java.getDeclaredMethod("submit");submit.isAccessible=true;submit.invoke(activity)
                }
                val completed=await {it.optString("state")=="ready" && !it.isNull("chat_id") && it.optString("chat_id")!=dialogId && it.has("result")}
                val uiId=completed.getString("chat_id")
                check(product(JSONObject().put("action","list")).getJSONArray("chats").length()==dialogsBefore+1) {"Duplicate submit created an extra empty dialog"}
                val fromUi=product(JSONObject().put("action","get").put("chat_id",uiId)).getJSONObject("chat")
                check(fromUi.getJSONArray("messages").length()==2 && fromUi.getJSONArray("messages").getJSONObject(0).getString("content")==question) {"First composer request lost or double-tap duplicated"}
                result.put("ui_first_send","Actual explicitly assigned local composer preserves first Unicode prompt and ignores duplicate submit while admission is pending")
                val followup="Черновик следующего вопроса 🦉"
                test.runOnMainSync {views(activity.window.decorView).filterIsInstance<android.widget.EditText>().single {it.hint.toString()=="Сообщение"}.setText(followup)}
                val savedDeadline=SystemClock.elapsedRealtime()+10000
                while(product(JSONObject().put("action","get").put("chat_id",uiId)).getJSONObject("chat").optString("draft")!=followup) {check(SystemClock.elapsedRealtime()<savedDeadline) {
                    var details="";test.runOnMainSync {details=listOf("chatId","lastDraft","restoreDraft","current").joinToString {field->val f=ChatActivity::class.java.getDeclaredField(field);f.isAccessible=true;"$field=${f.get(activity)}"}+" input="+views(activity.window.decorView).filterIsInstance<android.widget.EditText>().single {it.hint.toString()=="Сообщение"}.text.toString()}
                    "Following draft was not stored in service RAM: $details; expected=$uiId; stored="+product(JSONObject().put("action","get").put("chat_id",uiId)).getJSONObject("chat").optString("draft")
                };Thread.sleep(50)}
                test.runOnMainSync {activity.finish()};test.waitForIdleSync()
                activity=test.startActivitySync(Intent(context,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                // A fresh launch intentionally starts on the dialog list. Open the existing dialog explicitly.
                test.runOnMainSync {val open=ChatActivity::class.java.getDeclaredMethod("openChat",String::class.java);open.isAccessible=true;open.invoke(activity,uiId)}
                val restoredDeadline=SystemClock.elapsedRealtime()+10000
                while(true) {var restored="";test.runOnMainSync {restored=views(activity.window.decorView).filterIsInstance<android.widget.EditText>().single {it.hint.toString()=="Сообщение"}.text.toString()};if(restored==followup)break;check(SystemClock.elapsedRealtime()<restoredDeadline) {"RAM service draft not restored in a fresh Activity"};Thread.sleep(30)}
                check(product(JSONObject().put("action","get").put("chat_id",uiId)).getJSONObject("chat").getJSONArray("messages").length()==2)
                result.put("ui_service_draft_restore","Fresh Activity restores service RAM draft without sending/replaying or disk state")
                send(RuntimeService.UNLOAD);await {it.optString("state")=="unloaded"}
                val loadDeadline=SystemClock.elapsedRealtime()+10000
                while(true){var ready=false;test.runOnMainSync {ready=views(activity.window.decorView).filterIsInstance<android.widget.Button>().any {it.isShown && it.contentDescription?.toString()=="Загрузить модель диалога" && it.isEnabled}};if(ready)break;check(SystemClock.elapsedRealtime()<loadDeadline){"Existing local chat has no reload action"};Thread.sleep(30)}
                test.runOnMainSync {views(activity.window.decorView).filterIsInstance<android.widget.Button>().single {it.isShown && it.contentDescription?.toString()=="Загрузить модель диалога"}.performClick()}
                val profileDeadline=SystemClock.elapsedRealtime()+10000
                while(test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Профиль модели")?.isEmpty()!=false){check(SystemClock.elapsedRealtime()<profileDeadline);Thread.sleep(30)}
                test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                result.put("ui_existing_chat_reload","After unload an existing non-empty chat offers explicit reload/profile without changing source or losing history")
                test.runOnMainSync {val home=ChatActivity::class.java.getDeclaredMethod("showTab",Int::class.javaPrimitiveType);home.isAccessible=true;home.invoke(activity,0)}
                product(JSONObject().put("action","delete").put("chat_id",uiId))
            }finally{val edit=prefs.edit();if(previous==null)edit.remove(outputKey)else edit.putInt(outputKey,previous);edit.commit()}
            product(JSONObject().put("action","delete").put("chat_id",dialogId))
            send(RuntimeService.UNLOAD);await {it.optString("state")=="unloaded"}
            return result.put("passed",true)
        } finally { runCatching { product(JSONObject().put("action","api_stop"));send(RuntimeService.UNLOAD);send(RuntimeService.UNREGISTER) };context.unbindService(connection);test.runOnMainSync {activity.finish()} }
    }
    private fun checkTls():String {
        val identity=ApiTls.create("127.0.0.1")
        val server=identity.context.serverSocketFactory.createServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")) as javax.net.ssl.SSLServerSocket
        val task=CompletableFuture.runAsync {server.accept().use {socket->socket.getOutputStream().write(42);socket.getOutputStream().flush()}}
        val cert=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(identity.pem.byteInputStream())
        val trust=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {load(null);setCertificateEntry("motoai",cert)}
        val tm=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply {init(trust)}
        val ssl=javax.net.ssl.SSLContext.getInstance("TLS").apply {init(null,tm.trustManagers,null)}
        try {
            (ssl.socketFactory.createSocket("127.0.0.1",server.localPort) as javax.net.ssl.SSLSocket).use { socket ->
                socket.soTimeout=5000;socket.sslParameters=socket.sslParameters.apply {endpointIdentificationAlgorithm="HTTPS"};socket.startHandshake();check(socket.inputStream.read()==42)
            };task.get(5,TimeUnit.SECONDS)
        } catch (failure: Exception) {
            try { task.get(5,TimeUnit.SECONDS) } catch (serverError: Exception) { failure.addSuppressed(serverError) }; throw failure
        } finally{server.close()}
        return "AndroidKeyStore P256 ECDSA + platform TLS trust and IP SAN verification, private key not exported"
    }
}
