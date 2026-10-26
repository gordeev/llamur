package org.motoai.runtime

import android.app.*
import android.text.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

/** Full-screen provider flows. Secret fields stay in RAM and never enter view state. */
internal class ProviderUi(private val activity:Activity,private val style:MessengerStyle,private val pages:SettingsPages,
    private val call:(JSONObject,(JSONObject)->Unit)->Unit) {
    private fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun notice(t:String){if(!activity.isFinishing)AlertDialog.Builder(activity).setMessage(t).setPositiveButton(UiText.get(R.string.ui_ok),null).show()}
    private fun invoke(r:JSONObject,ok:(JSONObject)->Unit){call(r){if(!activity.isFinishing){if(it.optBoolean("ok"))ok(it)else notice(it.optString("message",UiText.get(R.string.ui_could_not_perform_action)))}}}
    private fun box()=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(24));isSaveEnabled=false}
    private fun label(t:String)=TextView(activity).apply {text=t;textSize=14f;setTextColor(style.muted);setPadding(dp(4),dp(10),dp(4),dp(8));isSaveEnabled=false}
    private fun button(t:String,action:()->Unit)=Button(activity).apply {text=t;isAllCaps=false;textSize=15f;setTextColor(style.text);minimumHeight=dp(52);layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)};background=style.ripple(activity,style.surface,16f);setOnClickListener {action()}}
    private fun row(b:LinearLayout,title:String,subtitle:String="",action:()->Unit){
        val row=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(14),dp(8),dp(14));minimumHeight=dp(70);background=style.ripple(activity,style.surface,16f);contentDescription=title;isFocusable=true;setOnClickListener {action()}}
        val texts=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL};texts.addView(TextView(activity).apply {text=title;textSize=16f;setTextColor(style.text);typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)})
        if(subtitle.isNotEmpty())texts.addView(TextView(activity).apply {text=subtitle;textSize=12.5f;setTextColor(style.muted);setPadding(0,dp(5),0,0)})
        row.addView(texts,LinearLayout.LayoutParams(0,-2,1f));row.addView(IconButton(activity,AppIcon.CHEVRON,"",style,ink=style.muted).apply {isClickable=false;isFocusable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(28),dp(36)));b.addView(row,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
    }
    private fun page(title:String,b:LinearLayout,replace:Boolean=false,onResume:(()->Unit)?=null){pages.open(title,ScrollView(activity).apply {isFillViewport=true;addView(b)},replace,onResume)}
    fun show(){val b=box();fun refresh(){invoke(JSONObject().put("action","providers")){r->
        b.removeAllViews();b.addView(label(UiText.get(R.string.ui_history_is_sent_only_to_the_provider)))
        val a=r.getJSONArray("providers");for(i in 0 until a.length()){val p=a.getJSONObject(i);row(b,ProviderStore.displayName(p),p.getString("base_url")){actions(p)}}
        row(b,UiText.get(R.string.ui_add_connection)){presets()}
    }};page(UiText.get(R.string.ui_api_connections),b,onResume={refresh()});b.addView(label(UiText.get(R.string.ui_loading_connections)));refresh()}
    private fun presets(){val b=box();val names=listOf("OpenRouter","OpenAI · Responses","OpenAI · Chat Completions","Anthropic · Claude",UiText.get(R.string.ui_other_compatible_server))
        names.forEachIndexed {i,name->row(b,name){edit(JSONObject().put("name",name).put("base_url",when(i){0->"https://openrouter.ai/api/v1";1,2->"https://api.openai.com/v1";3->"https://api.anthropic.com/v1";else->""})
            .put("protocol",when(i){1->"responses";3->"anthropic";else->"chat"}).put("requires_key",i!=4).put("openai_store",i==2).put("limit_field",if(i==2)"max_completion_tokens"else "max_tokens"))}}
        page(UiText.get(R.string.ui_add_provider),b)
    }
    private fun actions(p:JSONObject,replace:Boolean=false){val b=box();if(p.getString("base_url").startsWith("http://"))b.addView(httpStatus());b.addView(label(p.getString("base_url")))
        row(b,UiText.get(R.string.ui_model_catalogue),UiText.get(R.string.ui_find_and_enable_models)){chooseModel(p,true)}
        row(b,UiText.get(R.string.ui_enable_model_by_id),UiText.get(R.string.ui_if_the_catalogue_is_unavailable)){chooseModel(p,false)}
        row(b,UiText.get(R.string.ui_active_models),UiText.get(R.string.ui_available_in_new_chats)){activeModels(p)}
        row(b,UiText.get(R.string.ui_edit_connection),UiText.get(R.string.ui_key_address_response_limit_and_reasoning)){edit(p)}
        row(b,UiText.get(R.string.ui_delete_connection)){AlertDialog.Builder(activity).setTitle(UiText.get(R.string.ui_delete_connection_30e2a1)).setMessage(UiText.get(R.string.ui_key_and_settings_will_be_deleted_chats))
            .setPositiveButton(UiText.get(R.string.ui_delete)){_,_->invoke(JSONObject().put("action","provider_delete").put("provider_id",p.getString("id"))){pages.back()}}.setNegativeButton(UiText.get(R.string.ui_keep),null).show()}
        page(p.getString("name"),b,replace)
    }
    private fun activeModels(p:JSONObject){invoke(JSONObject().put("action","providers")){r->
        val profiles=r.getJSONArray("providers");val current=(0 until profiles.length()).map {profiles.getJSONObject(it)}.firstOrNull {it.getString("id")==p.getString("id")}?:return@invoke
        val b=box();if(p.getString("base_url").startsWith("http://"))b.addView(httpStatus());val a=current.optJSONArray("enabled_models")?:JSONArray();if(a.length()==0)b.addView(label(UiText.get(R.string.ui_no_active_models_yet)))
        for(i in 0 until a.length()){val id=a.getString(i);val check=CheckBox(activity).apply {text=id;setTextColor(style.text);isChecked=true;minimumHeight=dp(56)};var changing=false
            check.setOnCheckedChangeListener {_,on->if(!changing){check.isEnabled=false;call(JSONObject().put("action","provider_enable").put("provider_id",p.getString("id")).put("model",id).put("enabled",on)){answer->check.isEnabled=true;if(!answer.optBoolean("ok")){changing=true;check.isChecked=!on;changing=false;notice(answer.optString("message"))}}}};b.addView(check)}
        row(b,UiText.get(R.string.ui_add_model)){chooseModel(current,true)};page(UiText.get(R.string.ui_active_models),b)
    }}
    private fun chooseModel(p:JSONObject,fetch:Boolean){val b=box();if(p.getString("base_url").startsWith("http://"))b.addView(httpStatus());b.addView(label(UiText.get(R.string.ui_new_chats_will_use_this_server, (p.getString("name")))))
        val id=field("Model ID",p.optString("model"));b.addView(id)
        b.addView(label(UiText.get(R.string.ui_if_capabilities_are_missing_from_the_catalogue)))
        val images=CheckBox(activity).apply {text=UiText.get(R.string.ui_understands_images);minimumHeight=dp(52);setTextColor(style.text)};val pdf=CheckBox(activity).apply {text=UiText.get(R.string.ui_accepts_pdf);minimumHeight=dp(52);setTextColor(style.text)};val output=CheckBox(activity).apply {text=UiText.get(R.string.ui_returns_images_in_chat_api);minimumHeight=dp(52);setTextColor(style.text)};b.addView(images);b.addView(pdf);b.addView(output)
        b.addView(button(UiText.get(R.string.ui_enable_3a3d7a)){val m=id.text.toString().trim();try{ProviderStore.validModel(m)}catch(_:Exception){id.error=UiText.get(R.string.ui_enter_model_id);return@button}
            val request=JSONObject().put("action","provider_enable").put("provider_id",p.getString("id")).put("model",m).put("enabled",true)
            if(images.isChecked || pdf.isChecked || output.isChecked){val inputs=JSONArray().put("text");if(images.isChecked)inputs.put("image");if(pdf.isChecked)inputs.put("file");val outputs=JSONArray().put("text");if(output.isChecked)outputs.put("image");request.put("capabilities",JSONObject().put("input",inputs).put("output",outputs))}
            invoke(request){notice(UiText.get(R.string.ui_model_enabled_choose_it_when_creating_a))}})
        b.addView(button(UiText.get(R.string.ui_load_catalogue)){catalogue(p){id.setText(it)}});page(UiText.get(R.string.ui_add_model_2350fb),b);if(fetch)catalogue(p){id.setText(it)}
    }
    private fun catalogue(p:JSONObject,select:(String)->Unit){
        val root=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),0)};if(p.getString("base_url").startsWith("http://"))root.addView(httpStatus());val status=label(UiText.get(R.string.ui_loading_catalogue));root.addView(status)
        val search=field(UiText.get(R.string.ui_search_model_by_name_or_id));root.addView(search);val list=ListView(activity).apply {divider=null};root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        val retry=button(UiText.get(R.string.ui_retry_loading)){};root.addView(retry);pages.open(UiText.get(R.string.ui_catalogue, (p.getString("name"))),root)
        var all=emptyList<String>();var shown=all;var details=JSONObject()
        fun update(){val q=search.text.toString();shown=all.filter {it.contains(q,true) || details.optJSONObject(it)?.optString("name").orEmpty().contains(q,true)};status.text=UiText.get(R.string.ui_models_found, (all.size), (shown.size))
            list.adapter=object:ArrayAdapter<String>(activity,android.R.layout.simple_list_item_2,android.R.id.text1,shown){override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {val row=super.getView(position,convertView,parent);val model=shown[position];val c=details.optJSONObject(model);row.findViewById<TextView>(android.R.id.text1).apply {text=c?.optString("name")?.ifBlank {model}?:model;setTextColor(style.text)};row.findViewById<TextView>(android.R.id.text2).apply {text=model;setTextColor(style.muted)};row.minimumHeight=dp(70);return row}}}
        fun load(){retry.isEnabled=false;status.text=UiText.get(R.string.ui_loading_catalogue);call(JSONObject().put("action","provider_models").put("provider_id",p.getString("id"))){r->
            if(!root.isAttachedToWindow)return@call;retry.isEnabled=true
            if(!r.optBoolean("ok")){status.text=r.optString("message",UiText.get(R.string.ui_catalogue_unavailable));return@call}
            val a=r.getJSONArray("models");all=(0 until a.length()).map {a.getString(it)};details=r.optJSONObject("details")?:JSONObject();update()
        }}
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){update()};override fun afterTextChanged(s:Editable?){}})
        list.setOnItemClickListener {_,_,n,_->select(shown[n]);pages.back()};retry.setOnClickListener {load()};load()
    }
    private fun field(label:String,value:String="",secret:Boolean=false)=EditText(activity).apply {hint=label;contentDescription=label;setText(value);setTextColor(style.text);setHintTextColor(style.muted)
        minimumHeight=dp(52);layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(4);bottomMargin=dp(8)}
        inputType=InputType.TYPE_CLASS_TEXT or if(secret)InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine=!label.contains("JSON");if(label.contains("JSON")){minLines=2;maxLines=5};isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS;imeOptions=imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING}
    private fun httpStatus()=label(UiText.get(R.string.ui_http_connection_status)).apply {textSize=15f;setTextColor(if(style.dark)0xffffcf79.toInt()else 0xff815117.toInt());setPadding(dp(14),dp(12),dp(14),dp(12));background=style.shape(activity,if(style.dark)0xff3a3022.toInt()else 0xfffff3dc.toInt(),12f)}
    private fun edit(p:JSONObject){val b=box();val transport=httpStatus();b.addView(transport)
        fun add(t:String,v:String="",secret:Boolean=false)=field(t,v,secret).also {b.addView(label(t));b.addView(it)}
        fun check(t:String,on:Boolean)=CheckBox(activity).apply {text=t;setTextColor(style.text);minimumHeight=dp(52);isChecked=on;b.addView(this)}
        fun spinner(t:String,values:List<String>,selected:Int):Spinner {b.addView(label(t));return Spinner(activity).apply {minimumHeight=dp(56);adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,values);setSelection(selected.coerceAtLeast(0));b.addView(this)}}
        val base=add(UiText.get(R.string.ui_base_url_with_v1_or_api_v1),p.optString("base_url"))
        var approved=p.optString("allow_http_url")
        if(approved.isEmpty() && p.optBoolean("allow_local_http"))approved=runCatching {ProviderStore.endpoint(p.getString("base_url"),true).takeIf {it.startsWith("http://")}.orEmpty()}.getOrDefault("")
        val http=check(UiText.get(R.string.ui_allow_http_for_this_address),approved.isNotEmpty())
        var adjusting=false
        fun clearApproval(){approved="";adjusting=true;http.isChecked=false;adjusting=false}
        fun addressChanged(){val isHttp=base.text.toString().trim().startsWith("http://");transport.visibility=if(isHttp)View.VISIBLE else View.GONE;http.visibility=transport.visibility
            if(runCatching {ProviderStore.httpUrl(base.text.toString())}.getOrDefault("")!=approved)clearApproval()}
        fun confirmHttp(accepted:()->Unit={}) {
            val url=runCatching {ProviderStore.httpUrl(base.text.toString())}.getOrElse {clearApproval();notice(it.message?:UiText.get(R.string.ui_invalid_base_url));return}
            AlertDialog.Builder(activity).setTitle(UiText.get(R.string.ui_http_warning_title)).setMessage(UiText.get(R.string.ui_http_warning_body,url))
                .setPositiveButton(UiText.get(R.string.ui_allow_for_this_address)){_,_->if(runCatching {ProviderStore.httpUrl(base.text.toString())}.getOrDefault("")==url){approved=url;adjusting=true;http.isChecked=true;adjusting=false;accepted()}else clearApproval()}
                .setNegativeButton(UiText.get(R.string.ui_cancel)){_,_->clearApproval()}.setOnCancelListener {clearApproval()}.show()
        }
        http.setOnCheckedChangeListener {_,on->if(!adjusting){if(on)confirmHttp()else approved=""}}
        base.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){addressChanged()};override fun afterTextChanged(s:Editable?) {}});addressChanged()
        val name=add(UiText.get(R.string.ui_name),p.optString("name"));b.addView(label(UiText.get(R.string.ui_key_is_used_only_for_this_server)));val types=listOf("chat","responses","anthropic")
        val protocol=spinner(UiText.get(R.string.ui_protocol),listOf("Chat Completions","OpenAI Responses","Anthropic Messages"),types.indexOf(p.optString("protocol","chat")))
        val token=add("API key","",true);val saved=check(UiText.get(R.string.ui_save_key_protected_by_androidkeystore),p.optBoolean("saved_key"));val forget=check(UiText.get(R.string.ui_forget_previous_key_and_headers),false);val required=check(UiText.get(R.string.ui_server_requires_api_key),p.optBoolean("requires_key",true))
        val model=add(UiText.get(R.string.ui_default_model_id_can_be_added_later),p.optString("model"));val limit=add(UiText.get(R.string.ui_response_reserve_tokens),p.optInt("max_output",2048).toString()).apply {inputType=InputType.TYPE_CLASS_NUMBER}
        val modes=listOf("auto","on","off");val reasoning=spinner(UiText.get(R.string.ui_openrouter_reasoning_other_apis_use_json_below),listOf(UiText.get(R.string.ui_provider_default),UiText.get(R.string.ui_on),UiText.get(R.string.ui_off)),modes.indexOf(p.optString("reasoning_mode","auto")))
        val limits=listOf("max_tokens","max_completion_tokens");val lf=spinner(UiText.get(R.string.ui_chat_completions_limit_field),limits,limits.indexOf(p.optString("limit_field","max_tokens")))
        val noStore=check("Chat Completions: store:false (OpenAI)",p.optBoolean("openai_store"))
        b.addView(label(UiText.get(R.string.ui_extra_parameters_depend_on_the_model_an)))
        val options=add(UiText.get(R.string.ui_request_parameters_json),p.optString("options","{}"));val headers=add(UiText.get(R.string.ui_extra_headers_json_empty_keeps_existing),"",true)
        fun save() {
            val n=limit.text.toString().toIntOrNull();if(n==null || n !in 1..131072){limit.error="1–131072";return}
            if(base.text.toString().trim().startsWith("http://") && runCatching {ProviderStore.httpUrl(base.text.toString())}.getOrDefault("")!=approved){confirmHttp {save()};return}
            val normalized=runCatching {ProviderStore.endpoint(base.text.toString(),false,approved)}.getOrElse {base.error=it.message;notice(it.message?:UiText.get(R.string.ui_invalid_base_url));return}
            val legacy=p.optBoolean("allow_local_http") && normalized==p.optString("base_url") && (normalized.startsWith("https://") || http.isChecked)
            val value=JSONObject().put("name",name.text.toString()).put("base_url",base.text.toString().trim()).put("protocol",types[protocol.selectedItemPosition]).put("key",token.text.toString()).put("save_key",saved.isChecked).put("forget_key",forget.isChecked).put("requires_key",required.isChecked)
                .put("model",model.text.toString().trim()).put("max_output",n).put("options",options.text.toString().ifBlank {"{}"}).put("reasoning_mode",modes[reasoning.selectedItemPosition]).put("allow_local_http",legacy).put("allow_http_url",if(legacy)""else approved).put("limit_field",limits[lf.selectedItemPosition]).put("openai_store",noStore.isChecked)
            if(p.has("id"))value.put("id",p.getString("id"));if(headers.text.isNotBlank())value.put("headers",headers.text.toString())
            invoke(JSONObject().put("action","provider_save").put("profile",value)){r->token.text.clear();headers.text.clear();pages.back();actions(r.getJSONObject("provider"),true)}
        }
        b.addView(button(UiText.get(R.string.ui_save)){save()});page(UiText.get(R.string.ui_connection),b)
    }
}
