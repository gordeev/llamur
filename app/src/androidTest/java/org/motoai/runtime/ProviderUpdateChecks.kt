package org.motoai.runtime

import android.app.Instrumentation
import android.content.*
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.*
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.*

/** Real HTTP/JSON/SSE/SQLite and messenger UI fixtures in the separate debug package. */
internal class ProviderUpdateChecks(private val test:Instrumentation) {
    private var checks=0
    private val context=test.targetContext
    private fun verify(ok:Boolean,why:String){check(ok){why};checks++}
    private fun rejects(why:String,block:()->Unit){verify(runCatching(block).isFailure,why)}
    private class Server(private val host:String="127.0.0.1"):Closeable {
        val socket=ServerSocket(0,8,InetAddress.getByName(host));val port get()=socket.localPort
        @Volatile var status=200;@Volatile var reply="{}";@Volatile var type="application/json";@Volatile var received=JSONObject();var calls=0
        private val executor=Executors.newSingleThreadExecutor()
        init {executor.execute {while(!socket.isClosed)try {socket.accept().use {c->c.soTimeout=10000;val input=c.getInputStream()
            fun line():String {val b=ByteArrayOutputStream();while(true){val n=input.read();if(n<0 || n==10)break;b.write(n)};return b.toString("US-ASCII").trimEnd('\r')}
            line();var size=0;while(true){val h=line();if(h.isEmpty())break;if(h.startsWith("Content-Length:",true))size=h.substringAfter(':').trim().toInt()};require(size in 0..ChatFiles.REQUEST_BYTES);val bytes=ByteArray(size);DataInputStream(input).readFully(bytes);if(size>0)received=JSONObject(String(bytes,Charsets.UTF_8));calls++
            val body=reply.toByteArray();c.getOutputStream().apply {write("HTTP/1.1 $status Fixture\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}
        }}catch(_:Exception){if(socket.isClosed)break}}}
        fun profile(protocol:String="chat")=JSONObject().put("base_url","http://$host:$port").put("allow_local_http",true).put("protocol",protocol).put("options","{}").put("max_output",2048).put("secret",JSONObject().put("key","fixture-private-key"))
        override fun close(){socket.close();executor.shutdownNow()}
    }
    fun run(live:Boolean):JSONObject {
        verify(context.packageName.endsWith(".debug"),"Fixtures must not modify release data")
        val full=JSONArray();repeat(500){n->full.put(JSONObject().put("id","fixture/model-$n").put("name","Fixture $n").put("architecture",JSONObject().put("input_modalities",JSONArray().put("text").put("image")).put("output_modalities",JSONArray().put("text"))).put("supported_parameters",JSONArray().put("reasoning")).put("unused",JSONArray().also {a->repeat(90){a.put(it)}}))}
        val large=JSONObject().put("data",full).toString();rejects("Default JSON safety budget should stay bounded"){StrictJson.parse(large.toByteArray())}
        Server().use {server->server.reply=large;val catalog=RemoteNetwork().catalogue(server.profile());verify(catalog.getJSONArray("models").length()==500,"Large catalogue must load");verify(ModelCapabilities.has(catalog.getJSONObject("details").getJSONObject("fixture/model-1"),"input","image"),"Catalogue must retain image capability");verify(!catalog.toString().contains("unused"),"Unused metadata must not enter cached profiles")
            server.status=400;server.reply="""{"error":{"code":400,"message":"fixture-private-key rejected: invalid model ID"}}""";val error=runCatching {RemoteNetwork().models(server.profile())}.exceptionOrNull()?.message.orEmpty();verify("HTTP 400" in error && "invalid model ID" in error && "fixture-private-key" !in error,"HTTP errors need useful, redacted details")
            server.reply="""{"error":{"code":404,"message":"Free model training violation (account settings)"}}""";val privacy=runCatching {RemoteNetwork().models(server.profile())}.exceptionOrNull()?.message.orEmpty();verify("settings/privacy" in privacy,"OpenRouter guardrail must be explained")
            server.status=200;server.type="text/event-stream";server.reply="data: {\"error\":{\"code\":400,\"message\":\"fixture-private-key invalid parameter\"}}\n\n"
            val before=server.calls;val streamError=runCatching {RemoteNetwork().generate(server.profile(),"fixture",JSONArray()) {}}.exceptionOrNull()?.message.orEmpty();verify("invalid parameter" in streamError && "fixture-private-key" !in streamError && server.calls==before+1,"SSE errors must redact secrets and never retry")
        }
        val host=NetworkInterface.getNetworkInterfaces().toList().flatMap {it.inetAddresses.toList()}.first {it is Inet4Address && !it.isLoopbackAddress}.hostAddress!!
        Server(host).use {server->server.reply="""{"data":[{"id":"fixture-http"}]}""";val p=server.profile();val base=p.getString("base_url")
            rejects("Non-loopback HTTP must be blocked without exact approval"){RemoteNetwork().models(p)};rejects("Unapproved chat must also be blocked before network"){RemoteNetwork().generate(p,"fixture-http",JSONArray()) {}};verify(server.calls==0,"Unapproved request must not reach network")
            p.put("allow_http_url",base);verify(RemoteNetwork().models(p).getString(0)=="fixture-http" && server.calls==1,"Approved non-loopback HTTP must pass actual Android networking")
            server.reply="""{"choices":[{"message":{"role":"assistant","content":"HTTP fixture OK"},"finish_reason":"stop"}]}"""
            val completion=RemoteNetwork().generate(p,"fixture-http",JSONArray().put(JSONObject().put("role","user").put("content","Public fixture"))) {}
            verify(completion.getJSONObject("assistant").getString("content")=="HTTP fixture OK" && server.calls==2,"Approved non-loopback chat must complete through actual HTTP")
            val changed=JSONObject(p.toString()).put("base_url",base+"/other");rejects("Stale approval must not follow changed endpoint"){RemoteNetwork().models(changed)};verify(server.calls==2,"Changed-address rejection must happen before network")
            test.runOnMainSync {val store=ProviderStore(context);val spec=JSONObject(p.toString()).put("name","HTTP public fixture").put("requires_key",false).put("model","fixture-http")
                val saved=store.save(spec);val id=saved.getString("id")
                try {verify(ProviderStore(context).public(id).getString("allow_http_url")==base,"HTTP approval must survive profile reload")
                    spec.put("id",id).put("name","HTTP renamed fixture");verify(store.save(spec).getString("revision")==saved.getString("revision"),"Renaming must preserve approved source identity")
                    spec.put("base_url",base+"/other");rejects("Stale approval must not save a changed endpoint"){store.save(spec)}
                    verify(store.public(id).getString("base_url")==base,"Rejected edit must preserve previous connection")
                }finally {store.delete(id)}
            }
            p.put("allow_http_url","");rejects("Removing approval blocks new HTTP requests"){RemoteNetwork().models(p)}
        }
        val bitmap=Bitmap.createBitmap(700,700,Bitmap.Config.ARGB_8888);val random=Random(71);val pixels=IntArray(700*700){random.nextInt() or 0xff000000.toInt()};bitmap.setPixels(pixels,0,700,0,0,700,700);val png=ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val encoded=Base64.getEncoder().encodeToString(png);verify(encoded.length>2*1024*1024 && png.size<ChatFiles.FILE_BYTES,"Fixture must exercise former SSE/CursorWindow limits")
        val image=JSONObject().put("name","fixture.png").put("mime","image/png").put("data",encoded)
        val txt=JSONObject().put("name","test.txt").put("mime","text/plain").put("text","UTF-8 Привет 🌍")
        val files=JSONArray().put(image).put(txt);val caps=JSONObject().put("input",JSONArray().put("text").put("image").put("file"))
        verify(ChatFiles.validate(files,JSONObject().put("capabilities",caps)).length()==2,"Allowed image/text attachments must validate")
        rejects("Local runtime must not pretend to support image input"){ChatFiles.validate(JSONArray().put(image),null)}
        for(protocol in listOf("chat","responses","anthropic")){val parts=ChatFiles.parts("look",files,protocol) as JSONArray;verify(parts.length()==2,"Text and image parts must be encoded");val binary=parts.getJSONObject(1);verify(when(protocol){"chat"->binary.getJSONObject("image_url").getString("url").endsWith(encoded);"responses"->binary.getString("image_url").endsWith(encoded);else->binary.getJSONObject("source").getString("data")==encoded},"Binary image must remain unchanged in $protocol")}
        val pdf=JSONArray().put(JSONObject().put("name","a.pdf").put("mime","application/pdf").put("data",Base64.getEncoder().encodeToString("%PDF-fixture".toByteArray())))
        for(protocol in listOf("chat","responses","anthropic"))verify((ChatFiles.parts("PDF",pdf,protocol) as JSONArray).getJSONObject(1).getString("type")==when(protocol){"chat"->"file";"responses"->"input_file";else->"document"},"PDF encoder $protocol")
        val history=JSONArray().put(JSONObject().put("role","user").put("content","look").put("attachments",files))
        val nullableError=RemoteProtocol.Stream("chat");nullableError.json(JSONObject("""{"error":null,"choices":[{"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}]}"""));verify(nullableError.result().getJSONObject("assistant").getString("content")=="OK","JSON error:null is not an API failure")
        val p=JSONObject().put("base_url","https://openrouter.ai/api/v1").put("protocol","chat").put("max_output",2048).put("options","{}").put("reasoning_mode","off")
        verify(!RemoteProtocol.request(p,"fixture",history).getJSONObject("reasoning").getBoolean("enabled"),"OpenRouter reasoning off must reach request")
        p.put("capabilities",JSONObject().put("reasoning_mandatory",true));rejects("Mandatory reasoning cannot silently be disabled"){RemoteProtocol.request(p,"fixture",history)}
        val url="data:image/png;base64,$encoded";val event=JSONObject().put("choices",JSONArray().put(JSONObject().put("index",0).put("delta",JSONObject().put("images",JSONArray().put(JSONObject().put("image_url",JSONObject().put("url",url)))))))
        Server().use {server->server.type="text/event-stream";server.reply="data: $event\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";val result=RemoteNetwork().generate(server.profile(),"fixture",JSONArray().put(JSONObject().put("role","user").put("content","draw"))){};val output=result.getJSONObject("assistant").getJSONArray("attachments").getJSONObject(0);verify(ChatFiles.bytes(output).contentEquals(png),"Large generated image must survive actual HTTP/SSE byte-for-byte")}
        val exported=ChatFiles.exports(JSONObject().put("role","assistant").put("content","```python\nprint('Привет')\n```"));verify(exported.single().getString("name").endsWith(".py") && String(ChatFiles.bytes(exported.single())).contains("Привет"),"Code must export without execution or lost UTF-8")
        val id=UUID.randomUUID().toString();var disk=HistoryDisk(context,"fixture-files-$id.db","fixture-files-$id");val store=ChatStore(disk);store.policy("keep",86400000);val chat=store.create("remote:fixture",JSONObject().put("capabilities",caps),true);val cid=chat.getString("id")
        try {store.begin(cid,"fixture-file-request","look",files);store.finish(cid,JSONObject().put("role","assistant").put("content","OK"),"eog",UUID.randomUUID().toString());disk.close();disk=HistoryDisk(context,"fixture-files-$id.db","fixture-files-$id");val restored=ChatStore(disk).get(cid);verify(ChatFiles.bytes(restored.getJSONArray("messages").getJSONObject(0).getJSONArray("attachments").getJSONObject(0)).contentEquals(png),"Encrypted attachment body over2MiB must survive database reopen")}finally {disk.erase()}
        val liveCount=serviceFiles(png,encoded,live)
        ui()
        return JSONObject().put("checks",checks).put("live_catalog_models",liveCount).put("passed",true).put("scope","Separate debug package: bounded large catalog, redacted HTTP/SSE errors, three attachment encoders, large generated-image stream, encrypted reopen, settings/thinking UI. No paid or private prompts.")
    }
    private fun serviceFiles(png:ByteArray,encoded:String,live:Boolean):Int {
        val activity=test.startActivitySync(Intent(context,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));val connected=CountDownLatch(1);lateinit var endpoint:Messenger
        val replies=ConcurrentHashMap<String,CompletableFuture<JSONObject>>()
        val receiver=Messenger(Handler(Looper.getMainLooper()){m->m.data.getString("product_id")?.let {id->m.data.getParcelable<ParcelFileDescriptor>("payload")?.let {fd->ProductPipe.read(fd,16*1024*1024){r->r.fold({replies.remove(id)?.complete(JSONObject(String(it,Charsets.UTF_8)))},{replies.remove(id)?.completeExceptionally(it)})}}};true})
        val connection=object:ServiceConnection {override fun onServiceConnected(n:ComponentName,b:IBinder){endpoint=Messenger(b);connected.countDown()};override fun onServiceDisconnected(n:ComponentName){}}
        check(context.bindService(Intent(context,RuntimeService::class.java),connection,Context.BIND_AUTO_CREATE));check(connected.await(10,TimeUnit.SECONDS))
        fun call(value:JSONObject):JSONObject {val id=UUID.randomUUID().toString();val future=CompletableFuture<JSONObject>();replies[id]=future;val fd=ProductPipe.write(value.toString().toByteArray());try{endpoint.send(Message.obtain(null,RuntimeService.PRODUCT).apply {replyTo=receiver;data=Bundle().apply {putString("id",id);putParcelable("payload",fd)}})}finally{fd.close()};return future.get(30,TimeUnit.SECONDS).also {check(it.optBoolean("ok")){it.optString("message")}}}
        fun waitFor(seconds:Int=30,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+seconds*1000;while(!condition()){check(SystemClock.elapsedRealtime()<end){"Service/files fixture deadline"};Thread.sleep(80)}}
        var provider="";var publicProvider="";var liveCount=0;var chat="";val exportName="llamur-fixture-${UUID.randomUUID()}.png"
        fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).use {it.readBytes()}
        try {Server().use {server->
            if(live){val p=call(JSONObject().put("action","provider_save").put("profile",JSONObject().put("name","Public OpenRouter catalogue fixture").put("base_url","https://openrouter.ai/api/v1").put("protocol","chat").put("requires_key",true))).getJSONObject("provider");publicProvider=p.getString("id");verify(!p.getBoolean("has_key"),"Live discovery fixture must have no API key");liveCount=call(JSONObject().put("action","provider_models").put("provider_id",publicProvider)).getJSONArray("models").length();verify(liveCount>100,"OpenRouter catalogue must load through service without a key")}
            val profile=server.profile().put("name","Fixture files ${UUID.randomUUID()}").put("key","fixture-private-key").put("requires_key",true).put("save_key",false).put("model","fixture")
            provider=call(JSONObject().put("action","provider_save").put("profile",profile)).getJSONObject("provider").getString("id")
            val catalog=JSONObject().put("data",JSONArray().put(JSONObject().put("id","fixture").put("name","Fixture Multimodal").put("architecture",JSONObject().put("input_modalities",JSONArray().put("text").put("image").put("file")).put("output_modalities",JSONArray().put("text").put("image")))))
            server.reply=catalog.toString()
            verify(call(JSONObject().put("action","provider_models").put("provider_id",provider)).getJSONArray("models").getString(0)=="fixture","Catalogue must pass through service/cache/IPC")
            call(JSONObject().put("action","provider_enable").put("provider_id",provider).put("model","fixture").put("capabilities",JSONObject().put("input",JSONArray().put("text").put("image").put("file")).put("output",JSONArray().put("text").put("image"))))
            chat=call(JSONObject().put("action","remote_new").put("provider_id",provider).put("model","fixture")).getJSONObject("chat").getString("id")
            val event=JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",JSONObject().put("content","Fixture image").put("images",JSONArray().put(JSONObject().put("image_url",JSONObject().put("url","data:image/png;base64,$encoded")))))))
            server.type="text/event-stream";server.reply="data: $event\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            val title="Fixture file chat ${UUID.randomUUID().toString().take(8)}";val request=JSONObject().put("action","remote_send").put("chat_id",chat).put("request_id",UUID.randomUUID().toString()).put("text",title).put("attachments",JSONArray().put(JSONObject().put("name","fixture.png").put("mime","image/png").put("data",encoded)))
            call(request);var completed=JSONObject();waitFor {completed=call(JSONObject().put("action","get").put("chat_id",chat)).getJSONObject("chat");!completed.optBoolean("active")}
            verify(completed.optString("remote_error").let {it.isEmpty() || it=="null"},"Service image request must finish: ${completed.optString("remote_error")}")
            verify(ChatFiles.bytes(completed.getJSONArray("messages").getJSONObject(1).getJSONArray("attachments").getJSONObject(0)).contentEquals(png),"Generated file must cross service/IPC unchanged")
            val before=server.calls;call(request);verify(server.calls==before,"Duplicate send must not generate/pay twice")
            val driver=Driver(test,activity);waitFor {driver.views().filterIsInstance<TextView>().any {it.text.toString()==title}};driver.tap(title);driver.capture("files")
            verify(driver.find("Прикрепить файл").isEnabled,"Composer must offer attachment button")
            driver.tap("Прикрепить файл");driver.capture("file-picker")
            val picker=test.uiAutomation.rootInActiveWindow;check(picker.packageName?.toString()==context.packageName)
            verify(picker.findAccessibilityNodeInfosByText("Изображение ·").isNotEmpty(),"Discovered/manual vision capability must enable image picker")
            shell("input keyevent BACK");Thread.sleep(200)
            driver.tap("Сохранить файл image-1.png")
            waitFor {test.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
            fun nodes(root:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> = buildList {fun walk(n:android.view.accessibility.AccessibilityNodeInfo){add(n);for(i in 0 until n.childCount)n.getChild(i)?.let {walk(it)}};walk(root)}
            val root=test.uiAutomation.rootInActiveWindow;check(root.packageName.toString().contains("documentsui"));val all=nodes(root)
            val filename=all.first {it.isEditable && it.text?.toString()?.endsWith(".png")==true}
            check(filename.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,exportName)}))
            val save=nodes(test.uiAutomation.rootInActiveWindow).first {it.isClickable && it.text?.toString()?.lowercase(Locale.ROOT) in listOf("save","сохранить")};check(save.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            waitFor {test.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName}
            waitFor {shell("cat /sdcard/Download/$exportName").contentEquals(png)};verify(true,"System Save picker must export generated file byte-for-byte")
            server.reply="data: {\"choices\":[{\"delta\":{\"content\":\"Follow-up OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            call(JSONObject().put("action","remote_send").put("chat_id",chat).put("request_id",UUID.randomUUID().toString()).put("text","Follow-up"))
            waitFor {!call(JSONObject().put("action","get").put("chat_id",chat)).getJSONObject("chat").optBoolean("active")}
            val sent=server.received.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url")
            verify(sent.endsWith(encoded),"Follow-up must preserve original image input in history")
            server.type="application/json";server.reply=catalog.toString()
            driver.tap("Назад к диалогам");driver.tap("Настройки");driver.tap("Подключения по API");waitFor {driver.views().any {it.contentDescription?.toString()==ProviderStore.displayName(profile)}};driver.tap(ProviderStore.displayName(profile));driver.tap("Каталог моделей")
            waitFor {driver.views().filterIsInstance<TextView>().any {it.text.toString()=="Fixture Multimodal"}};driver.capture("catalogue")
            test.runOnMainSync {(driver.find("Поиск модели по имени или ID") as EditText).setText("fixture")};verify(driver.views().filterIsInstance<ListView>().single().count==1,"Full-screen catalogue must load and filter models")
        }}finally {
            if(chat.isNotEmpty())runCatching {call(JSONObject().put("action","delete").put("chat_id",chat))}
            if(provider.isNotEmpty())runCatching {call(JSONObject().put("action","provider_delete").put("provider_id",provider))}
            if(publicProvider.isNotEmpty())runCatching {call(JSONObject().put("action","provider_delete").put("provider_id",publicProvider))}
            shell("rm -f /sdcard/Download/$exportName");context.unbindService(connection);test.runOnMainSync {activity.finish()}
        }
        return liveCount
    }
    private class Driver(private val test:Instrumentation,private val activity:android.app.Activity) {
        fun views():List<View>{val out=mutableListOf<View>();fun walk(v:View){if(v.visibility!=View.VISIBLE)return;out.add(v);if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))};if(Looper.myLooper()==Looper.getMainLooper())walk(activity.window.decorView)else test.runOnMainSync {walk(activity.window.decorView)};return out}
        fun find(t:String):View=views().firstOrNull {it.contentDescription?.toString()==t || (it as? TextView)?.text?.toString()==t}?:error("Missing UI: $t")
        fun tap(t:String){val v=find(t);test.runOnMainSync {v.requestRectangleOnScreen(Rect(0,0,v.width,v.height),true)};test.waitForIdleSync();Thread.sleep(120);val r=Rect();test.runOnMainSync {check(v.getGlobalVisibleRect(r))};val now=SystemClock.uptimeMillis();test.sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,r.exactCenterX(),r.exactCenterY(),0));test.sendPointerSync(MotionEvent.obtain(now,now+40,MotionEvent.ACTION_UP,r.exactCenterX(),r.exactCenterY(),0));test.waitForIdleSync();Thread.sleep(160)}
        fun capture(name:String){test.waitForIdleSync();Thread.sleep(160);test.uiAutomation.takeScreenshot()?.let {b->File(activity.filesDir,"provider-update-$name.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    }
    private fun ui() {
        val prefs=context.getSharedPreferences("preferences",Context.MODE_PRIVATE);val original=prefs.getBoolean("thinking",true)
        val activity=test.startActivitySync(Intent(context,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(500)
        fun views():List<View>{val out=mutableListOf<View>();fun walk(v:View){if(v.visibility!=View.VISIBLE)return;out.add(v);if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))};test.runOnMainSync {walk(activity.window.decorView)};return out}
        fun find(t:String):View=views().firstOrNull {it.contentDescription?.toString()==t || (it as? TextView)?.text?.toString()==t}?:error("Missing UI: $t")
        fun tap(t:String){val v=find(t);test.runOnMainSync {v.requestRectangleOnScreen(Rect(0,0,v.width,v.height),true)};test.waitForIdleSync();Thread.sleep(120);var r=Rect();test.runOnMainSync {check(v.getGlobalVisibleRect(r))};val now=SystemClock.uptimeMillis();test.sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,r.exactCenterX(),r.exactCenterY(),0));test.sendPointerSync(MotionEvent.obtain(now,now+40,MotionEvent.ACTION_UP,r.exactCenterX(),r.exactCenterY(),0));test.waitForIdleSync();Thread.sleep(160)}
        fun capture(name:String){test.waitForIdleSync();Thread.sleep(160);test.uiAutomation.takeScreenshot()?.let {b->File(context.filesDir,"provider-update-$name.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        try {
            verify(views().none {(it as? TextView)?.text?.toString()=="llamur"},"Home brand heading must be removed");tap("Настройки");capture("settings")
            val thinking=find("Рассуждение локальной модели") as Switch;val scroll=views().filterIsInstance<ScrollView>().last();test.runOnMainSync {thinking.requestRectangleOnScreen(Rect(0,0,thinking.width,thinking.height),true)};test.waitForIdleSync();val y=scroll.scrollY
            tap("Рассуждение локальной модели");verify(prefs.getBoolean("thinking",original)!=original && scroll.scrollY==y,"Thinking switch must change preference without scrolling/recreating settings");tap("Рассуждение локальной модели");verify(prefs.getBoolean("thinking",!original)==original,"Thinking switch must restore")
            tap("Модели на телефоне");verify(find("Импорт GGUF").isShown,"Models must be a full screen with import action");capture("models");tap("Hugging Face");verify(find("Репозиторий: автор/модель").isShown,"HF must open full screen");capture("hf");tap("Назад");tap("Назад")
            tap("Подключения по API");Thread.sleep(400);verify(views().filterIsInstance<TextView>().any {it.text.contains("Добавить подключение")},"Providers must offer clear add row");capture("providers");tap("＋ Добавить подключение");tap("OpenRouter");verify(find("API key").isShown,"Provider editor must expose masked API key field");capture("provider-edit")
            val endpoint=views().filterIsInstance<EditText>().first {it.contentDescription?.toString()=="Base URL с /v1 или /api/v1"}
            test.runOnMainSync {endpoint.setText("http://100.64.12.34:8000/v1")}
            verify(find("⚠ HTTP — соединение без TLS").isShown,"HTTP status must appear above endpoint")
            fun dialogClick(label:String){val until=SystemClock.elapsedRealtime()+5000;while(true){val node=test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull {it.isClickable};if(node!=null){check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));break};check(SystemClock.elapsedRealtime()<until){"Missing dialog action $label"};Thread.sleep(50)};test.waitForIdleSync()}
            tap("Сохранить");capture("http-warning");verify(test.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Tailscale").isNotEmpty(),"HTTP warning must explain VPN boundary")
            dialogClick("Отмена");val permission=find("Разрешить HTTP для этого адреса") as CheckBox;verify(!permission.isChecked,"Cancelling must not grant permission")
            tap("Разрешить HTTP для этого адреса");dialogClick("Разрешить для этого адреса");verify(permission.isChecked,"Explicit acceptance grants this address")
            test.runOnMainSync {endpoint.setText("http://100.64.12.34:8001/v1")};verify(!permission.isChecked,"Changing port must invalidate consent")
            capture("http-editor")
            val providerUi=ChatActivity::class.java.getDeclaredMethod("providerUi").apply {isAccessible=true}.invoke(activity)
            test.runOnMainSync {ProviderUi::class.java.getDeclaredMethod("chooseModel",JSONObject::class.java,Boolean::class.javaPrimitiveType).apply {isAccessible=true}.invoke(providerUi,JSONObject().put("name","Public fixture").put("base_url","http://100.64.12.34:8000/v1").put("model","fixture-model"),false)}
            test.waitForIdleSync()
            val activate=find("Активировать");val catalogue=find("Загрузить каталог")
            verify(activate.parent===catalogue.parent && catalogue.top-activate.bottom>=(12*context.resources.displayMetrics.density).toInt(),"Adjacent provider actions need at least12dp visible separation")
            capture("model-actions")

        }finally {prefs.edit().putBoolean("thinking",original).commit();test.runOnMainSync {activity.finish()}}
    }
}
