package org.motoai.runtime

import org.json.JSONArray
import org.json.JSONObject

/** Protocol-only text adapters. Opaque signed/encrypted reasoning stays in RAM for the same chat. */
internal object RemoteProtocol {
    fun request(profile:JSONObject,model:String,history:JSONArray):JSONObject {
        ProviderStore.validModel(model)
        val protocol=profile.getString("protocol");val body=JSONObject(profile.getString("options"))
            .put("model",model).put("stream",true)
        if(java.net.URI(profile.optString("base_url")).host=="openrouter.ai" && protocol=="chat") {
            val mode=profile.optString("reasoning_mode","auto")
            if(mode!="auto") {
                require(mode!="off" || profile.optJSONObject("capabilities")?.optBoolean("reasoning_mandatory")!=true){UiText.get(R.string.ui_this_model_requires_reasoning_it_cannot_be)}
                body.put("reasoning",JSONObject().put("enabled",mode=="on"))
            }
        }
        val system=StringBuilder();val messages=JSONArray()
        for(i in 0 until history.length()) {
            val m=history.getJSONObject(i);val role=m.getString("role")
            require(role in listOf("system","user","assistant"))
            if(role=="system" && protocol!="chat") {if(system.isNotEmpty())system.append('\n');system.append(m.getString("content"));continue}
            val payload=m.opt("remote_payload")
            if(role=="assistant" && payload!=null && payload!==JSONObject.NULL) {
                if(protocol=="responses") {val items=payload as JSONArray;for(j in 0 until items.length())messages.put(items.getJSONObject(j))}
                else if(protocol=="anthropic")messages.put(JSONObject().put("role",role).put("content",payload))
                else messages.put(JSONObject(payload.toString()).put("role",role))
            } else messages.put(JSONObject().put("role",role).put("content",if(role=="user")ChatFiles.parts(m.getString("content"),m.optJSONArray("attachments"),protocol)else m.getString("content")))
        }
        val limit=profile.getInt("max_output")
        when(protocol) {
            "responses" -> body.put("input",messages).put("instructions",system.toString()).put("max_output_tokens",limit).put("store",false).put("include",JSONArray().put("reasoning.encrypted_content"))
            "anthropic" -> body.put("messages",messages).put("system",system.toString()).put("max_tokens",limit)
            "chat" -> {
                body.put("messages",messages)
                // Explicit preset avoids sending obsolete max_tokens to native OpenAI reasoning models.
                if(profile.optString("limit_field")=="max_completion_tokens")body.put("max_completion_tokens",limit)else body.put("max_tokens",limit)
                if(profile.optBoolean("openai_store"))body.put("store",false)
            }
            else -> error(UiText.get(R.string.ui_unknown_protocol))
        }
        if(protocol=="chat" && ModelCapabilities.has(profile.optJSONObject("capabilities"),"output","image") && !body.has("modalities"))body.put("modalities",JSONArray().put("text").put("image"))
        require(body.toString().toByteArray(Charsets.UTF_8).size<=ChatFiles.REQUEST_BYTES) {UiText.get(R.string.ui_request_exceeds_8_mib_history_was_not)}
        return body
    }
    class Stream(private val protocol:String,private val describeError:(Int,String)->String={_,_->UiText.get(R.string.ui_provider_reported_a_stream_error_partial_response)}) {
        private val content=StringBuilder();private val reasoning=StringBuilder()
        private val blocks=sortedMapOf<Int,JSONObject>();private val details=sortedMapOf<Int,JSONObject>()
        private var payload:Any?=null;private var finish="";private var terminal=false
        val isTerminal get()=terminal
        private var usage:JSONObject?=null;private var events=0
        private val files=linkedMapOf<String,JSONObject>()
        private var emittedFiles=0
        private fun images(a:JSONArray?) {if(a==null)return;for(i in 0 until a.length()){val url=a.getJSONObject(i).optJSONObject("image_url")?.optString("url")?:error(UiText.get(R.string.ui_unsupported_api_image));val hash=java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(""){"%02x".format(it)};if(!files.containsKey(hash)){require(files.size<4){UiText.get(R.string.ui_too_many_response_files)};files[hash]=ChatFiles.generated(url,files.size)}}}
        private fun append(out:StringBuilder,text:String) {require(out.length+text.length<=512*1024) {UiText.get(R.string.ui_response_exceeds_512_kib_of_text_partial)};out.append(text)}
        private fun boundedIndex(n:Int):Int {require(n in 0..1023) {UiText.get(R.string.ui_too_many_response_blocks)};return n}
        private fun reason(value:String):String=when(value) {"stop","end_turn","stop_sequence","completed"->"eog";"length","max_tokens"->"length";"content_filter","refusal"->"blocked";else->error(UiText.get(R.string.ui_provider_used_an_unsupported_finish_mode_tool))}
        private fun checkError(j:JSONObject) {if(j.has("error") && !j.isNull("error") || j.optString("type")=="error"){val e=j.optJSONObject("error")?:j;throw RemoteFailure(describeError(e.optInt("code",400),e.optString("message")))}}
        fun event(event:String,data:String) {
            require(++events<=200000) {UiText.get(R.string.ui_too_many_response_events)}
            if(data=="[DONE]") {check(protocol=="chat" && finish.isNotEmpty()) {UiText.get(R.string.ui_stream_ended_without_finish_reason)};terminal=true;return}
            val j=StrictJson.parse(data.toByteArray(Charsets.UTF_8),maxBytes=ChatFiles.MESSAGE_BYTES)
            checkError(j)
            when(protocol) {
                "chat" -> {
                    j.optJSONObject("usage")?.let {usage=JSONObject(it.toString())}
                    val a=j.optJSONArray("choices")?:return;if(a.length()==0)return
                    require(a.length()==1);val choice=a.getJSONObject(0);require(choice.optInt("index",0)==0)
                    val d=choice.optJSONObject("delta")?:JSONObject()
                    images(d.optJSONArray("images"))
                    require(!d.has("tool_calls") && !d.has("function_call")) {UiText.get(R.string.ui_model_requested_tools_this_text_client_does)}
                    if(d.has("content") && !d.isNull("content")){require(d.get("content") is String);append(content,d.getString("content"))}
                    for(field in listOf("reasoning_content","reasoning"))if(d.has(field) && !d.isNull(field)){append(reasoning,d.getString(field));break}
                    d.optString("refusal").takeIf {it.isNotEmpty()}?.let {append(content,it)}
                    val legacy=d.optString("reasoning_content",d.optString("reasoning")).isNotEmpty()
                    d.optJSONArray("reasoning_details")?.let {a->for(i in 0 until a.length()) {
                        val item=a.getJSONObject(i);if(!legacy && item.optString("type") in listOf("reasoning.text","reasoning.summary"))append(reasoning,item.optString("text"));val n=boundedIndex(item.optInt("index",i));val v=details.getOrPut(n){JSONObject()}
                        item.keys().forEach {key->if(key in listOf("text","data","signature"))v.put(key,v.optString(key)+item.getString(key))else v.put(key,item.get(key))}
                    }}
                    if(choice.has("finish_reason") && !choice.isNull("finish_reason"))finish=reason(choice.getString("finish_reason"))
                }
                "anthropic" -> when(j.optString("type",event)) {
                    "message_start" -> {check(!terminal);usage=j.getJSONObject("message").optJSONObject("usage")?.let {JSONObject(it.toString())}}
                    "content_block_start" -> {val n=boundedIndex(j.getInt("index"));check(!blocks.containsKey(n));val b=JSONObject(j.getJSONObject("content_block").toString());require(b.getString("type") in listOf("text","thinking","redacted_thinking")) {UiText.get(R.string.ui_unsupported_claude_block_tools_are_not_executed)};blocks[n]=b
                        append(content,b.optString("text"));append(reasoning,b.optString("thinking"))}
                    "content_block_delta" -> {
                        val b=blocks[boundedIndex(j.getInt("index"))]?:error(UiText.get(R.string.ui_delta_before_block_start));val d=j.getJSONObject("delta")
                        when(d.getString("type")) {
                            "text_delta" -> {check(b.getString("type")=="text");val v=d.getString("text");append(content,v);b.put("text",b.optString("text")+v)}
                            "thinking_delta" -> {check(b.getString("type")=="thinking");val v=d.getString("thinking");append(reasoning,v);b.put("thinking",b.optString("thinking")+v)}
                            "signature_delta" -> {check(b.getString("type")=="thinking");b.put("signature",b.optString("signature")+d.getString("signature"))}
                            else -> error(UiText.get(R.string.ui_unsupported_claude_delta))
                        }
                    }
                    "message_delta" -> {val d=j.getJSONObject("delta");if(!d.isNull("stop_reason") && d.has("stop_reason"))finish=reason(d.getString("stop_reason"));j.optJSONObject("usage")?.let {v->if(usage==null)usage=JSONObject();v.keys().forEach {usage!!.put(it,v.get(it))}}}
                    "message_stop" -> {check(finish.isNotEmpty());terminal=true;payload=JSONArray().also {a->blocks.values.forEach {a.put(it)}}}
                    "content_block_stop","ping" -> Unit
                    else -> Unit // Forward-compatible metadata events carry no text or tool execution.
                }
                "responses" -> when(j.optString("type",event)) {
                    "response.output_text.delta","response.refusal.delta" -> append(content,j.getString("delta"))
                    "response.reasoning_summary_text.delta","response.reasoning_text.delta" -> append(reasoning,j.getString("delta"))
                    "response.completed","response.incomplete" -> completeResponse(j.getJSONObject("response"))
                    "response.failed" -> error(UiText.get(R.string.ui_provider_did_not_complete_the_response_partial))
                    "response.output_item.added" -> {val type=j.getJSONObject("item").optString("type");require(type in listOf("message","reasoning")) {UiText.get(R.string.ui_tools_non_text_responses_are_not_supported)}}
                    else -> Unit
                }
            }
            require(blocks.values.sumOf {it.toString().toByteArray(Charsets.UTF_8).size}+details.values.sumOf {it.toString().toByteArray(Charsets.UTF_8).size}<=1024*1024) {UiText.get(R.string.ui_internal_state_exceeded_1_mib_partial_response)}
            require(files.values.sumOf {it.optString("data").length}<=6*1024*1024){UiText.get(R.string.ui_response_files_exceed_6_mib_in_total)}
        }
        private fun completeResponse(j:JSONObject) {
            finish=when(j.getString("status")){"completed"->"eog";"incomplete"->if(j.optJSONObject("incomplete_details")?.optString("reason")=="max_output_tokens")"length" else "interrupted";else->error(UiText.get(R.string.ui_incomplete_responses_response))}
            val a=j.getJSONArray("output");val text=StringBuilder();val summaries=StringBuilder();for(i in 0 until a.length()) {
                val item=a.getJSONObject(i);require(item.getString("type") in listOf("message","reasoning")) {UiText.get(R.string.ui_tools_are_not_supported_yet)}
                if(item.optString("type")=="reasoning")item.optJSONArray("summary")?.let {v->for(k in 0 until v.length())summaries.append(v.getJSONObject(k).optString("text"))}
                if(item.optString("type")=="message") {val c=item.getJSONArray("content");for(k in 0 until c.length()){val v=c.getJSONObject(k);when(v.getString("type")){"output_text"->text.append(v.getString("text"));"refusal"->text.append(v.getString("refusal"));else->error(UiText.get(R.string.ui_non_text_response))}}}
            }
            check(text.startsWith(content.toString())) {UiText.get(R.string.ui_provider_revised_text_already_delivered)};require(text.length<=512*1024);content.clear();content.append(text)
            if(reasoning.isEmpty())append(reasoning,summaries.toString())
            payload=JSONArray(a.toString());usage=j.optJSONObject("usage");terminal=true
        }
        fun json(j:JSONObject) {
            checkError(j)
            when(protocol) {
                "responses" -> completeResponse(j)
                "anthropic" -> {
                    val a=j.getJSONArray("content");for(i in 0 until a.length()){val b=a.getJSONObject(i);require(b.getString("type") in listOf("text","thinking","redacted_thinking"));append(content,b.optString("text"));append(reasoning,b.optString("thinking"))}
                    payload=JSONArray(a.toString());usage=j.optJSONObject("usage");finish=reason(j.getString("stop_reason"));terminal=true
                }
                "chat" -> {
                    val a=j.getJSONArray("choices");require(a.length()==1);val c=a.getJSONObject(0);val m=c.getJSONObject("message")
                    images(m.optJSONArray("images"))
                    require(!m.has("tool_calls") && !m.has("function_call") && (!m.has("content") || m.isNull("content") || m.get("content") is String));append(content,if(m.isNull("content"))""else m.optString("content"));append(reasoning,m.optString("reasoning_content",m.optString("reasoning")))
                    payload=JSONObject(m.toString()).also {it.remove("images");if(files.isNotEmpty() && (it.isNull("content") || it.optString("content").isEmpty()))it.put("content","[Созданы файлы: "+files.values.joinToString {f->f.getString("name")}+"]")};usage=j.optJSONObject("usage");finish=reason(c.getString("finish_reason"));terminal=true
                }
            }
        }
        fun message(includePayload:Boolean=true):JSONObject {
            val j=JSONObject().put("role","assistant").put("content",content.toString()).put("reasoning_content",reasoning.toString())
            if(files.isNotEmpty() && (includePayload || emittedFiles!=files.size))j.put("attachments",JSONArray().also {a->files.values.forEach {a.put(it)}})
            if(!includePayload)emittedFiles=files.size
            val p=payload?:if(protocol=="chat")JSONObject().put("role","assistant").put("content",content.toString()).also {v->if(reasoning.isNotEmpty())v.put("reasoning_content",reasoning.toString());if(details.isNotEmpty())v.put("reasoning_details",JSONArray().also {a->details.values.forEach {a.put(it)}})}else null
            if(includePayload && p!=null)j.put("remote_payload",if(p is JSONArray)JSONArray(p.toString())else JSONObject(p.toString()))
            usage?.let {j.put("remote_usage",JSONObject(it.toString()))}
            return j
        }
        fun result():JSONObject {
            check(finish.isNotEmpty() && (terminal || protocol=="chat")) {UiText.get(R.string.ui_connection_ended_before_response_completion_partial_text)}
            return JSONObject().put("assistant",message()).put("finish_reason",finish).put("usage",usage?:JSONObject.NULL)
        }
    }
}
