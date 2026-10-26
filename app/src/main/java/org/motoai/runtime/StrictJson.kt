package org.motoai.runtime

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Network JSON only: Android JSONObject's permissive single-quote/comment syntax is unsuitable here. */
internal object StrictJson {
    fun parse(bytes:ByteArray,maxBytes:Int=2*1024*1024,maxNodes:Int=32768):JSONObject {
        require(bytes.size<=maxBytes) {UiText.get(R.string.ui_json_exceeds_size_limit)}
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        JsonReader(StringReader(text)).use {reader->
            reader.isLenient=false;var nodes=0
            fun value(depth:Int):Any {
                require(depth<=32 && ++nodes<=maxNodes) {"JSON exceeds nesting/value budget"}
                return when(reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        val result=JSONObject();val keys=hashSetOf<String>();reader.beginObject()
                        while(reader.hasNext()) {val key=reader.nextName();require(keys.add(key)) {"Duplicate JSON field"};result.put(key,value(depth+1))};reader.endObject();result
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        val result=JSONArray();reader.beginArray();while(reader.hasNext())result.put(value(depth+1));reader.endArray();result
                    }
                    JsonToken.STRING -> reader.nextString()
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> {reader.nextNull();JSONObject.NULL}
                    JsonToken.NUMBER -> {
                        val number=reader.nextString()
                        if(number.matches(Regex("-?(0|[1-9][0-9]*)")))number.toLongOrNull()?:error("Integer overflow")
                        else number.toDouble().also {require(it.isFinite()) {"Non-finite JSON number"}}
                    }
                    else -> error("Invalid JSON value")
                }
            }
            val root=value(0);require(root is JSONObject && reader.peek()==JsonToken.END_DOCUMENT) {"One JSON object required"};return root
        }
    }
}
