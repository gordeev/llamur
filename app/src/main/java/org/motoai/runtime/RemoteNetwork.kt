package org.motoai.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/** No SDK, automatic retry, redirects, logging, model fallback or TLS bypass. */
internal class RemoteNetwork {
    private val cancelled=AtomicBoolean(false)
    @Volatile private var connection:HttpURLConnection?=null
    private val started=android.os.SystemClock.elapsedRealtime()
    fun cancel() {if(cancelled.compareAndSet(false,true))Thread({connection?.disconnect()},"provider-cancel").start()}
    private fun checkAlive() {if(cancelled.get())throw RemoteFailure(UiText.get(R.string.ui_request_stopped_partial_response_kept));check(android.os.SystemClock.elapsedRealtime()-started<60*60*1000) {UiText.get(R.string.ui_request_exceeded_1_hour)}}
    private fun open(profile:JSONObject,suffix:String):HttpURLConnection {
        checkAlive();val base=ProviderStore.endpoint(profile.getString("base_url"),profile.optBoolean("allow_local_http"),profile.optString("allow_http_url"))
        val c=URL(base+suffix).openConnection() as HttpURLConnection;connection=c
        c.instanceFollowRedirects=false;c.connectTimeout=20000;c.readTimeout=180000;c.useCaches=false
        c.setRequestProperty("Accept","application/json, text/event-stream");c.setRequestProperty("Accept-Encoding","identity")
        val secret=profile.optJSONObject("secret")?:JSONObject();val key=secret.optString("key")
        if(profile.getString("protocol")=="anthropic") {c.setRequestProperty("anthropic-version","2023-06-01");if(key.isNotEmpty())c.setRequestProperty("x-api-key",key)}
        else if(key.isNotEmpty())c.setRequestProperty("Authorization","Bearer $key")
        secret.optJSONObject("headers")?.let {h->h.keys().forEach {c.setRequestProperty(it,h.getString(it))}}
        checkAlive();return c
    }
    private fun input(c:HttpURLConnection,profile:JSONObject):InputStream {
        val status=c.responseCode
        if(status in 300..399)throw RemoteFailure(UiText.get(R.string.ui_redirect_rejected_key_is_not_forwarded_to))
        if(status !in 200..299) {
            val message=runCatching {c.errorStream?.use {body(it,16384)}?.let {it.optJSONObject("error")?.optString("message")?:it.optString("message")}}.getOrNull().orEmpty()
            throw RemoteFailure(ProviderError.describe(status,message,profile))
        }
        val raw=c.inputStream;val decoded=when(c.contentEncoding?.lowercase(java.util.Locale.ROOT)){null,"","identity"->raw;"gzip"->GZIPInputStream(raw);else->throw RemoteFailure(UiText.get(R.string.ui_unsupported_response_encoding))}
        return object:FilterInputStream(decoded) {
            var count=0L
            override fun read():Int {checkAlive();val n=super.read();if(n>=0)count++;if(count>32*1024*1024)throw RemoteFailure(UiText.get(R.string.ui_network_response_exceeded_32_mib));return n}
            override fun read(b:ByteArray,off:Int,len:Int):Int {checkAlive();val n=`in`.read(b,off,len);if(n>0)count+=n;if(count>32*1024*1024)throw RemoteFailure(UiText.get(R.string.ui_network_response_exceeded_32_mib));return n}
        }
    }
    // Decode one raw SSE line at a time. A decoder's read-ahead must not discard
    // an earlier valid event when a later line contains malformed UTF-8.
    private fun text(input:InputStream):Reader=object:Reader() {
        private val source=BufferedInputStream(input)
        private var line="";private var offset=0;private val single=CharArray(1)
        override fun read():Int=if(read(single,0,1)<0)-1 else single[0].code
        override fun read(chars:CharArray,start:Int,length:Int):Int {
            require(start>=0 && length>=0 && start<=chars.size-length);if(length==0)return 0
            if(offset==line.length) {
                val bytes=ByteArrayOutputStream()
                while(true){val n=source.read();if(n<0)break;require(bytes.size()<ChatFiles.MESSAGE_BYTES) {UiText.get(R.string.ui_sse_line_too_large)};bytes.write(n);if(n==10 || n==13)break}
                if(bytes.size()==0)return -1
                line=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();offset=0
            }
            val count=minOf(length,line.length-offset);line.toCharArray(chars,start,offset,offset+count);offset+=count;return count
        }
        override fun close(){source.close()}
    }
    private fun body(input:InputStream,limit:Int=2*1024*1024):JSONObject {
        val out=ByteArrayOutputStream();val b=ByteArray(8192)
        while(true){val n=input.read(b);if(n<0)break;require(out.size()+n<=limit) {UiText.get(R.string.ui_json_response_too_large)};out.write(b,0,n)}
        return StrictJson.parse(out.toByteArray(),maxBytes=limit,maxNodes=if(limit>2*1024*1024)262144 else 32768)
    }
    fun models(profile:JSONObject):JSONArray=catalogue(profile).getJSONArray("models")
    fun catalogue(profile:JSONObject):JSONObject {
        val result=JSONArray();val metadata=JSONObject();val seen=mutableSetOf<String>();var after=""
        fun complete()=JSONObject().put("models",result).put("details",metadata)
        repeat(10) {
            val suffix="/models"+if(after.isEmpty())""else "?after_id="+java.net.URLEncoder.encode(after,"UTF-8")
            val c=open(profile,suffix)
            try {
                val j=input(c,profile).use {body(it,8*1024*1024)};val a=j.getJSONArray("data")
                for(i in 0 until a.length()){val m=a.getJSONObject(i);val id=m.getString("id");ProviderStore.validModel(id);if(seen.add(id))result.put(id);metadata.put(id,ModelCapabilities.compact(m));require(seen.size<=10000) {UiText.get(R.string.ui_catalogue_too_large_enter_model_id_manually)}}
                require(metadata.toString().toByteArray().size<=2*1024*1024){UiText.get(R.string.ui_capabilities_catalogue_too_large)}
                if(!j.optBoolean("has_more"))return complete()
                val next=j.optString("last_id");require(next.isNotEmpty() && next!=after);after=next
            }finally{c.disconnect();connection=null}
        }
        return complete() // Bounded catalog; manual model ID remains available.
    }
    fun generate(profile:JSONObject,model:String,history:JSONArray,onDelta:(JSONObject)->Unit):JSONObject {
        val parser=RemoteProtocol.Stream(profile.getString("protocol")){status,message->ProviderError.describe(status,message,profile)}
        val suffix=when(profile.getString("protocol")){"chat"->"/chat/completions";"responses"->"/responses";else->"/messages"}
        val c=open(profile,suffix)
        try {
            val bytes=RemoteProtocol.request(profile,model,history).toString().toByteArray(Charsets.UTF_8)
            c.requestMethod="POST";c.doOutput=true;c.setFixedLengthStreamingMode(bytes.size);c.setRequestProperty("Content-Type","application/json")
            c.outputStream.use {it.write(bytes)}
            input(c,profile).use {stream->
                val type=c.contentType.orEmpty().substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
                if(type=="application/json") {parser.json(body(stream,ChatFiles.MESSAGE_BYTES));onDelta(parser.message())}
                else {
                    require(type=="text/event-stream") {UiText.get(R.string.ui_expected_sse_or_json_response)}
                    text(stream).use {reader->sse(reader) {event,data->parser.event(event,data);onDelta(parser.message(false));!parser.isTerminal}}
                }
            }
            checkAlive();return parser.result()
        }catch(f:Exception) {
            if(f is RemoteFailure)throw f
            if(cancelled.get())throw RemoteFailure(UiText.get(R.string.ui_request_stopped_partial_response_kept))
            // Never expose provider bodies, request headers or exception strings containing secrets.
            throw RemoteFailure(if(f is IllegalArgumentException || f is IllegalStateException)f.message?:UiText.get(R.string.ui_unsupported_provider_response) else UiText.get(R.string.ui_network_protocol_error_partial_response_kept_no, (f.javaClass.simpleName)))
        }finally{c.disconnect();connection=null}
    }
    companion object {
        fun sse(reader:Reader,receive:(String,String)->Boolean) {
            val source=reader
            var event="";val data=StringBuilder();var first=true;var skipLf=false
            while(true) {
                val line=StringBuilder();var eof=false
                while(true){val n=source.read();if(skipLf){skipLf=false;if(n==10)continue};if(n==13){skipLf=true;break};if(n<0){eof=true;break};if(n==10)break;require(line.length<ChatFiles.MESSAGE_BYTES) {UiText.get(R.string.ui_sse_line_exceeded_8_mib)};line.append(n.toChar())}
                if(line.endsWith("\r"))line.setLength(line.length-1)
                if(first && line.startsWith("\uFEFF"))line.deleteCharAt(0);first=false
                if(line.isEmpty()) {if(data.isNotEmpty()){data.setLength(data.length-1);if(!receive(event,data.toString()))return};event="";data.clear()}
                else if(line[0]!=':') {val i=line.indexOf(":");val field=if(i<0)line.toString()else line.substring(0,i);val value=if(i<0)""else line.substring(i+1).removePrefix(" ")
                    when(field){"event"->{require(value.length<=200);event=value};"data"->{require(data.length+value.length+1<=ChatFiles.MESSAGE_BYTES) {UiText.get(R.string.ui_sse_event_exceeded_8_mib)};data.append(value).append('\n')}}}
                if(eof) {if(data.isNotEmpty()) {data.setLength(data.length-1);receive(event,data.toString())};return}
            }
        }
    }
}
internal class RemoteFailure(message:String):IOException(message)
