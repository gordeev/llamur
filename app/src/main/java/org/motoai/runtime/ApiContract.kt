package org.motoai.runtime

import org.json.JSONArray
import org.json.JSONObject

internal class ApiError(val status: Int, val code: String, message: String) : RuntimeException(message) {
    fun json() = JSONObject().put("error", JSONObject().put("message", message).put("type", if(status==401) "authentication_error" else "invalid_request_error").put("code",code))
}
internal object ApiContract {
    data class Request(val native: JSONObject, val stream: Boolean, val usage: Boolean)
    fun integer(value: Any?, name: String, low: Long, high: Long): Long {
        if (value !is Int && value !is Long) throw ApiError(400,"invalid_parameter","$name must be an integer")
        val n=(value as Number).toLong(); if(n !in low..high)throw ApiError(400,"invalid_parameter","$name outside $low..$high"); return n
    }
    fun parse(body: JSONObject, model: String, context: Int): Request {
        val allowed=setOf("model","messages","stream","stream_options","max_tokens","max_completion_tokens","temperature","seed","n","stop","top_p")
        body.keys().forEach { if(it !in allowed)throw ApiError(400,"unsupported_parameter","Unsupported parameter: $it") }
        if(body.opt("model") != model)throw ApiError(404,"model_not_found","Select the loaded model ID from /v1/models")
        if(body.has("n") && integer(body.get("n"),"n",1,1)!=1L)throw ApiError(400,"unsupported_parameter","Only n=1 is supported")
        if(body.has("top_p") && (body.opt("top_p") !is Number || body.getDouble("top_p") != 1.0))throw ApiError(400,"unsupported_parameter","Only default top_p=1 is currently supported")
        if(body.has("stream") && body.opt("stream") !is Boolean)throw ApiError(400,"invalid_parameter","stream must be boolean")
        val stream=body.optBoolean("stream",false)
        val streamOptions=body.optJSONObject("stream_options")
        if(body.has("stream_options") && !body.isNull("stream_options") && streamOptions==null)throw ApiError(400,"invalid_parameter","stream_options must be an object")
        streamOptions?.keys()?.forEach { if(it!="include_usage")throw ApiError(400,"unsupported_parameter","Unsupported stream option: $it") }
        if(streamOptions?.has("include_usage")==true && streamOptions.opt("include_usage") !is Boolean)throw ApiError(400,"invalid_parameter","include_usage must be boolean")
        val reserve=when {
            body.has("max_tokens") && body.has("max_completion_tokens") -> throw ApiError(400,"invalid_parameter","Supply only one output limit")
            body.has("max_completion_tokens") -> integer(body.get("max_completion_tokens"),"max_completion_tokens",1,context.toLong()).toInt()
            body.has("max_tokens") -> integer(body.get("max_tokens"),"max_tokens",1,context.toLong()).toInt()
            else -> minOf(2048,context/2)
        }
        val temperature=if(body.has("temperature")) {
            if(body.opt("temperature") !is Number)throw ApiError(400,"invalid_parameter","temperature must be a number")
            body.getDouble("temperature").also { if(!it.isFinite() || it !in 0.0..5.0)throw ApiError(400,"invalid_parameter","Invalid temperature") }
        } else 0.0
        val seed=if(body.has("seed"))integer(body.get("seed"),"seed",0,4294967295L) else 42L
        val messages=body.optJSONArray("messages") ?: throw ApiError(400,"invalid_messages","messages must be an array")
        if(messages.length() !in 1..1024)throw ApiError(400,"invalid_messages","Supply 1..1024 messages")
        val clean=JSONArray()
        for(i in 0 until messages.length()) {
            val m=messages.optJSONObject(i) ?: throw ApiError(400,"invalid_messages","Each message must be an object")
            m.keys().forEach { if(it !in setOf("role","content","reasoning_content"))throw ApiError(400,"unsupported_parameter","Unsupported message field: $it") }
            val role=m.optString("role")
            if(role !in setOf("system","user","assistant") || m.opt("content") !is String || (m.has("reasoning_content") && m.opt("reasoning_content") !is String))throw ApiError(400,"invalid_messages","Text system/user/assistant messages required; tools and images are unsupported")
            if(role!="assistant" && m.optString("reasoning_content").isNotEmpty())throw ApiError(400,"invalid_messages","reasoning_content is supported only for assistant messages")
            clean.put(JSONObject().put("role",role).put("content",m.getString("content")).put("reasoning_content",m.optString("reasoning_content")))
        }
        val stops=JSONArray()
        if(body.has("stop") && !body.isNull("stop")) {
            val supplied=body.get("stop")
            when(supplied) {
                is String -> stops.put(supplied)
                is JSONArray -> for(i in 0 until supplied.length()) {
                    if(supplied.opt(i) !is String)throw ApiError(400,"invalid_parameter","stop entries must be strings")
                    stops.put(supplied.getString(i))
                }
                else -> throw ApiError(400,"invalid_parameter","stop must be a string or array")
            }
            if(stops.length()>4)throw ApiError(400,"invalid_parameter","At most four stop strings")
            for(i in 0 until stops.length())if(stops.getString(i).toByteArray().size !in 1..256)throw ApiError(400,"invalid_parameter","stop strings must contain 1..256 UTF-8 bytes")
        }
        return Request(JSONObject().put("chat",true).put("thinking",true).put("parse_response",true)
            .put("stop",stops).put("top_p",1.0).put("messages",clean).put("max_tokens",reserve).put("temperature",temperature).put("seed",seed),stream,streamOptions?.optBoolean("include_usage",false)==true)
    }
}
