package org.motoai.runtime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One owner, RuntimeService main thread. Public metadata on disk; optional secrets encrypted. */
internal class ProviderStore(context: Context) {
    private val prefs=context.getSharedPreferences("providers-v1",Context.MODE_PRIVATE)
    private val memory get()=sessionKeys
    private val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
    private fun alias(id:String)="motoai-provider-$id-v1"
    private fun key(id:String):SecretKey = (store.getKey(alias(id),null) as? SecretKey)?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias(id),KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
    private fun secrets(id:String):JSONObject {
        memory[id]?.let {return JSONObject(it.toString())}
        val value=prefs.getString("secret:$id",null)?:return JSONObject()
        return try {val p=value.split(':');require(p.size==2)
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(id),GCMParameterSpec(128,Base64.getDecoder().decode(p[0])))
            JSONObject(String(c.doFinal(Base64.getDecoder().decode(p[1])),Charsets.UTF_8))
        }catch(_:Exception){error(UiText.get(R.string.ui_saved_connection_key_unavailable_enter_it_again))}
    }
    fun list():JSONArray=JSONArray().also {a-> prefs.getStringSet("ids",emptySet())!!.sorted().forEach {id->a.put(public(id))} }
    fun public(id:String):JSONObject {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        val p=JSONObject(prefs.getString("profile:$id",null)?:error(UiText.get(R.string.ui_connection_deleted)))
        return p.put("has_key",memory[id]?.optString("key")?.isNotEmpty()==true || p.optBoolean("key_present") && prefs.contains("secret:$id"))
    }
    fun resolve(id:String):JSONObject {val p=public(id);val s=secrets(id);check(!p.optBoolean("requires_key") || s.optString("key").isNotEmpty()) {UiText.get(R.string.ui_enter_api_key_in_connection_settings)};return p.put("secret",s)}
    fun catalogue(id:String,details:JSONObject) {public(id);check(prefs.edit().putString("catalog:$id",details.toString()).commit())}
    fun model(id:String,model:String):JSONObject {
        validModel(model);val p=public(id).put("model",model)
        val c=JSONObject(prefs.getString("capabilities:$id","{}")!!).optJSONObject(model)?:JSONObject(prefs.getString("catalog:$id","{}")!!).optJSONObject(model)
        if(c!=null)p.put("capabilities",c)
        return p
    }
    fun save(input:JSONObject):JSONObject {
        val id=input.optString("id").ifBlank {UUID.randomUUID().toString()};require(id.matches(Regex("[a-f0-9-]{36}")))
        val old=if(prefs.contains("profile:$id"))public(id)else null
        require(old!=null || prefs.getStringSet("ids",emptySet())!!.size<32) {UiText.get(R.string.ui_up_to_32_connections)}
        val name=input.getString("name").trim();require(name.length in 1..100)
        val base=endpoint(input.getString("base_url"),input.optBoolean("allow_local_http"),input.optString("allow_http_url"))
        val protocol=input.getString("protocol");require(protocol in listOf("chat","responses","anthropic"))
        val model=input.optString("model").trim();validModel(model,true)
        val limit=input.optInt("max_output",2048);require(limit in 1..131072) {UiText.get(R.string.ui_response_reserve_1_131072_tokens)}
        val options=StrictJson.parse(input.optString("options","{}").toByteArray(Charsets.UTF_8));require(options.toString().length<=8192)
        val reserved=setOf("model","messages","input","instructions","stream","store","previous_response_id","conversation","background","tools","tool_choice","max_tokens","max_output_tokens","max_completion_tokens","n","include","models","api_key","apiKey","authorization","headers","password","token","base_url")
        require(options.keys().asSequence().none {it in reserved}) {UiText.get(R.string.ui_model_history_stream_limit_tools_are_set)}
        var secret=if(old!=null && !input.optBoolean("forget_key"))secrets(id)else JSONObject()
        if(input.optBoolean("forget_key"))secret=JSONObject()
        if(input.optString("key").trim().isNotEmpty())secret.put("key",input.getString("key").trim())
        val token=secret.optString("key");require(token.length<=8192 && token.none {it<' ' || it=='\u007f'}) {UiText.get(R.string.ui_invalid_api_key)}
        val oldHeaders=secret.optJSONObject("headers")?.toString()?:"{}"
        if(input.has("headers"))secret.put("headers",StrictJson.parse(input.getString("headers").toByteArray(Charsets.UTF_8)))
        val headers=secret.optJSONObject("headers")?:JSONObject();require(headers.length()<=16 && headers.toString().length<=16384)
        val banned=setOf("authorization","x-api-key","content-type","content-length","transfer-encoding","host","connection","accept","accept-encoding","cookie","proxy-authorization","anthropic-version")
        val seen=mutableSetOf<String>();headers.keys().forEach {h->
            require(h.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,80}")) && h.lowercase(java.util.Locale.ROOT) !in banned && seen.add(h.lowercase(java.util.Locale.ROOT))) {UiText.get(R.string.ui_invalid_additional_header)}
            require(headers.get(h) is String && headers.getString(h).all {it in ' '..'~'}) {UiText.get(R.string.ui_headers_printable_ascii_only)}
        }
        val p=JSONObject().put("id",id).put("revision",UUID.randomUUID().toString()).put("name",name).put("base_url",base)
            .put("protocol",protocol).put("model",model).put("max_output",limit).put("options",options.toString())
            .put("limit_field",input.optString("limit_field","max_tokens").also {require(it in listOf("max_tokens","max_completion_tokens"))}).put("openai_store",input.optBoolean("openai_store"))
            .put("enabled_models",old?.optJSONArray("enabled_models")?:JSONArray().also {if(model.isNotEmpty())it.put(model)})
            .put("requires_key",input.optBoolean("requires_key",true)).put("key_present",token.isNotEmpty())
            .put("allow_http_url",if(base.startsWith("http://"))input.optString("allow_http_url")else "").put("allow_local_http",input.optBoolean("allow_local_http")).put("saved_key",input.optBoolean("save_key"))
            .put("reasoning_mode",input.optString("reasoning_mode","auto").also {require(it in listOf("auto","on","off"))})
        // A saved conversation keeps its source identity when a session key is re-entered
        // or a connection is renamed. Routing/protocol/parameters still require a new chat.
        val contextual=listOf("base_url","protocol","options","max_output","limit_field","openai_store","allow_local_http")
        if(old!=null && oldHeaders==headers.toString() && contextual.all {old.opt(it).toString()==p.opt(it).toString()} && old.optString("reasoning_mode","auto")==p.optString("reasoning_mode") && old.optString("allow_http_url")==p.optString("allow_http_url"))p.put("revision",old.getString("revision"))
        if(input.optBoolean("forget_key") && store.containsAlias(alias(id)))store.deleteEntry(alias(id))
        val edit=prefs.edit().putString("profile:$id",p.toString()).putStringSet("ids",prefs.getStringSet("ids",emptySet())!!+id)
        if(old!=null && (old.optString("base_url")!=base || old.optString("protocol")!=protocol)){edit.remove("catalog:$id");edit.remove("capabilities:$id")}
        if(input.optBoolean("save_key") && secret.length()>0) {
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(id))
            edit.putString("secret:$id",Base64.getEncoder().encodeToString(c.iv)+":"+Base64.getEncoder().encodeToString(c.doFinal(secret.toString().toByteArray(Charsets.UTF_8))))
        } else edit.remove("secret:$id")
        check(edit.commit()) {UiText.get(R.string.ui_could_not_save_connection)}
        if(input.optBoolean("save_key"))memory.remove(id)else memory[id]=secret
        return public(id)
    }
    fun enable(id:String,model:String,enabled:Boolean,manual:JSONObject?=null):JSONObject {
        validModel(model);val p=public(id);val old=p.optJSONArray("enabled_models")?:JSONArray();val values=(0 until old.length()).map {old.getString(it)}.toMutableSet()
        if(enabled)values.add(model)else values.remove(model);require(values.size<=64) {UiText.get(R.string.ui_up_to_64_active_models_per_connection)}
        p.put("enabled_models",JSONArray(values.sorted()));p.remove("has_key");val edit=prefs.edit().putString("profile:$id",p.toString())
        if(manual!=null){val cache=JSONObject(prefs.getString("capabilities:$id","{}")!!);val input=manual.getJSONArray("input");val output=manual.getJSONArray("output");require(input.length()<=3 && output.length()<=2);for(i in 0 until input.length())require(input.getString(i) in listOf("text","image","file"));for(i in 0 until output.length())require(output.getString(i) in listOf("text","image"));cache.put(model,JSONObject().put("input",input).put("output",output).put("manual",true));require(cache.toString().length<=65536);edit.putString("capabilities:$id",cache.toString())}
        check(edit.commit());return public(id)
    }
    fun delete(id:String) {public(id);check(prefs.edit().remove("profile:$id").remove("secret:$id").remove("catalog:$id").remove("capabilities:$id").putStringSet("ids",prefs.getStringSet("ids",emptySet())!!-id).commit());memory.remove(id);if(store.containsAlias(alias(id)))store.deleteEntry(alias(id))}
    companion object {
        private val sessionKeys=mutableMapOf<String,JSONObject>() // Owner process RAM, survives bound-service recreation.
        fun validModel(model:String,empty:Boolean=false) {require((empty && model.isEmpty()) || model.length in 1..256 && model.none {it<' ' || it=='\u007f'}) {UiText.get(R.string.ui_enter_a_model_id_up_to_256)}}
        private fun address(value:String):URI {
            val u=try {URI(value.trim())}catch(_:Exception){error(UiText.get(R.string.ui_invalid_base_url))}
            require(value.length<=2048 && u.host!=null && u.rawUserInfo==null && u.rawQuery==null && u.rawFragment==null && u.port in -1..65535 && u.port!=0) {UiText.get(R.string.ui_base_url_address_without_key_query_or)}
            require(u.scheme in listOf("http","https") && u.rawPath.orEmpty().length<1024 && !u.rawPath.orEmpty().contains("..")) {UiText.get(R.string.ui_invalid_base_url)}
            return u
        }
        fun httpUrl(value:String):String {val u=address(value);require(u.scheme=="http"){UiText.get(R.string.ui_enter_http_address_first)};return u.toASCIIString().trimEnd('/')}
        fun endpoint(value:String,allowHttp:Boolean,httpApproved:String=""):String {
            val u=address(value);val canonical=u.toASCIIString().trimEnd('/')
            require(u.scheme=="https" || allowHttp && u.host in listOf("127.0.0.1","localhost","[::1]","::1") || httpApproved==canonical) {UiText.get(R.string.ui_http_requires_address_approval)}
            return canonical
        }
        fun displayName(p:JSONObject,model:String="")=(if(p.optString("base_url").startsWith("http://"))UiText.get(R.string.ui_http_badge)+" · "else "")+(if(model.isEmpty())p.optString("name")else "$model · ${p.optString("name")}")
    }
}
