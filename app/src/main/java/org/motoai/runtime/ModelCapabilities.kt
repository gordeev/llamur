package org.motoai.runtime

import org.json.JSONArray
import org.json.JSONObject

/** Capabilities come from discovery or an explicit user override, never a guessed model name. */
internal object ModelCapabilities {
    fun compact(m:JSONObject):JSONObject {
        val a=m.optJSONObject("architecture")?:JSONObject()
        fun strings(value:JSONArray?)=JSONArray().also {out->if(value!=null)for(i in 0 until minOf(value.length(),64)){val v=value.optString(i);if(v.length in 1..100)out.put(v)}}
        return JSONObject().put("name",m.optString("name",m.optString("id")).take(200))
            .put("input",strings(a.optJSONArray("input_modalities"))).put("output",strings(a.optJSONArray("output_modalities")))
            .put("parameters",strings(m.optJSONArray("supported_parameters"))).put("context",m.optLong("context_length"))
            .put("reasoning_mandatory",m.optJSONObject("reasoning")?.optBoolean("mandatory")?:false)
    }
    fun has(c:JSONObject?,field:String,value:String):Boolean {val a=c?.optJSONArray(field)?:return false;return (0 until a.length()).any {a.optString(it)==value}}
    fun reasoning(c:JSONObject?)=has(c,"parameters","reasoning") || has(c,"parameters","reasoning_effort") || has(c,"parameters","include_reasoning")
}
