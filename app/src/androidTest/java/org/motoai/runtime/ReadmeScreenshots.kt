package org.motoai.runtime

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.os.*
import android.view.*
import android.widget.*
import org.json.*
import java.io.File
import java.net.*
import java.util.*
import java.util.concurrent.*

/** Actual app screens with public scripted demo conversations, never model-performance evidence. */
internal class ReadmeScreenshots(private val test:Instrumentation) {
    fun run(theme:Int):JSONObject {
        val context=test.targetContext
        check(context.packageName.endsWith(".debug") && Build.HARDWARE=="ranchu" && UiText.language()=="en")
        val prefs=context.getSharedPreferences("preferences",Context.MODE_PRIVATE);val previousTheme=prefs.getInt("theme",0);prefs.edit().putInt("theme",theme).commit()
        val pending=ConcurrentHashMap<String,CompletableFuture<JSONObject>>();lateinit var service:Messenger
        val receiver=Messenger(Handler(Looper.getMainLooper()){m->m.data.getString("product_id")?.let {id->m.data.getParcelable<ParcelFileDescriptor>("payload")?.let {fd->ProductPipe.read(fd,4*1024*1024){r->r.fold({pending.remove(id)?.complete(JSONObject(String(it)))},{pending.remove(id)?.completeExceptionally(it)})}}};true})
        val connected=CountDownLatch(1);val connection=object:ServiceConnection {override fun onServiceConnected(n:ComponentName,b:IBinder){service=Messenger(b);connected.countDown()};override fun onServiceDisconnected(n:ComponentName){}}
        check(context.bindService(Intent(context,RuntimeService::class.java),connection,Context.BIND_AUTO_CREATE));check(connected.await(10,TimeUnit.SECONDS))
        fun call(body:JSONObject):JSONObject {val id=UUID.randomUUID().toString();val future=CompletableFuture<JSONObject>();pending[id]=future;val fd=ProductPipe.write(body.toString().toByteArray());try {service.send(Message.obtain(null,RuntimeService.PRODUCT).apply {replyTo=receiver;data=Bundle().apply {putString("id",id);putParcelable("payload",fd)}})}finally{fd.close()};return future.get(20,TimeUnit.SECONDS).also {check(it.optBoolean("ok")){it.optString("message")}}}
        fun waitFor(condition:()->Boolean){val end=SystemClock.elapsedRealtime()+15000;while(!condition()){check(SystemClock.elapsedRealtime()<end){"Screenshot fixture deadline"};Thread.sleep(50)}}
        val replies=mapOf(
            "Plan a weekend with offline AI" to "Your phone is ready for the trip.\n\n• Download a GGUF model before you leave.\n• Start with a 4K context window.\n• Use temporary chats for private notes.\n• Keep a charger nearby for longer sessions.\n\nOnce the model is downloaded, local chats work without an internet connection.",
            "Can I keep my travel notes private?" to "Yes. Temporary chats stay in memory. For notes you want to keep, choose a permanent chat and encrypted storage.\n\nYou can also set an automatic deletion period in Settings.",
            "Explain binary search in plain English" to "Binary search keeps the half that can still contain your answer. Repeat until the item is found.\n\nThe list must be sorted first.",
            "A short checklist for tomorrow" to "• Charge your phone\n• Download your model\n• Pack headphones\n• Enjoy the day")
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));val worker=Executors.newSingleThreadExecutor();var failure:Throwable?=null
        val serving=worker.submit {try {while(!server.isClosed)server.accept().use {socket->socket.soTimeout=10000;val input=socket.getInputStream().buffered()
            fun line():String {val out=java.io.ByteArrayOutputStream();while(true){val b=input.read();check(b>=0);if(b==10)break;check(out.size()<16384);out.write(b)};return out.toString("US-ASCII").removeSuffix("\r")}
            check(line().startsWith("POST "));var size=0;while(true){val h=line();if(h.isEmpty())break;if(h.startsWith("Content-Length:",true))size=h.substringAfter(':').trim().toInt()};check(size in 1..65536)
            val bytes=ByteArray(size);var at=0;while(at<size){val n=input.read(bytes,at,size-at);check(n>0);at+=n}
            val messages=JSONObject(String(bytes)).getJSONArray("messages");val question=messages.getJSONObject(messages.length()-1).getString("content");val answer=replies.getValue(question)
            val body=JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("role","assistant").put("content",answer)).put("finish_reason","stop"))).toString().toByteArray()
            socket.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}
        }}catch(f:Throwable){if(!server.isClosed)failure=f}}
        var provider="";val chats=mutableListOf<String>();val folders=mutableListOf<String>();var activity:Activity?=null
        try {
            for(name in listOf("Everyday","Coding","Ideas")){val a=call(JSONObject().put("action","folder").put("name",name)).getJSONObject("history").getJSONArray("folders");folders.add((0 until a.length()).map {a.getJSONObject(it)}.first {it.getString("name")==name}.getString("id"))}
            provider=call(JSONObject().put("action","provider_save").put("profile",JSONObject().put("name","Demo provider").put("base_url","http://127.0.0.1:${server.localPort}/v1").put("allow_local_http",true).put("requires_key",false).put("model","Assistant").put("protocol","chat"))).getJSONObject("provider").getString("id")
            fun send(id:String,question:String){call(JSONObject().put("action","remote_send").put("chat_id",id).put("request_id",UUID.randomUUID().toString()).put("text",question));waitFor {!call(JSONObject().put("action","get").put("chat_id",id)).getJSONObject("chat").optBoolean("active")};check(call(JSONObject().put("action","get").put("chat_id",id)).getJSONObject("chat").getJSONArray("messages").let {it.getJSONObject(it.length()-1).getString("content")==replies[question]})}
            for((i,q) in listOf("A short checklist for tomorrow","Explain binary search in plain English","Plan a weekend with offline AI").withIndex()){val id=call(JSONObject().put("action","remote_new").put("provider_id",provider).put("model","Assistant")).getJSONObject("chat").getString("id");chats.add(id);call(JSONObject().put("action","chat_move").put("chat_id",id).put("folder_id",folders[if(i==1)1 else 0]));send(id,q)}
            val current=chats.last();send(current,"Can I keep my travel notes private?")
            activity=test.startActivitySync(Intent(context,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));val a=activity!!
            fun invoke(name:String,vararg args:Any){test.runOnMainSync {val m=ChatActivity::class.java.declaredMethods.first {it.name==name && it.parameterTypes.size==args.size};m.isAccessible=true;m.invoke(a,*args)}}
            fun capture(name:String){test.waitForIdleSync();Thread.sleep(500);val bitmap=test.uiAutomation.takeScreenshot()?:error("No screenshot");File(context.filesDir,"readme-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            Thread.sleep(700);capture("home")
            invoke("selectChat",call(JSONObject().put("action","get").put("chat_id",current)).getJSONObject("chat"));capture("chat")
            invoke("showTab",2);capture("settings");invoke("showAbout");capture("about")
            val pages=ChatActivity::class.java.getDeclaredField("pages").apply {isAccessible=true};test.runOnMainSync {(pages.get(a) as SettingsPages).clear()}
            invoke("historySettings",false);Thread.sleep(400);capture("storage")
            test.runOnMainSync {(pages.get(a) as SettingsPages).clear()};invoke("showDownload");capture("hugging-face")
            check(failure==null)
            return JSONObject().put("passed",true).put("theme",theme).put("screens",6).put("scope","Actual English Android UI; four real service requests to a public scripted loopback fixture. No cloud/model inference or speed claim; no private user data.")
        }finally {
            test.runOnMainSync {activity?.finish()}
            for(id in chats)runCatching {call(JSONObject().put("action","delete").put("chat_id",id))}
            if(provider.isNotEmpty())runCatching {call(JSONObject().put("action","provider_delete").put("provider_id",provider))}
            for(id in folders)runCatching {call(JSONObject().put("action","folder").put("folder_id",id).put("remove",true))}
            server.close();worker.shutdownNow();runCatching {serving.get(2,TimeUnit.SECONDS)};context.unbindService(connection);prefs.edit().putInt("theme",previousTheme).commit()
        }
    }
}
