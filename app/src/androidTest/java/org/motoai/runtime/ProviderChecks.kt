package org.motoai.runtime

import android.app.Instrumentation
import android.content.*
import android.os.*
import android.view.*
import android.widget.*
import org.json.*
import java.io.*
import java.net.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Public local fixtures only: no cloud credentials, external requests or GGUF needed. */
internal class ProviderChecks(private val test:Instrumentation) {
    val progress=JSONObject()
    private var checks=0
    private fun verify(value:Boolean){check(value){"Provider check ${checks+1} failed"};checks++}
    private fun reject(block:()->Unit){verify(runCatching(block).isFailure)}
    private fun protocolChecks() {
        reject{ProviderStore.endpoint("http://example.com/v1",true)};reject{ProviderStore.endpoint("https://key@example.com/v1",false)};reject{ProviderStore.endpoint("https://example.com/v1?key=bad",false)}
        verify(ProviderStore.endpoint("http://127.0.0.1:123/v1/",true)=="http://127.0.0.1:123/v1")
        val approved="http://100.64.10.20:8000/v1"
        verify(ProviderStore.endpoint(approved,false,approved)==approved)
        verify(ProviderStore.endpoint("http://model.tail123.ts.net:8080/v1",false,"http://model.tail123.ts.net:8080/v1").contains("tail123"))
        for(changed in listOf("http://100.64.10.21:8000/v1","http://100.64.10.20:8001/v1","http://100.64.10.20:8000/other"))reject{ProviderStore.endpoint(changed,false,approved)}
        reject{ProviderStore.endpoint(approved,false)}
        reject{ProviderStore.endpoint("http://key@100.64.10.20:8000/v1",false,approved)}
        for(ending in listOf("\n","\r\n","\r")){
            val found=mutableListOf<String>();RemoteNetwork.sse(StringReader("\uFEFF: heartbeat$ending"+"event: fixture$ending"+"data: {\"x\":$ending"+"data: \"Привет🌍\"}$ending$ending")){e,d->verify(e=="fixture");found.add(d);true};verify(found.single()=="{\"x\":\n\"Привет🌍\"}")
        }
        val chat=RemoteProtocol.Stream("chat");chat.event("","{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Привет🌍\",\"reasoning_content\":\"мысль\"},\"finish_reason\":\"stop\"}]}")
        chat.event("","{\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":5}}");chat.event("","[DONE]");val result=chat.result();verify(result.getJSONObject("assistant").getString("content")=="Привет🌍");verify(result.getJSONObject("usage").getInt("completion_tokens")==5)
        reject{RemoteProtocol.Stream("chat").event("","[DONE]")};reject{RemoteProtocol.Stream("chat").event("","{\"choices\":[{\"delta\":{\"tool_calls\":[]}}]}")};reject{RemoteProtocol.Stream("chat").result()}
        val claude=RemoteProtocol.Stream("anthropic")
        listOf("""{"type":"message_start","message":{"usage":{"input_tokens":7}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"fixture reasoning"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"fixture-signature"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Ответ"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":8}}""",
            """{"type":"message_stop"}""").forEach {claude.event("",it)}
        val cm=claude.result().getJSONObject("assistant");verify(cm.getJSONArray("remote_payload").getJSONObject(0).getString("signature")=="fixture-signature")
        val profile=JSONObject().put("protocol","anthropic").put("options","{}").put("max_output",2048)
        val history=JSONArray().put(JSONObject().put("role","system").put("content","fixture system")).put(JSONObject().put("role","user").put("content","one")).put(cm).put(JSONObject().put("role","user").put("content","two"))
        val request=RemoteProtocol.request(profile,"fixture",history);verify(request.getString("system")=="fixture system");verify(request.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("signature")=="fixture-signature")
        val responses=RemoteProtocol.Stream("responses");responses.event("","""{"type":"response.output_text.delta","delta":"Ответ"}""")
        responses.event("","""{"type":"response.completed","response":{"status":"completed","output":[{"type":"reasoning","id":"rs_fixture","encrypted_content":"fixture-cipher","summary":[]},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Ответ"}]}],"usage":{"input_tokens":9,"output_tokens":10}}}""")
        profile.put("protocol","responses");history.put(2,responses.result().getJSONObject("assistant"));val rr=RemoteProtocol.request(profile,"fixture",history)
        verify(!rr.getBoolean("store"));verify(rr.getJSONArray("input").getJSONObject(1).getString("encrypted_content")=="fixture-cipher")
        val partial=RemoteProtocol.Stream("chat");partial.event("","""{"choices":[{"delta":{"content":"partial"}}]}""");reject{partial.event("","""{"error":{"message":"fixture error"}}""")};verify(partial.message().getString("content")=="partial");reject{partial.result()}
    }
    private class Server:Closeable {
        val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));val port get()=socket.localPort
        val requests=AtomicInteger();@Volatile var protocol="chat";@Volatile var json=false;@Volatile var status=200;@Volatile var abortBeforeHeaders=false;@Volatile var broken=false;@Volatile var malformed=false;@Volatile var delayMillis=0L;@Volatile var slow=false;@Volatile var redirect="";@Volatile var failure:Throwable?=null
        val bodies=CopyOnWriteArrayList<JSONObject>();private val pool=Executors.newCachedThreadPool();private val sockets=CopyOnWriteArrayList<Socket>()
        init {pool.execute {while(!socket.isClosed)try {val c=socket.accept();sockets.add(c);pool.execute {try {serve(c)}catch(f:Throwable){if(!slow && !socket.isClosed)failure=f}finally{sockets.remove(c);c.close()}}}catch(_:IOException){break}}}
        private fun serve(c:Socket){c.soTimeout=10000;val input=c.getInputStream();fun line():String {val b=ByteArrayOutputStream();while(true){val v=input.read();check(v>=0);if(v==10)break;check(b.size()<16384);b.write(v)};return String(b.toByteArray(),Charsets.US_ASCII).removeSuffix("\r")};val first=line();val h=mutableMapOf<String,String>();while(true){val l=line();if(l.isEmpty())break;h[l.substringBefore(':').lowercase()]=l.substringAfter(':').trim()};check(if(protocol=="anthropic")h["x-api-key"]=="motoai-fixture-key" && h["anthropic-version"]=="2023-06-01" && h["authorization"]==null else h["authorization"]=="Bearer motoai-fixture-key");requests.incrementAndGet()
            val size=h["content-length"]?.toInt()?:0;check(size in 0..2*1024*1024);val bytes=ByteArray(size);var at=0;while(at<size){val n=input.read(bytes,at,size-at);check(n>0);at+=n};if(size>0)bodies.add(JSONObject(String(bytes,Charsets.UTF_8)))
            if(abortBeforeHeaders)return
            val out=c.getOutputStream();if(redirect.isNotEmpty()){out.write("HTTP/1.1 302 Found\r\nLocation: $redirect\r\nContent-Length:0\r\nConnection:close\r\n\r\n".toByteArray());return}
            if(first.startsWith("GET ")){val b="""{"data":[{"id":"fixture-model"}]}""".toByteArray();out.write("HTTP/1.1 200 OK\r\nContent-Type:application/json\r\nContent-Length:${b.size}\r\nConnection:close\r\n\r\n".toByteArray());out.write(b);return}
            if(status!=200){val b="motoai-fixture-key SHOULD NOT LEAK".toByteArray();out.write("HTTP/1.1 $status Fixture\r\nContent-Type:application/json\r\nContent-Length:${b.size}\r\nConnection:close\r\n\r\n".toByteArray());out.write(b);return}
            val complete=when(protocol){
                "anthropic"->"""{"type":"message","role":"assistant","content":[{"type":"thinking","thinking":"Fixture thought","signature":"fixture-signature"},{"type":"text","text":"Привет 🌍"}],"stop_reason":"end_turn","usage":{"input_tokens":7,"output_tokens":3}}"""
                "responses"->"""{"status":"completed","output":[{"type":"reasoning","id":"rs_fixture","encrypted_content":"fixture-cipher","summary":[{"type":"summary_text","text":"Fixture thought"}]},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Привет 🌍"}]}],"usage":{"input_tokens":7,"output_tokens":3}}"""
                else->"""{"choices":[{"message":{"role":"assistant","content":"Привет 🌍"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":3}}"""}
            if(json){val b=complete.toByteArray(Charsets.UTF_8);out.write("HTTP/1.1 200 OK\r\nContent-Type:application/json\r\nContent-Length:${b.size}\r\nConnection:close\r\n\r\n".toByteArray());out.write(b);return}
            if(protocol!="chat") {
                out.write("HTTP/1.1 200 OK\r\nContent-Type:text/event-stream\r\nConnection:close\r\n\r\n".toByteArray())
                val events=if(protocol=="responses")listOf("""{"type":"response.output_text.delta","delta":"Привет 🌍"}""","""{"type":"response.completed","response":$complete}""")else listOf(
                    """{"type":"message_start","message":{"usage":{"input_tokens":7}}}""",
                    """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"Fixture thought","signature":"fixture-signature"}}""",
                    """{"type":"content_block_stop","index":0}""",
                    """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
                    """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Привет 🌍"}}""",
                    """{"type":"content_block_stop","index":1}""",
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}""",
                    """{"type":"message_stop"}""")
                events.forEach {out.write("data: $it\r\n\r\n".toByteArray(Charsets.UTF_8));out.flush()};return
            }
            out.write("HTTP/1.1 200 OK\r\nContent-Type:text/event-stream\r\nConnection:close\r\n\r\n".toByteArray())
            val begin=""": fixture heartbeat

data: {"choices":[{"index":0,"delta":{"content":"Привет 🌍"}}]}

""".toByteArray(Charsets.UTF_8)
            begin.forEach {out.write(it.toInt());out.flush()}
            if(slow){Thread.sleep(10000);return};if(delayMillis>0)Thread.sleep(delayMillis);if(broken)return;if(malformed){out.write(byteArrayOf(0xc3.toByte(),0x28));out.flush();return}
            out.write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n".toByteArray());out.flush()
        }
        override fun close(){socket.close();sockets.forEach {runCatching {it.close()}};pool.shutdownNow()}
    }
    fun run(background:Boolean=false,theme:Int?=null):JSONObject {
        protocolChecks();var screenOff=false;var notificationTap=false;val context=test.targetContext;val prefs=context.getSharedPreferences("preferences",Context.MODE_PRIVATE);val oldTheme=if(prefs.contains("theme"))prefs.getInt("theme",0)else null;if(theme!=null)prefs.edit().putInt("theme",theme).commit();val ids=mutableListOf<String>();val chats=mutableListOf<String>();var fixtureFolder="";var restoreNever=false;val replies=ConcurrentHashMap<String,CompletableFuture<JSONObject>>();lateinit var remote:Messenger
        val connected=CountDownLatch(1);var snapshot=JSONObject()
        val receiver=Messenger(Handler(Looper.getMainLooper()){m->m.data.getString("snapshot")?.let {snapshot=JSONObject(it)};m.data.getString("product_id")?.let {id->m.data.getParcelable<ParcelFileDescriptor>("payload")?.let {fd->ProductPipe.read(fd,4*1024*1024){r->r.fold({replies.remove(id)?.complete(JSONObject(String(it,Charsets.UTF_8)))},{replies.remove(id)?.completeExceptionally(it)})}}};true})
        val connection=object:ServiceConnection {override fun onServiceConnected(n:ComponentName,b:IBinder){remote=Messenger(b);remote.send(Message.obtain(null,RuntimeService.REGISTER).apply {replyTo=receiver});connected.countDown()};override fun onServiceDisconnected(n:ComponentName){}}
        ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("cmd statusbar collapse")).use {it.readBytes()}
        val activity=test.startActivitySync(Intent(context,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));check(context.bindService(Intent(context,RuntimeService::class.java),connection,Context.BIND_AUTO_CREATE));check(connected.await(10,TimeUnit.SECONDS))
        fun call(body:JSONObject,ok:Boolean=true):JSONObject {val id=UUID.randomUUID().toString();val future=CompletableFuture<JSONObject>();replies[id]=future;val fd=ProductPipe.write(body.toString().toByteArray(Charsets.UTF_8));try{remote.send(Message.obtain(null,RuntimeService.PRODUCT).apply {data=Bundle().apply {putString("id",id);putParcelable("payload",fd)};replyTo=receiver})}finally{fd.close()};val r=future.get(25,TimeUnit.SECONDS);if(ok)check(r.getBoolean("ok")){r.optString("message")};return r}
        fun get(id:String)=call(JSONObject().put("action","get").put("chat_id",id)).getJSONObject("chat")
        fun waitFor(seconds:Int=15,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+seconds*1000;while(!condition()){check(SystemClock.elapsedRealtime()<end){"Provider fixture deadline"};Thread.sleep(40)}}
        fun send(id:String,text:String){call(JSONObject().put("action","remote_send").put("chat_id",id).put("request_id",UUID.randomUUID().toString()).put("text",text))}
        try {
            // Recover only this suite's explicitly named public fixtures after a main-thread crash.
            // Folder removal moves any content to All; never clear the owner's history.
            val old=call(JSONObject().put("action","providers")).getJSONArray("providers")
            for(i in 0 until old.length()){val p=old.getJSONObject(i);if(p.optString("name").matches(Regex("Fixture API(?: [0-9a-f]{8}(?: renamed)?)?")) && p.optString("base_url").startsWith("http://127.0.0.1:") && p.optString("model")=="fixture-model")call(JSONObject().put("action","provider_delete").put("provider_id",p.getString("id")))}
            val oldFolders=call(JSONObject().put("action","history")).getJSONObject("history").getJSONArray("folders")
            for(i in 0 until oldFolders.length()){val f=oldFolders.getJSONObject(i);if(f.getString("name").matches(Regex("Fixture [0-9a-f]{8}")))call(JSONObject().put("action","folder").put("folder_id",f.getString("id")).put("remove",true))}
            Server().use {server->
            val providerName="Fixture API "+UUID.randomUUID().toString().take(8)
            val spec=JSONObject().put("name",providerName).put("base_url","http://127.0.0.1:${server.port}/v1").put("allow_local_http",true).put("protocol","chat").put("model","fixture-model").put("max_output",2048).put("options","{}").put("key","motoai-fixture-key").put("save_key",true)
            val profile=call(JSONObject().put("action","provider_save").put("profile",spec)).getJSONObject("provider");val pid=profile.getString("id");ids.add(pid)
            verify(!profile.toString().contains("motoai-fixture-key") && !profile.has("secret"));verify(profile.getBoolean("has_key"))
            verify(!File(context.applicationInfo.dataDir,"shared_prefs/providers-v1.xml").readText().contains("motoai-fixture-key"))
            val catalog=call(JSONObject().put("action","provider_models").put("provider_id",pid)).getJSONArray("models");verify(catalog.getString(0)=="fixture-model")
            val chat=call(JSONObject().put("action","remote_new").put("provider_id",pid).put("model","fixture-model")).getJSONObject("chat");val cid=chat.getString("id");chats.add(cid)
            send(cid,"Fixture one");waitFor { !get(cid).getBoolean("active")};verify(get(cid).getJSONArray("messages").getJSONObject(1).getString("content")=="Привет 🌍");verify(snapshot.optString("model").isEmpty())
            send(cid,"Fixture two");waitFor { !get(cid).getBoolean("active")};val history=server.bodies.last().getJSONArray("messages");verify(history.length()==4);verify(history.getJSONObject(2).getString("content")=="Привет 🌍")
            fun views(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
            fun field():EditText=views(activity.window.decorView).filterIsInstance<EditText>().single {it.hint.toString()=="Сообщение"}
            fun button(description:String)=views(activity.window.decorView).filterIsInstance<Button>().single {it.contentDescription?.toString()==description}
            fun current():JSONObject {var value=JSONObject();test.runOnMainSync {val f=ChatActivity::class.java.getDeclaredField("current");f.isAccessible=true;value=JSONObject((f.get(activity) as JSONObject).toString())};return value}
            fun clickText(text:String) {
                test.waitForIdleSync();runCatching {test.uiAutomation.waitForIdle(250,3000)};test.uiAutomation.clearCache()
                try {waitFor {val root=test.uiAutomation.rootInActiveWindow?:return@waitFor false;check(root.packageName?.toString()==context.packageName) {"Only MotoAI UI fixture may be inspected"}
                    root.refresh()
                    val candidates=root.findAccessibilityNodeInfosByText(text).filter {it.isVisibleToUser && it.isEnabled && it.className?.toString()!="android.widget.EditText" && (it.text?.toString()?.equals(text,true)==true || it.contentDescription?.toString()?.equals(text,true)==true)}.sortedByDescending {it.isClickable}
                    val node=candidates.firstOrNull {val b=android.graphics.Rect();it.getBoundsInScreen(b);!b.isEmpty}?:return@waitFor false
                    val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds);val now=SystemClock.uptimeMillis();test.sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0));test.sendPointerSync(MotionEvent.obtain(now,now+40,MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0));true}}catch(f:Exception){val root=test.uiAutomation.rootInActiveWindow;val known=listOf("Активные модели","Нет активных моделей","Выбрать другую модель?","Fixture API · fixture-model","Настройки","Новый диалог").filter {root?.findAccessibilityNodeInfosByText(it)?.isNotEmpty()==true};error("UI fixture could not click $text; visible known labels=$known; source=${current().optString("model")}; profiles=${call(JSONObject().put("action","providers")).getJSONArray("providers").length()}")}
            }
            fun capture(name:String) {if(Build.HARDWARE!="ranchu")return;test.waitForIdleSync();waitFor {test.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName};Thread.sleep(200);test.waitForIdleSync();test.uiAutomation.takeScreenshot()?.let {b->File(context.filesDir,"provider-ui-$name.png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
            fun newUi(permanent:Boolean=false):String {
                val previous=current().optString("id")
                test.runOnMainSync {val f=ChatActivity::class.java.getDeclaredMethod("showTab",Int::class.javaPrimitiveType);f.isAccessible=true;f.invoke(activity,0)}
                clickText("Новый диалог");clickText("Модель нового диалога");clickText(ProviderStore.displayName(spec,"fixture-model"))
                if(permanent){clickText("Постоянный · сохранить на устройстве");val mode=call(JSONObject().put("action","history")).getJSONObject("history").getString("mode");if(mode=="never"){clickText("Разрешить хранение постоянных диалогов");restoreNever=true;clickText("Включить и создать")}else clickText("Создать")}else clickText("Создать")
                waitFor {current().optString("id").isNotEmpty() && current().optString("id")!=previous && current().optJSONObject("remote")!=null}
                val blank=current();capture("empty");val id=blank.getString("id");chats.add(id);verify(blank.getJSONArray("messages").length()==0 && blank.getBoolean("permanent")==permanent);return id
            }
            val ui=newUi();val count=server.requests.get()
            test.runOnMainSync {field().setText("UI Enter fixture ☕");val editor=android.view.inputmethod.EditorInfo();check(field().onCreateInputConnection(editor)!=null);verify(editor.imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION==android.view.inputmethod.EditorInfo.IME_ACTION_SEND && editor.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION==0)}
            progress.put("stage","wait composer enabled")
            waitFor {var enabled=false;test.runOnMainSync {enabled=button("Отправить").isEnabled};enabled}
            test.runOnMainSync {field().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));field().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER))}
            progress.put("stage","wait service completion")
            waitFor {val c=get(ui);progress.put("message_count",c.getJSONArray("messages").length()).put("active",c.optBoolean("active")).put("requests",server.requests.get());c.getJSONArray("messages").length()==2 && !c.getBoolean("active")}
            progress.put("stage","wait visible final message")
            waitFor {var ready=false;test.runOnMainSync {ready=views(activity.window.decorView).filterIsInstance<TextView>().any {it.isShown && it.text.toString()=="Привет 🌍"};val f=ChatActivity::class.java.getDeclaredField("screen");f.isAccessible=true;progress.put("screen",f.get(activity));val c=ChatActivity::class.java.getDeclaredField("current");c.isAccessible=true;progress.put("ui_count",(c.get(activity) as JSONObject).optJSONArray("messages")?.length())};ready}
            test.waitForIdleSync();capture("answer");verify(server.requests.get()==count+1);verify(get(ui).getJSONArray("messages").getJSONObject(0).getString("content")=="UI Enter fixture ☕")
            // Real message actions, search and the new three-tab messenger navigation.
            test.runOnMainSync {views(activity.window.decorView).filterIsInstance<TextView>().first {it.isShown && it.text.toString()=="Привет 🌍"}.performLongClick()};clickText("Закрепить")
            waitFor {get(ui).getJSONArray("pinned").length()==1};waitFor {current().optJSONArray("pinned")?.length()==1};verify(true)
            test.runOnMainSync {button("К закреплённому сообщению").performClick()};test.runOnMainSync {button("Открепить сообщение").performClick()};waitFor {get(ui).getJSONArray("pinned").length()==0};verify(true)
            clickText("Поиск в диалоге");test.runOnMainSync {views(activity.window.decorView).filterIsInstance<EditText>().single {it.hint.toString()=="Поиск в диалоге"}.setText("Привет")}
            waitFor {var found=false;test.runOnMainSync {found=views(activity.window.decorView).filterIsInstance<TextView>().any {it.text.toString()=="1/1"}};found};verify(true);clickText("Закрыть поиск в диалоге")
            clickText("О модели");waitFor {test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("По API · $providerName")?.isNotEmpty()==true};verify(true);clickText("Готово")
            clickText("Назад к диалогам");waitFor {var shown=false;test.runOnMainSync {shown=views(activity.window.decorView).filterIsInstance<TextView>().any {it.isShown && it.text.toString()=="UI Enter fixture ☕"}};shown};capture("drawer");clickText("Поиск по диалогам")
            test.runOnMainSync {views(activity.window.decorView).filterIsInstance<EditText>().single {it.hint.toString()=="Поиск по диалогам"}.setText("UI Enter fixture")}
            waitFor {var count=0;test.runOnMainSync {count=views(activity.window.decorView).filterIsInstance<ListView>().filter {it.isShown}.sumOf {it.count}};count==1};verify(true);clickText("Закрыть поиск")
            clickText("Настройки");waitFor {test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Подключения по API")?.isNotEmpty()==true};capture("settings");verify(true)
            clickText("Хранение диалогов");waitFor {test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Автоудаление")?.isNotEmpty()==true};verify(true);clickText("Назад")
            clickText("Локальный API");waitFor {var shown=false;test.runOnMainSync {shown=views(activity.window.decorView).filterIsInstance<TextView>().any {it.isShown && it.text.toString()=="Сервер выключен"}};shown};capture("api");verify(true)
            clickText("Диалоги")
            val folderName="Fixture "+UUID.randomUUID().toString().take(8)
            clickText("Создать папку")
            waitFor {val node=test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Название папки")?.firstOrNull {it.className?.toString()=="android.widget.EditText"}?:return@waitFor false
                node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,folderName)})}
            clickText("Сохранить")
            waitFor {val a=call(JSONObject().put("action","history")).getJSONObject("history").getJSONArray("folders");fixtureFolder=(0 until a.length()).map {a.getJSONObject(it)}.firstOrNull {it.getString("name")==folderName}?.getString("id")?:"";fixtureFolder.isNotEmpty()};clickText(folderName)
            waitFor {var selected="";test.runOnMainSync {val f=ChatActivity::class.java.getDeclaredField("home");f.isAccessible=true;selected=(f.get(activity) as MessengerHome).folder};progress.put("selected_folder",selected).put("fixture_folder",fixtureFolder);selected==fixtureFolder};capture("folder");verify(true)
            if(Build.HARDWARE=="ranchu") {
                val storedBefore=call(JSONObject().put("action","history")).getJSONObject("history");check(storedBefore.getInt("saved_count")==0){"Public emulator fixture requires no user permanent history"}
                val permanent=newUi(true);verify(get(permanent).getBoolean("permanent") && get(permanent).getString("folder_id")==fixtureFolder)
                test.runOnMainSync {field().setText("Permanent fixture draft 🦉")}
                waitFor {get(permanent).getString("draft")=="Permanent fixture draft 🦉"};verify(true)
                clickText("Назад к диалогам");call(JSONObject().put("action","delete").put("chat_id",permanent));chats.remove(permanent)
            }
            val stopUi=newUi();verify(get(stopUi).getString("folder_id")==fixtureFolder);server.slow=true;val stopCount=server.requests.get()
            test.runOnMainSync {field().setText("line1");field().setSelection(field().length());field().dispatchKeyEvent(KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER,0,KeyEvent.META_SHIFT_ON));field().dispatchKeyEvent(KeyEvent(0,0,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER,0,KeyEvent.META_SHIFT_ON));verify(field().text.toString().contains("\n"));field().setText("UI Stop fixture");button("Отправить").performClick()}
            waitFor {get(stopUi).getJSONArray("messages").length()==2 && get(stopUi).getJSONArray("messages").getJSONObject(1).getString("content").isNotEmpty()}
            test.runOnMainSync {val stop=button("Остановить");verify(stop.text.toString()=="■" && stop.isEnabled);stop.performClick()}
            waitFor(5){!get(stopUi).getBoolean("active")};verify(server.requests.get()==stopCount+1);server.slow=false
            for(id in listOf(ui,stopUi)){call(JSONObject().put("action","delete").put("chat_id",id));chats.remove(id)}
            server.slow=true;val cancel=call(JSONObject().put("action","remote_new").put("provider_id",pid).put("model","fixture-model")).getJSONObject("chat").getString("id");chats.add(cancel);val before=server.requests.get()
            send(cancel,"Fixture cancellation");waitFor {get(cancel).getJSONArray("messages").getJSONObject(1).getString("content").isNotEmpty()}
            remote.send(Message.obtain(null,RuntimeService.STOP));waitFor(5){!get(cancel).getBoolean("active")};verify(server.requests.get()==before+1);verify(get(cancel).getJSONArray("messages").getJSONObject(1).getString("status")=="interrupted")
            val refused=call(JSONObject().put("action","remote_send").put("chat_id",cancel).put("request_id",UUID.randomUUID().toString()).put("text","No hidden partial reasoning replay"),false);verify(!refused.getBoolean("ok"));server.slow=false
            val beforeRename=call(JSONObject().put("action","providers")).getJSONArray("providers").let {a->(0 until a.length()).map {a.getJSONObject(it)}.single {it.getString("id")==pid}.getString("revision")}
            spec.put("id",pid).put("name",providerName+" renamed");val renamed=call(JSONObject().put("action","provider_save").put("profile",spec)).getJSONObject("provider");verify(renamed.getString("revision")==beforeRename)
            spec.put("id",pid).put("name","Fixture changed").put("options","{\"temperature\":0.1}");call(JSONObject().put("action","provider_save").put("profile",spec));val refusedOld=call(JSONObject().put("action","remote_send").put("chat_id",cid).put("request_id",UUID.randomUUID().toString()).put("text","No stale destination"),false);verify(!refusedOld.getBoolean("ok"))
            val blank=call(JSONObject().put("action","blank")).getJSONObject("chat");chats.add(blank.getString("id"));verify(blank.getString("model").isEmpty() && blank.getJSONArray("messages").length()==0)
            // Native protocol HTTP headers, JSON fallback and same-chat opaque state across two turns.
            for(protocol in listOf("anthropic","responses")) {
                server.protocol=protocol;spec.remove("id");spec.put("protocol",protocol).put("name","Fixture $protocol")
                val np=call(JSONObject().put("action","provider_save").put("profile",spec)).getJSONObject("provider");val nid=np.getString("id");ids.add(nid)
                val nc=call(JSONObject().put("action","remote_new").put("provider_id",nid).put("model","fixture-model")).getJSONObject("chat").getString("id");chats.add(nc)
                send(nc,"Native protocol first");waitFor {!get(nc).getBoolean("active")};verify(get(nc).getJSONArray("messages").getJSONObject(1).getString("content")=="Привет 🌍")
                server.json=true;send(nc,"Native protocol second JSON");waitFor {!get(nc).getBoolean("active")}
                val assistant=get(nc).getJSONArray("messages").getJSONObject(3);verify(assistant.getString("status")=="eog" && assistant.getString("content")=="Привет 🌍" && assistant.getString("reasoning_content")=="Fixture thought")
                val request=server.bodies.last();if(protocol=="anthropic")verify(request.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("signature")=="fixture-signature")
                else verify(request.getJSONArray("input").getJSONObject(1).getString("encrypted_content")=="fixture-cipher" && !request.getBoolean("store"))
                call(JSONObject().put("action","delete").put("chat_id",nc));chats.remove(nc);server.json=false
            }
            server.protocol="chat"
            val direct=JSONObject().put("base_url","http://127.0.0.1:${server.port}/v1").put("allow_local_http",true).put("protocol","chat").put("max_output",2048).put("options","{}").put("secret",JSONObject().put("key","motoai-fixture-key"))
            val input=JSONArray().put(JSONObject().put("role","user").put("content","Fixture network errors"))
            for(code in listOf(401,429,503)) {server.status=code;val n=server.requests.get();val e=runCatching {RemoteNetwork().generate(direct,"fixture-model",input){}}.exceptionOrNull();verify(e is RemoteFailure && !e.message.orEmpty().contains("motoai-fixture-key"));verify(server.requests.get()==n+1)};server.status=200
            Server().use {other->server.redirect="http://127.0.0.1:${other.port}/steal";reject {RemoteNetwork().generate(direct,"fixture-model",input){}};verify(other.requests.get()==0 && other.failure==null)};server.redirect=""
            server.abortBeforeHeaders=true;val once=server.requests.get();reject {RemoteNetwork().generate(direct,"fixture-model",input){}};verify(server.requests.get()==once+1);server.abortBeforeHeaders=false
            for(invalid in listOf(false,true)){server.broken=!invalid;server.malformed=invalid;var partial="";reject {RemoteNetwork().generate(direct,"fixture-model",input){partial=it.getString("content")}};verify(partial=="Привет 🌍")};server.broken=false;server.malformed=false
            server.json=true;verify(RemoteNetwork().generate(direct,"fixture-model",input){}.getString("finish_reason")=="eog");server.json=false
            if(background) {
                if(Build.HARDWARE=="ranchu")context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.filter {it.id==AnswerNotifications.ID}.forEach {context.getSystemService(android.app.NotificationManager::class.java).cancel(it.tag,it.id)} // Public emulator contains only this suite's fixture answers.
                val nc=call(JSONObject().put("action","remote_new").put("provider_id",pid).put("model","fixture-model")).getJSONObject("chat").getString("id");chats.add(nc)
                server.delayMillis=3000;val before=server.requests.get();send(nc,"Public screen-off API fixture")
                fun shell(command:String){ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).use {it.readBytes()}}
                test.runOnMainSync {check(activity.moveTaskToBack(true))};shell("input keyevent KEYCODE_HOME");shell("input keyevent KEYCODE_SLEEP")
                waitFor {!get(nc).getBoolean("active")};verify(!context.getSystemService(PowerManager::class.java).isInteractive);verify(get(nc).getJSONArray("messages").getJSONObject(1).getString("status")=="eog" && server.requests.get()==before+1)
                screenOff=true;val answer=get(nc).getString("answer_id");val notifications=context.getSystemService(android.app.NotificationManager::class.java);val granted=notifications.areNotificationsEnabled()
                if(granted)waitFor {notifications.activeNotifications.any {it.tag==answer && it.id==AnswerNotifications.ID}}
                else {Thread.sleep(800);verify(notifications.activeNotifications.none {it.tag==answer})}
                shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
                val keyguard=context.getSystemService(android.app.KeyguardManager::class.java);val signal=File(context.filesDir,"background-owner-unlock.json")
                try {if(keyguard.isDeviceLocked || keyguard.isKeyguardLocked)signal.writeText("{\"waiting_for_owner_unlock\":true}");waitFor(180){!keyguard.isDeviceLocked && !keyguard.isKeyguardLocked}}finally{signal.delete()}
                Thread.sleep(500) // Wait for ordinary keyguard exit before expanding the shade.
                if(granted) {
                    val appLabel=context.applicationInfo.loadLabel(context.packageManager).toString()
                    shell("cmd statusbar expand-notifications")
                    Thread.sleep(700) // SystemUI publishes node bounds before the shade animation finishes.
                    waitFor {val root=test.uiAutomation.rootInActiveWindow?:return@waitFor false
                        root.refresh()
                        fun descendants(n:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> = listOf(n)+(0 until n.childCount).flatMap {n.getChild(it)?.let {v->descendants(v)}?:emptyList()}
                        val parents=mutableMapOf<android.view.accessibility.AccessibilityNodeInfo,android.view.accessibility.AccessibilityNodeInfo>()
                        fun walk(n:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> = listOf(n)+(0 until n.childCount).flatMap {n.getChild(it)?.let {c->parents[c]=n;walk(c)}?:emptyList()}
                        val all=walk(root)
                        File(context.filesDir,"provider-notification-diagnostic.json").writeText(JSONObject().put("root",root.packageName).put("titles",all.count {it.text?.toString()=="Ответ готов"}).put("own_labels",all.count {it.text?.toString()==appLabel}).put("node_count",all.size).toString())
                        for(title in all.filter {it.text?.toString()=="Ответ готов"}) {
                            var node:android.view.accessibility.AccessibilityNodeInfo?=title
                            repeat(12){val n=node?:return@repeat;val children=descendants(n)
                                if(children.any {it.text?.toString()==appLabel} && children.count {it.text?.toString()=="Ответ готов"}==1){
                                    val b=android.graphics.Rect();title.getBoundsInScreen(b);if(!b.isEmpty){val t=SystemClock.uptimeMillis();val down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,b.exactCenterX(),b.exactCenterY(),0);val up=MotionEvent.obtain(t,t+40,MotionEvent.ACTION_UP,b.exactCenterX(),b.exactCenterY(),0)
                                        try{check(test.uiAutomation.injectInputEvent(down,true));check(test.uiAutomation.injectInputEvent(up,true))}finally{down.recycle();up.recycle()};return@waitFor true}
                                };node=parents[n]
                            }
                        }
                        false}
                    waitFor {current().optString("id")==nc};verify(current().getJSONArray("messages").getJSONObject(1).getString("content")=="Привет 🌍")
                    waitFor {notifications.activeNotifications.none {it.tag==answer}};notificationTap=true;verify(true)
                }
                server.delayMillis=0
            }
            verify(server.failure==null)
        }} catch(f:Throwable) {
            if(Build.HARDWARE=="ranchu")test.uiAutomation.takeScreenshot()?.let {b->File(context.filesDir,"provider-ui-failure.png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            throw f
        } finally {
            for(id in chats)runCatching {get(id).optString("answer_id").takeIf {it.isNotEmpty()}?.let {context.getSystemService(android.app.NotificationManager::class.java).cancel(it,AnswerNotifications.ID)};call(JSONObject().put("action","delete").put("chat_id",id))}
            for(id in ids)runCatching {call(JSONObject().put("action","provider_delete").put("provider_id",id))}
            if(fixtureFolder.isNotEmpty())runCatching {call(JSONObject().put("action","folder").put("folder_id",fixtureFolder).put("remove",true))}
            if(restoreNever)runCatching {check(call(JSONObject().put("action","history")).getJSONObject("history").getInt("saved_count")==0);call(JSONObject().put("action","history_policy").put("mode","never").put("confirm_clear",true))}
            if(background)runCatching {ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP")).use {it.readBytes()};ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("cmd statusbar collapse")).use {it.readBytes()}};context.unbindService(connection);test.runOnMainSync {activity.finish()};if(theme!=null){val e=prefs.edit();if(oldTheme==null)e.remove("theme")else e.putInt("theme",oldTheme);e.commit()}
        }
        return JSONObject().put("passed",true).put("checks",checks).put("background",background).put("screen_off_completed",screenOff).put("notification_tap",notificationTap).put("theme",theme?:JSONObject.NULL).put("scope","Messenger home/settings/API, new temporary model picker, search and pin, Enter/ShiftEnter/arrow→Stop UI; public loopback fixtures: endpoint/SSE Unicode+CRLF/CR, three protocol adapters, signed/encrypted reasoning replay, encrypted keys, catalog, real two-turn remote service without GGUF, cancellation/no retry, stale endpoint and partial-state rejection. Not real cloud account/provider inference acceptance.")
    }
}
