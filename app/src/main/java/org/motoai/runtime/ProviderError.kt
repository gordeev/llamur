package org.motoai.runtime

import org.json.JSONObject

/** Only a bounded JSON message is surfaced; credentials and arbitrary HTML stay out of UI/logs. */
internal object ProviderError {
    fun describe(status:Int,message:String,profile:JSONObject):String {
        val lower=message.lowercase(java.util.Locale.ROOT)
        if("free model training" in lower || "data policy" in lower || "guardrail restrictions" in lower)
            return UiText.get(R.string.ui_openrouter_rejected_the_model_due_to_account, (status))
        var clean=message
        val secret=profile.optJSONObject("secret")
        secret?.optString("key")?.takeIf {it.isNotEmpty()}?.let {clean=clean.replace(it,UiText.get(R.string.ui_key_hidden))}
        secret?.optJSONObject("headers")?.let {headers->headers.keys().forEach {name->headers.optString(name).takeIf {it.length>=4}?.let {clean=clean.replace(it,UiText.get(R.string.ui_hidden))}}}
        clean=clean.replace(Regex("(?i)Bearer\\s+[^\\s\"<>]+"),UiText.get(R.string.ui_bearer_hidden))
            .replace(Regex("sk-[A-Za-z0-9_-]{8,}"),UiText.get(R.string.ui_key_hidden))
            .replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"),"").take(700).trim()
        val hint=when(status){401,403->UiText.get(R.string.ui_check_key_permissions_and_account);404->UiText.get(R.string.ui_check_model_id_availability_and_base_url);429->UiText.get(R.string.ui_provider_rate_limit_reached);else->UiText.get(R.string.ui_check_model_parameters)}
        return UiText.get(R.string.ui_provider_rejected_request_http, (status), (hint))+if(clean.isNotEmpty())".\n$clean"else UiText.get(R.string.ui_no_automatic_retry)
    }
}
