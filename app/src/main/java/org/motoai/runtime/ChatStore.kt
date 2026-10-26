package org.motoai.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** :inference main-thread owner. Temporary content never reaches HistoryDisk.
 * Permanent bodies are loaded lazily; only safely committed inactive bodies may leave RAM. */
internal class ChatStore(private var disk:HistoryDisk?=null,private val now:()->Long={System.currentTimeMillis()},private val changed:()->Unit={}) {
    private val chats=java.util.LinkedHashMap<String,JSONObject>(16,.75f,true)
    private val catalog=linkedMapOf<String,JSONObject>()
    private val sizes=mutableMapOf<String,Int>()
    private val requests=linkedMapOf<String,String>()
    private val maxBytes=minOf(32L*1024*1024,Runtime.getRuntime().maxMemory()/8).toInt()
    private val perChatBytes=minOf(12*1024*1024,maxBytes/2)
    private var config=JSONObject().put("mode","never").put("ttl_ms",7L*86400000).put("folders",JSONArray())
    private var storageError=""
    private var cleanupError=""
    var revision=0L;private set
    init {
        try {disk?.let {config=it.settings();for(m in it.catalog()){require(m.getString("id").matches(Regex("[a-f0-9-]{36}")));m.put("active",false).put("compacting",false);catalog[m.getString("id")]=m}}}
        catch(_:Exception){storageError=UiText.get(R.string.ui_saved_chats_are_unavailable_data_was_not);catalog.clear()}
    }
    fun settings():JSONObject=JSONObject(config.toString()).put("storage_error",storageError).put("cleanup_error",cleanupError).put("saved_count",catalog.values.count {it.optBoolean("permanent")}).put("ram_bytes",used()).put("ram_limit_bytes",maxBytes).put("resident_count",chats.size)
    private fun signal(){revision++;changed()}
    private fun used()=sizes.values.sum()
    private fun quotedBytes(text:String)=JSONObject.quote(text).toByteArray(Charsets.UTF_8).size
    private fun expiry(chat:JSONObject):Long=if(chat.optBoolean("permanent") && config.optString("mode")=="auto")chat.optLong("updated_at",now())+config.optLong("ttl_ms")else 0
    private fun metadata(chat:JSONObject):JSONObject {
        val a=chat.getJSONArray("messages");val last=if(a.length()==0)null else a.getJSONObject(a.length()-1)
        val m=JSONObject().put("id",chat.getString("id")).put("title",chat.getString("title")).put("model",chat.getString("model"))
            .put("active",chat.optBoolean("active")).put("permanent",chat.optBoolean("permanent")).put("folder_id",chat.optString("folder_id"))
            .put("created_at",chat.optLong("created_at")).put("updated_at",chat.optLong("updated_at")).put("expires_at",expiry(chat))
            .put("pinned",chat.optJSONArray("pinned")?:JSONArray()).put("message_count",a.length()).put("preview",last?.optString("content")?.replace('\n',' ')?.take(160)?.ifEmpty {last.optJSONArray("attachments")?.optJSONObject(0)?.optString("name").orEmpty()}?:"")
            .put("view_revision",chat.optLong("view_revision")).put("save_error",chat.optString("save_error")).put("answer_id",chat.optString("answer_id"))
        chat.optJSONObject("remote")?.let {r->m.put("remote",JSONObject().also {v->for(k in listOf("id","name","model","protocol"))v.put(k,r.optString(k))})}
        m.put("compacting",chat.optBoolean("compacting")).put("compaction_error",chat.optString("compaction_error")).put("compact_auto_paused",chat.optBoolean("compact_auto_paused")).put("compact_window",chat.optInt("compact_window"))
        m.put("compaction_active",chat.has("compaction")).put("compaction_pending",chat.has("compact_preview"))
        m.put("compaction_through",chat.optJSONObject("compaction")?.optInt("through")?:0)
        return m
    }
    private fun refresh(chat:JSONObject,save:Boolean=true,draftOnly:Boolean=false) {
        val id=chat.getString("id");chat.put("view_revision",chat.optLong("view_revision")+1);val m=metadata(chat)
        if(save && chat.optBoolean("permanent")) {
            try {check(storageError.isEmpty());val store=disk?:error(UiText.get(R.string.ui_storage_not_connected));chat.remove("save_error");m.put("save_error","")
                if(draftOnly)store.saveDraft(chat,m)else store.save(chat,m)
            }catch(_:Exception){chat.put("save_error",UiText.get(R.string.ui_changes_are_only_in_ram_saving_failed));m.put("save_error",chat.getString("save_error"))}
        }
        catalog[id]=m;sizes[id]=chat.toString().toByteArray(Charsets.UTF_8).size;signal()
    }
    private fun room(extra:Int,exclude:String="") {
        while(used()+extra>maxBytes-4096) {
            val victim=chats.entries.firstOrNull {it.key!=exclude && it.value.optBoolean("permanent") && !it.value.optBoolean("active") && it.value.optString("save_error").isEmpty()}
                ?:error(UiText.get(R.string.ui_temporary_active_chat_memory_is_full_close))
            chats.remove(victim.key);sizes.remove(victim.key)
        }
    }
    fun list():JSONArray {expire();return JSONArray().also {a->catalog.values.filter {it.optBoolean("active") || it.optLong("expires_at") !in 1..now()}.forEach {a.put(JSONObject(it.toString()))}}}
    fun get(id:String):JSONObject {
        val m=catalog[id]?:error(UiText.get(R.string.ui_chat_unavailable_temporary_history_ended_or_saved))
        if(m.optLong("expires_at") in 1..now() && !m.optBoolean("active")){delete(id);error(UiText.get(R.string.ui_chat_deleted_under_its_retention_policy))}
        chats[id]?.let {return it}
        check(m.optBoolean("permanent") && storageError.isEmpty()) {UiText.get(R.string.ui_saved_chat_unavailable_data_was_not_deleted)}
        val chat=disk!!.load(id)
        for(k in listOf("title","folder_id","permanent","pinned","updated_at","view_revision"))if(m.has(k))chat.put(k,m.get(k))
        if(chat.optBoolean("active")) {
            chat.put("active",false).put("recovered_after_restart",true)
            if(chat.optBoolean("compacting"))chat.put("compacting",false).put("compaction_error",UiText.get(R.string.ui_compaction_interrupted_by_restart_original_history_kept)).put("compact_auto_paused",true)
            val a=chat.getJSONArray("messages");if(a.length()>0 && a.getJSONObject(a.length()-1).optString("status")=="generating")a.getJSONObject(a.length()-1).put("status","interrupted")
        }
        val size=chat.toString().toByteArray(Charsets.UTF_8).size;require(size<=perChatBytes);room(size);chats[id]=chat;sizes[id]=size;return chat
    }
    fun create(model:String,remote:JSONObject?=null,permanent:Boolean=false,folder:String=""):JSONObject {
        expire();require(catalog.size<1032){UiText.get(R.string.ui_limit_of_1000_saved_and_32_temporary)}
        require(permanent || catalog.values.count {!it.optBoolean("permanent")}<32){UiText.get(R.string.ui_32_temporary_chats_already_in_memory_close)}
        if(permanent)check(disk!=null && storageError.isEmpty() && config.optString("mode")!="never"){UiText.get(R.string.ui_explicitly_allow_permanent_chat_storage_in_settings)}
        require(folder.isEmpty() || folders().any {it.getString("id")==folder})
        val chat=JSONObject().put("id",UUID.randomUUID().toString()).put("model",model).put("title",UiText.get(R.string.ui_new_chat)).put("messages",JSONArray()).put("active",false).put("draft","")
            .put("permanent",permanent).put("folder_id",folder).put("pinned",JSONArray()).put("created_at",now()).put("updated_at",now())
        if(remote!=null)chat.put("remote",JSONObject(remote.toString()))
        room(chat.toString().toByteArray().size);chats[chat.getString("id")]=chat;refresh(chat);return chat
    }
    fun assign(id:String,model:String,remote:JSONObject?=null):JSONObject {
        val chat=get(id);check(!chat.optBoolean("active") && chat.getJSONArray("messages").length()==0){UiText.get(R.string.ui_start_a_new_chat_to_change_the)}
        chat.put("model",model);if(remote==null)chat.remove("remote")else chat.put("remote",JSONObject(remote.toString()));refresh(chat);return chat
    }
    fun delete(id:String) {
        val m=catalog[id]?:error(UiText.get(R.string.ui_chat_already_deleted));require(!m.optBoolean("active")){UiText.get(R.string.ui_stop_the_response_first)}
        if(m.optBoolean("permanent"))disk!!.delete(id)
        chats.remove(id);catalog.remove(id);sizes.remove(id);requests.entries.removeAll {it.value==id};signal()
    }
    fun clear(){require(catalog.values.none {it.optBoolean("active")}){UiText.get(R.string.ui_stop_the_response_first)};disk?.configure(config,clear=true);chats.clear();catalog.clear();sizes.clear();requests.clear();signal()}
    fun draft(id:String,text:String,baseRequestId:String?=null) {
        require(text.toByteArray().size<=512*1024){UiText.get(R.string.ui_draft_exceeds_512_kib)};val chat=get(id)
        if(baseRequestId!=null && baseRequestId!=chat.optString("request_id"))return
        val delta=quotedBytes(text)-quotedBytes(chat.optString("draft"));room(delta,id);require(sizes.getValue(id)+delta<=perChatBytes-4096){UiText.get(R.string.ui_chat_memory_full_text_was_not_truncated)}
        chat.put("draft",text);refresh(chat,draftOnly=true)
    }
    fun preview(id:String,prompt:String,attachments:JSONArray=JSONArray()):JSONArray {
        require(prompt.toByteArray().size<=512*1024){UiText.get(R.string.ui_message_exceeds_512_kib)}
        return ContextCompactor.working(if(id.isEmpty())null else get(id),prompt,attachments)
    }
    fun duplicate(request:String)=requests[request]
    fun begin(id:String,requestId:String,prompt:String,attachments:JSONArray=JSONArray()):JSONObject {
        require(requestId.matches(Regex("[a-zA-Z0-9_-]{1,80}")));require(duplicate(requestId)==null){UiText.get(R.string.ui_request_already_accepted)};require(requests.size<32768){UiText.get(R.string.ui_session_request_limit_reached_close_unused_chats)}
        val chat=get(id);val files=ChatFiles.validate(attachments,chat.optJSONObject("remote"));require(!chat.optBoolean("active"));require((prompt.isNotBlank() || files.length()>0) && prompt.toByteArray().size<=512*1024){UiText.get(R.string.ui_enter_a_message_up_to_512_kib)}
        val a=chat.getJSONArray("messages");require(a.length()<1000){UiText.get(R.string.ui_too_many_messages_start_a_new_chat)}
        val user=JSONObject().put("role","user").put("content",prompt).put("created_at",now());if(files.length()>0)user.put("attachments",files);room(user.toString().toByteArray().size+1024*1024,id)
        val epoch=chat.optJSONObject("compaction")?.optString("id").orEmpty();user.put("context_epoch",epoch)
        val input=preview(id,prompt,files);require(input.toString().toByteArray().size+2048<=ChatFiles.REQUEST_BYTES){UiText.get(R.string.ui_full_history_exceeds_the_8_mib_request)}
        require(sizes.getValue(id)+user.toString().toByteArray().size+4096<perChatBytes){UiText.get(R.string.ui_this_chat_s_memory_is_full_start)}
        chat.remove("compact_preview");a.put(user);if(a.length()==1)chat.put("title",prompt.lineSequence().first().take(60).ifBlank {files.optJSONObject(0)?.optString("name")?:UiText.get(R.string.ui_new_chat)})
        a.put(JSONObject().put("role","assistant").put("content","").put("reasoning_content","").put("status","generating").put("created_at",now()).put("context_epoch",epoch))
        chat.put("active",true).put("draft","").put("request_id",requestId).put("updated_at",now());requests[requestId]=id;refresh(chat)
        return JSONObject().put("messages",input)
    }
    fun output(id:String,message:JSONObject,status:String="generating") {
        val chat=get(id);val a=chat.getJSONArray("messages");val old=a.getJSONObject(a.length()-1)
        val next=JSONObject(message.toString()).put("role","assistant").put("status",status).put("created_at",old.optLong("created_at",now()))
        next.put("context_epoch",old.optString("context_epoch"))
        if(!next.has("attachments") && old.has("attachments"))next.put("attachments",old.getJSONArray("attachments"))
        val delta=next.toString().toByteArray().size-old.toString().toByteArray().size;room(delta,id);require(sizes.getValue(id)+delta<=perChatBytes-4096){UiText.get(R.string.ui_response_exceeds_chat_memory_budget_partial_response)}
        a.put(a.length()-1,next);chat.put("view_revision",chat.optLong("view_revision")+1);sizes[id]=sizes.getValue(id)+delta
        // No disk write, catalog serialization or expiry scheduling per generated token.
    }
    fun finish(id:String,message:JSONObject?,status:String,outputId:String) {
        val chat=get(id)
        try {if(message!=null)output(id,message,status)else {val a=chat.getJSONArray("messages");a.getJSONObject(a.length()-1).put("status",status)}}
        finally {chat.put("active",false).put("answer_id",outputId).put("updated_at",now());val a=chat.getJSONArray("messages");a.getJSONObject(a.length()-1).put("answer_id",outputId);refresh(chat)}
    }
    fun findAnswer(answer:String):String? {
        if(!answer.matches(Regex("[a-f0-9-]{36}")))return null
        chats.values.firstOrNull {c->val a=c.getJSONArray("messages");(0 until a.length()).any {a.getJSONObject(it).optString("answer_id")==answer}}?.let {return it.getString("id")}
        return catalog.values.firstOrNull {it.optString("answer_id")==answer}?.getString("id") ?: if(storageError.isEmpty())disk?.findAnswer(answer)?.takeIf {catalog.containsKey(it)}else null
    }
    /** UI polling returns only a changed tail, never repeatedly transports an old long prompt. */
    fun poll(id:String,since:Long,count:Int,attachmentCount:Int=-1):JSONObject {
        val c=get(id);val revision=c.optLong("view_revision");val a=c.getJSONArray("messages")
        if(since==revision)return JSONObject().put("unchanged",true).put("view_revision",revision)
        if(since<0 || count!=a.length() || a.length()==0) {
            val view=JSONObject(c.toString());val messages=view.getJSONArray("messages");for(i in 0 until messages.length())messages.getJSONObject(i).remove("remote_payload")
            return JSONObject().put("chat",view)
        }
        val tail=JSONObject(a.getJSONObject(a.length()-1).toString());tail.remove("remote_payload")
        if(attachmentCount>=0 && attachmentCount==(tail.optJSONArray("attachments")?.length()?:0)){tail.remove("attachments");tail.put("preserve_attachments",true)}
        val m=metadata(c);for(k in listOf("remote_error","recovered_after_restart"))if(c.has(k))m.put(k,c.get(k))
        return JSONObject().put("tail",tail).put("metadata",m).put("view_revision",revision)
    }
    fun commit(id:String){refresh(get(id))}
    fun compactStart(id:String) {val c=get(id);check(!c.optBoolean("active"));require(c.getJSONArray("messages").length()>=2){UiText.get(R.string.ui_at_least_one_model_response_is_required)};c.put("active",true).put("compacting",true).remove("compaction_error");refresh(c)}
    fun compactEnd(id:String,candidate:JSONObject?,error:String="",pause:Boolean=false) {
        val c=get(id)
        if(candidate!=null){val delta=candidate.toString().toByteArray().size;room(delta,id);require(sizes.getValue(id)+delta+4096<=perChatBytes){UiText.get(R.string.ui_not_enough_memory_for_summary_original_history)};c.put("compact_preview",candidate).put("answer_id",candidate.getString("id"))}
        c.put("active",false).put("compacting",false)
        if(error.isNotEmpty())c.put("compaction_error",error)else c.remove("compaction_error")
        if(pause)c.put("compact_auto_paused",true);refresh(c)
    }
    fun compactApply(id:String):JSONObject {
        val c=get(id);check(!c.optBoolean("active"));val p=c.getJSONObject("compact_preview")
        require(p.getInt("message_count")==c.getJSONArray("messages").length() && p.optString("previous_id")==c.optJSONObject("compaction")?.optString("id").orEmpty()){ UiText.get(R.string.ui_history_changed_create_the_summary_again) }
        require(p.getInt("through") in 2..c.getJSONArray("messages").length() && p.getInt("through")%2==0)
        c.put("compaction",p);c.remove("compact_preview");c.remove("compaction_error");refresh(c);return c
    }
    fun compactUndo(id:String):JSONObject {val c=get(id);check(!c.optBoolean("active"));c.remove("compaction");c.remove("compact_preview");c.remove("compaction_error");c.put("compact_auto_paused",true);refresh(c);return c}
    fun compactDiscard(id:String):JSONObject {val c=get(id);check(!c.optBoolean("active"));c.remove("compact_preview");refresh(c);return c}
    fun compactWindow(id:String,window:Int):JSONObject {require(window==0 || window in 1024..2097152);val c=get(id);check(!c.optBoolean("active"));c.put("compact_window",window);c.remove("compact_auto_paused");refresh(c);return c}
    fun compactResume(id:String):JSONObject {val c=get(id);check(!c.optBoolean("active"));c.remove("compact_auto_paused");refresh(c);return c}

    private fun editMetadata(id:String,mutate:(JSONObject)->Unit) {
        val old=catalog[id]?:error(UiText.get(R.string.ui_chat_deleted));val m=JSONObject(old.toString()).put("view_revision",old.optLong("view_revision")+1);mutate(m)
        val c=chats[id]
        if(m.optBoolean("permanent") && m.optString("save_error").isEmpty())disk!!.updateCatalog(m)
        catalog[id]=m
        c?.let {for(k in listOf("title","folder_id","pinned","view_revision"))it.put(k,m.get(k));sizes[id]=it.toString().toByteArray().size}
        signal()
    }
    fun rename(id:String,title:String){require(title.trim().length in 1..100);editMetadata(id){it.put("title",title.trim())}}
    fun pin(id:String,index:Int,pinned:Boolean) {
        val c=get(id);require(index in 0 until c.getJSONArray("messages").length());val a=c.optJSONArray("pinned")?:JSONArray();val values=(0 until a.length()).map {a.getInt(it)}.toMutableSet();if(pinned)values.add(index)else values.remove(index);require(values.size<=50){UiText.get(R.string.ui_up_to_50_pinned_messages)};editMetadata(id){it.put("pinned",JSONArray(values.sorted()))}
        if(c.optBoolean("active"))refresh(c) // Explicit pin checkpoints this partial answer once.
    }
    fun folders():List<JSONObject> {val a=config.optJSONArray("folders")?:JSONArray();return (0 until a.length()).map {JSONObject(a.getJSONObject(it).toString())}}
    fun move(id:String,folder:String){require(folder.isEmpty() || folders().any {it.getString("id")==folder});editMetadata(id){it.put("folder_id",folder)}}
    fun folder(id:String?,name:String?,remove:Boolean=false):JSONObject {
        check(storageError.isEmpty()) {storageError};val values=folders().toMutableList();val existing=values.firstOrNull {it.getString("id")==id}
        if(remove){require(existing!=null);values.remove(existing)}else {
            val title=name!!.trim();require(title.length in 1..40 && values.none {it.getString("id")!=id && it.getString("name").equals(title,true)}){UiText.get(R.string.ui_folder_name_1_40_characters_no_duplicates)}
            if(existing!=null)existing.put("name",title)else {require(values.size<24){UiText.get(R.string.ui_up_to_24_folders)};values.add(JSONObject().put("id",UUID.randomUUID().toString()).put("name",title))}
        }
        val updated=JSONObject(config.toString()).put("folders",JSONArray(values))
        val moved=if(remove)catalog.values.filter {it.optString("folder_id")==id}.map {JSONObject(it.toString()).put("folder_id","")}else emptyList()
        disk?.configure(updated,moved.filter {it.optBoolean("permanent") && it.optString("save_error").isEmpty()});config=updated
        for(m in moved){catalog[m.getString("id")]=m;chats[m.getString("id")]?.put("folder_id","")}
        signal();return settings()
    }
    fun policy(mode:String,ttl:Long=7L*86400000):JSONObject {
        require(mode in listOf("never","keep","auto"));require(ttl in listOf(3600000L,86400000L,7L*86400000,30L*86400000));check(storageError.isEmpty()){storageError}
        check(mode=="never" || disk!=null){UiText.get(R.string.ui_storage_not_connected)};require(catalog.values.none {it.optBoolean("active")}){UiText.get(R.string.ui_wait_until_the_response_finishes_before_changing)}
        val updated=JSONObject(config.toString()).put("mode",mode).put("ttl_ms",ttl)
        if(mode=="never") {
            // Load saved records only if already live; clearing disk does not copy unseen history into RAM.
            disk?.configure(updated,clear=true);config=updated
            for(id in catalog.keys.toList()){val c=chats[id];if(c==null){catalog.remove(id);requests.entries.removeAll {it.value==id}}else {c.put("permanent",false).remove("save_error");catalog[id]=metadata(c)}}
        } else {
            val metas=catalog.values.filter {it.optBoolean("permanent")}.map {JSONObject(it.toString()).put("expires_at",if(mode=="auto")it.optLong("updated_at")+ttl else 0)}
            disk!!.configure(updated,metas);config=updated;for(m in metas)catalog[m.getString("id")]=m
        }
        signal();expire();return settings()
    }
    fun permanent(id:String,value:Boolean):JSONObject {
        val c=get(id);require(!c.optBoolean("active")){UiText.get(R.string.ui_finish_the_response_first)}
        if(value)check(disk!=null && storageError.isEmpty() && config.optString("mode")!="never"){UiText.get(R.string.ui_allow_permanent_chat_storage_first)}else if(c.optBoolean("permanent"))disk!!.delete(id)
        c.put("permanent",value);c.remove("save_error");refresh(c);return c
    }
    fun expire(){val expired=catalog.values.filter {it.optBoolean("permanent") && !it.optBoolean("active") && it.optLong("expires_at") in 1..now()}.map {it.getString("id")};for(id in expired)try{delete(id)}catch(_:Exception){cleanupError=UiText.get(R.string.ui_could_not_clean_up_expired_chats_they)}}
    fun resetStorage():JSONObject {
        require(catalog.values.none {it.optBoolean("active")}){UiText.get(R.string.ui_finish_the_response_first)};val replacement=disk?.erasedReplacement()?:error(UiText.get(R.string.ui_storage_not_connected));disk=replacement
        config=JSONObject().put("mode","never").put("ttl_ms",7L*86400000).put("folders",JSONArray());storageError="";cleanupError="";catalog.clear()
        for(c in chats.values){c.put("permanent",false).put("folder_id","").remove("save_error");catalog[c.getString("id")]=metadata(c)}
        signal();return settings()
    }
    fun nextExpiry():Long?=catalog.values.filter {it.optBoolean("permanent") && !it.optBoolean("active") && it.optLong("expires_at")>0}.minOfOrNull {it.optLong("expires_at")}
}
