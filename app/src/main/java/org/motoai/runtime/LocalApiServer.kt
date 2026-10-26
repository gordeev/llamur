package org.motoai.runtime

import android.content.Context
import android.net.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket

internal class ApiCall(val request: JSONObject) {
    val admitted = CountDownLatch(1)
    val done = CountDownLatch(1)
    val updates = LinkedBlockingQueue<JSONObject>(2)
    val cancelled = AtomicBoolean(false)
    @Volatile var error: ApiError? = null
    @Volatile var result: JSONObject? = null
    fun update(message: JSONObject) { if(!updates.offer(message)) { updates.poll(); updates.offer(message) } }
    fun fail(failure: ApiError) { error=failure; admitted.countDown(); done.countDown() }
}

/** A deliberately bounded HTTP/1.1 text-chat subset. No access/body logging or remote model fallback. */
internal class LocalApiServer(private val context: Context, private val lan: Boolean, private val port: Int,
    private val models: () -> JSONObject, private val begin: (JSONObject) -> ApiCall,
    private val cancel: (ApiCall) -> Unit, private val stopped: (String) -> Unit) : AutoCloseable {
    private val closed=AtomicBoolean(false)
    private val random=ByteArray(32).also { SecureRandom().nextBytes(it) }
    val token=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random)
    private val connectivity=context.getSystemService(ConnectivityManager::class.java)
    private val network: Network?
    val address: String
    val certificate: String
    val fingerprint: String
    private val server: ServerSocket
    private val clients=java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
    private val calls=java.util.Collections.synchronizedSet(mutableSetOf<ApiCall>())
    private val pool=ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,SynchronousQueue())
    private var callback: ConnectivityManager.NetworkCallback?=null
    val url get()="${if(lan) "https" else "http"}://$address:$port/v1"
    init {
        require(port in 1024..65535)
        if(lan) {
            network=connectivity.allNetworks.firstOrNull { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true }
                ?: error(UiText.get(R.string.ui_connect_to_wi_fi_for_lan_api))
            address=connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
                ?: error(UiText.get(R.string.ui_wi_fi_has_no_ipv4_address))
            val tls=ApiTls.create(address); certificate=tls.pem; fingerprint=tls.fingerprint
            server=tls.context.serverSocketFactory.createServerSocket() as SSLServerSocket
            server.enabledProtocols=server.supportedProtocols.filter { it in listOf("TLSv1.2","TLSv1.3") }.toTypedArray()
        } else { network=null; address="127.0.0.1"; certificate=""; fingerprint=""; server=ServerSocket() }
        // Rebind after an explicit stop despite old TCP TIME_WAIT connections.
        // SO_REUSEPORT is never enabled; a live listener still owns this address/port.
        try { server.reuseAddress=true; server.bind(InetSocketAddress(InetAddress.getByName(address),port),8) }
        catch(e:Throwable) { server.close(); pool.shutdownNow(); throw e }
        if(lan) {
            callback=object : ConnectivityManager.NetworkCallback() {
                override fun onLost(n:Network) { if(n==network) stopForNetwork() }
                override fun onLinkPropertiesChanged(n:Network,p:LinkProperties) {
                    if(n==network && p.linkAddresses.none { it.address.hostAddress==address })stopForNetwork()
                }
            }
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),callback!!)
        }
        Thread({
            while(!closed.get()) try {
                val socket=server.accept(); socket.soTimeout=20000
                clients.add(socket)
                try { pool.execute { socket.use { try { serve(it) } catch(_:Exception) {} finally { clients.remove(it) } } } }
                catch(_:RejectedExecutionException) { clients.remove(socket); socket.close() }
            } catch(_:SocketException) { if(!closed.get())stopForNetwork() }
        },"llamur-api-accept").start()
    }
    private fun stopForNetwork() { close(); stopped(UiText.get(R.string.ui_api_stopped_wi_fi_or_address_changed)) }
    fun info()=JSONObject().put("running",!closed.get()).put("url",url).put("token",token)
        .put("lan",lan).put("certificate",certificate).put("fingerprint",fingerprint)
    private data class Http(val method:String,val path:String,val headers:Map<String,String>,val body:ByteArray)
    private fun read(input:InputStream):Http {
        var budget=16384
        fun line():String {
            val out=ByteArrayOutputStream()
            while(true) { val c=input.read(); if(c<0)throw EOFException(); if(--budget<0)throw ApiError(431,"headers_too_large","Headers exceed 16 KiB")
                if(c==10) { val b=out.toByteArray(); if(b.isEmpty() || b.last()!=13.toByte())throw ApiError(400,"invalid_http","CRLF required")
                    return String(b,0,b.size-1,Charsets.US_ASCII) }
                if(c !in 32..126 && c!=13)throw ApiError(400,"invalid_http","Invalid header byte"); out.write(c)
            }
        }
        val start=line().split(' ')
        if(start.size!=3 || start[2]!="HTTP/1.1")throw ApiError(400,"invalid_http","HTTP/1.1 required")
        val headers=linkedMapOf<String,String>()
        while(true) { val line=line(); if(line.isEmpty())break
            val i=line.indexOf(':'); if(i<1 || line[0].isWhitespace())throw ApiError(400,"invalid_http","Invalid header")
            val key=line.substring(0,i).lowercase(java.util.Locale.ROOT)
            if(!key.matches(Regex("[a-z0-9!#$%&'*+.^_`|~-]+")) || headers.containsKey(key))throw ApiError(400,"invalid_http","Duplicate or invalid header")
            headers[key]=line.substring(i+1).trim()
        }
        val expected="Bearer $token".toByteArray(Charsets.US_ASCII)
        if(!MessageDigest.isEqual((headers["authorization"] ?: "").toByteArray(Charsets.US_ASCII),expected))throw ApiError(401,"invalid_api_key","Bearer token required")
        if(!headers.containsKey("host") || headers.containsKey("transfer-encoding") || headers.containsKey("expect"))throw ApiError(400,"invalid_http","Host and fixed Content-Length required; chunked/Expect unsupported")
        val size=headers["content-length"]?.let { if(!it.matches(Regex("[0-9]{1,8}")))throw ApiError(400,"invalid_http","Invalid Content-Length");it.toLong() } ?: 0
        if(size>2*1024*1024)throw ApiError(413,"body_too_large","Body exceeds 2 MiB")
        if(start[0]=="POST" && headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()!="application/json")throw ApiError(415,"invalid_content_type","application/json required")
        val bytes=ByteArray(size.toInt()); var offset=0
        while(offset<bytes.size) { val n=input.read(bytes,offset,bytes.size-offset); if(n<0)throw ApiError(400,"incomplete_body","Incomplete body");offset+=n }
        return Http(start[0],start[1],headers,bytes)
    }
    private fun headers(output:OutputStream,status:Int,type:String,length:Int?=null) {
        val reason=when(status){200->"OK";400->"Bad Request";401->"Unauthorized";404->"Not Found";409->"Conflict";413->"Content Too Large";429->"Too Many Requests";503->"Service Unavailable";else->"Error"}
        output.write(("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n"+
            (length?.let { "Content-Length: $it\r\n" } ?: "")+"\r\n").toByteArray(Charsets.US_ASCII));output.flush()
    }
    private fun json(output:OutputStream,status:Int,body:JSONObject) { val b=body.toString().toByteArray(Charsets.UTF_8);headers(output,status,"application/json; charset=utf-8",b.size);output.write(b);output.flush() }
    private fun serve(socket:Socket) {
        val output=BufferedOutputStream(socket.getOutputStream()); val input=socket.getInputStream()
        var call:ApiCall?=null; var streaming=false
        try {
            val request=read(input)
            if(request.method=="GET" && request.path=="/v1/models") { json(output,200,models());return }
            if(request.method!="POST" || request.path!="/v1/chat/completions")throw ApiError(404,"not_found","Supported routes: /v1/models, /v1/chat/completions")
            val info=models(); val data=info.getJSONArray("data")
            if(data.length()==0)throw ApiError(503,"model_not_loaded","Load a model in llamur first")
            val model=data.getJSONObject(0).getString("id"); val context=data.getJSONObject(0).getInt("context_length")
            val body=try { StrictJson.parse(request.body) }catch(_:Exception){throw ApiError(400,"invalid_json","Invalid UTF-8 JSON body")}
            val parsed=ApiContract.parse(body,model,context)
            val session=request.headers["x-llamur-session"]?:request.headers["x-motoai-session"]
            if(session!=null) {
                if(!session.matches(Regex("[a-zA-Z0-9_-]{1,80}")))throw ApiError(400,"invalid_session","Invalid X-llamur-Session")
                parsed.native.put("cache_session","api:"+session)
            }
            parsed.native.put("_expected_model",model).put("_expected_context",context)
            call=begin(parsed.native); calls.add(call)
            val current=call
            socket.soTimeout=250
            fun disconnected() {
                try { if(input.read()<0)throw EOFException("Client disconnected") else throw IOException("Pipelining is unsupported") }
                catch(_:SocketTimeoutException) {}
            }
            val deadline=System.nanoTime()+TimeUnit.HOURS.toNanos(4)
            while(!current.admitted.await(100,TimeUnit.MILLISECONDS)) { if(closed.get() || System.nanoTime()>deadline)throw IOException("Server stopped/deadline"); disconnected() }
            current.error?.let { throw it }
            val id="chatcmpl-"+UUID.randomUUID().toString();val created=System.currentTimeMillis()/1000
            fun chunk(delta:JSONObject,reason:Any=JSONObject.NULL)=JSONObject().put("id",id).put("object","chat.completion.chunk").put("created",created).put("model",model)
                .put("choices",JSONArray().put(JSONObject().put("index",0).put("delta",delta).put("finish_reason",reason)))
            fun event(value:String) { output.write(("data: $value\n\n").toByteArray(Charsets.UTF_8));output.flush() }
            var content=""; var reasoning=""; var heartbeat=System.nanoTime()
            if(parsed.stream) { headers(output,200,"text/event-stream; charset=utf-8");streaming=true;event(chunk(JSONObject().put("role","assistant").put("content","")).toString()) }
            fun emit(message:JSONObject) {
                val c=message.optString("content"); val r=message.optString("reasoning_content")
                if(!c.startsWith(content) || !r.startsWith(reasoning))throw ApiError(500,"non_monotonic_parser","Model parser revised an emitted prefix")
                val delta=JSONObject()
                if(c.length>content.length)delta.put("content",c.substring(content.length))
                if(r.length>reasoning.length)delta.put("reasoning_content",r.substring(reasoning.length))
                content=c;reasoning=r
                if(parsed.stream && delta.length()>0)event(chunk(delta).toString())
            }
            while(current.done.count>0) {
                if(closed.get() || System.nanoTime()>deadline)throw IOException("Server stopped/deadline")
                current.updates.poll(100,TimeUnit.MILLISECONDS)?.let { emit(it) }
                if(parsed.stream) {
                    if(System.nanoTime()-heartbeat>TimeUnit.SECONDS.toNanos(5)) { output.write(": llamur working\n\n".toByteArray());output.flush();heartbeat=System.nanoTime() }
                } else disconnected()
            }
            current.error?.let { throw it }
            val result=current.result ?: throw ApiError(500,"missing_result","Generation ended without a result")
            val assistant=result.getJSONObject("assistant");emit(assistant)
            val reason=if(result.getString("finish_reason")=="length")"length" else "stop"
            val usage=JSONObject().put("prompt_tokens",result.getInt("prompt_tokens")).put("completion_tokens",result.getInt("generated_tokens"))
                .put("total_tokens",result.getInt("prompt_tokens")+result.getInt("generated_tokens"))
            if(parsed.stream) {
                event(chunk(JSONObject(),reason).toString())
                if(parsed.usage)event(JSONObject().put("id",id).put("object","chat.completion.chunk").put("created",created).put("model",model).put("choices",JSONArray()).put("usage",usage).toString())
                event("[DONE]")
            } else json(output,200,JSONObject().put("id",id).put("object","chat.completion").put("created",created).put("model",model)
                .put("choices",JSONArray().put(JSONObject().put("index",0).put("message",assistant).put("finish_reason",reason))).put("usage",usage))
        } catch(e:ApiError) { if(streaming) { output.write(("data: ${e.json()}\n\ndata: [DONE]\n\n").toByteArray(Charsets.UTF_8));output.flush() }else json(output,e.status,e.json()) }
        catch(_:java.nio.charset.CharacterCodingException) { if(!streaming)json(output,400,ApiError(400,"invalid_utf8","Invalid UTF-8").json()) }
        finally { call?.let { if(it.done.count>0) { it.cancelled.set(true);cancel(it) };calls.remove(it) } }
    }
    override fun close() {
        if(!closed.compareAndSet(false,true))return
        runCatching { server.close() }
        synchronized(clients) { clients.toList().forEach { runCatching { it.close() } } }
        synchronized(calls) { calls.toList().forEach { it.cancelled.set(true);cancel(it) } }
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }; callback=null
        pool.shutdownNow()
    }
}
