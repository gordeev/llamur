package org.motoai.runtime

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.BufferedOutputStream
import java.util.UUID
import java.util.concurrent.Executors

/** Only this process owns the native runtime. IPC never contains model bytes or token streams. */
class RuntimeService : Service() {
    companion object {
        const val REGISTER = 1
        const val LOAD = 2
        const val GENERATE = 3
        const val STOP = 4
        const val UNLOAD = 5
        const val EVENT = 6
        const val UNREGISTER = 7
        const val READ_OUTPUT = 8
        const val PREPARE = 9
        const val PRODUCT = 10
        const val ACTION_SESSION = "org.llamur.app.SESSION"
        const val ACTION_STOP = "org.llamur.app.STOP"
        const val CHANNEL = "inference"
        const val NOTIFICATION = 1
        fun validName(name: String) = name.matches(Regex("[a-zA-Z0-9._-]+")) && name != "." && name != ".."
        internal fun clientSnapshot(source: JSONObject): JSONObject {
            val event = JSONObject()
            source.keys().forEach { key ->
                if (key != "request" && key != "result") event.put(key, source.get(key))
            }
            source.optJSONObject("result")?.let { result ->
                val summary = JSONObject()
                // Token traces, per-token timings and corpus/probe arrays belong
                // in the explicit debug report, never a Binder status message.
                listOf("prompt_tokens", "processed_prompt_tokens", "generated_tokens", "finish_reason", "prepare_us", "reset_us",
                    "prefill_us", "decode_us", "first_target_token_us", "first_nonempty_piece_us",
                    "reused_prompt_tokens", "prefix_cache_bytes", "cache_capture_us", "cache_restore_us", "cache_status",
                    "logits_checks", "mode", "scored_tokens", "mean_nll", "perplexity", "backend_perf").forEach { key ->
                    if (result.has(key)) summary.put(key, result.get(key))
                }
                event.put("result", summary)
            }
            return event
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val clients = mutableMapOf<IBinder, Messenger>()
    private val visibleClients = mutableSetOf<IBinder>()
    private var snapshot = JSONObject().put("state", "unloaded").put("message", UiText.get(R.string.ui_model_not_loaded))
    private var busy = false
    private var planning = false
    private var planningStopped = false
    private var initialized = false
    private var loaded = false
    @Volatile private var closing = false
    private val wakeGuard = Any()
    private var activeWake: InferenceWakeLease? = null
    private var operation = 0L
    private var foreground = false
    private var model = ""
    // Access to this handle is confined to worker; keep URI ownership through mmap lifetime.
    private var modelSource: ModelStore.OpenModel? = null
    private var config = JSONObject()
    private var api: LocalApiServer? = null
    private var apiSession=""
    private var apiPublicId=""
    private var apiPublicName=""
    private var activeApi: ApiCall? = null
    private var apiStarting = false
    private val chats get()=HistoryOwner.get(this)
    private val providers by lazy {ProviderStore(this)}
    private val providerControl=Executors.newSingleThreadExecutor()
    private var activeRemote:RemoteNetwork?=null
    private var catalog:RemoteNetwork?=null
    private var unloadAfterRemote=false
    private var activeChat: String? = null
    private class CompactWork(val chatId:String,val input:JSONObject,val original:JSONObject,val profile:JSONObject?,val window:Int,val automatic:Boolean,val reply:((JSONObject)->Unit)?) {
        @Volatile var cancelled=false
        var engine:ContextCompactor?=null
        var before=0L
        var network:RemoteNetwork?=null
    }
    private var compactWork:CompactWork?=null
    private var compactNativeDone:((Result<JSONObject>)->Unit)?=null
    private var liveOutput: EphemeralOutput? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        try {
            when (message.what) {
                REGISTER -> message.replyTo?.let {
                    clients[it.binder] = it
                    if (message.data.getBoolean("ui_visible", false)) visibleClients.add(it.binder) else visibleClients.remove(it.binder)
                    send(it)
                }
                UNREGISTER -> message.replyTo?.let { clients.remove(it.binder); visibleClients.remove(it.binder) }
                PRODUCT -> message.replyTo?.let { product(message.data, it) }
                LOAD -> load(message.data)
                GENERATE -> generate(message.data)
                PREPARE -> message.replyTo?.let { prepare(message.data, it) }
                STOP -> stop(false)
                UNLOAD -> stop(true)
                READ_OUTPUT -> message.replyTo?.let { client ->
                    val output = liveOutput
                    if (output != null && message.data.getString("output_id") == output.id) {
                        val offset = message.data.getInt("offset")
                        if (message.data.getBoolean("acknowledge", false) && client.binder in visibleClients)
                            AnswerNotifications.read(this, output.id)
                        val bytes = output.read(offset)
                        client.send(Message.obtain(null, EVENT).apply { data = Bundle().apply {
                            putString("output_id", output.id); putInt("output_offset", offset)
                            putInt("output_total", output.size()); putByteArray("output_chunk", bytes)
                        } })
                    }
                }
            }
        } catch (failure: Exception) { update(snapshot.optString("state"), failure.message ?: UiText.get(R.string.ui_command_error)) }
        true
    })

    private fun product(data: Bundle, client: Messenger) {
        val id = data.getString("id") ?: return
        fun reply(value: JSONObject) {
            val fd = ProductPipe.write(value.toString().toByteArray(Charsets.UTF_8))
            try { client.send(Message.obtain(null, EVENT).apply { this.data = Bundle().apply {
                putString("product_id", id); putParcelable("payload", fd)
            } }) } catch(_:android.os.RemoteException) { /* The accepted operation outlives its UI client. */ } finally { fd.close() }
        }
        val fd = data.getParcelable<ParcelFileDescriptor>("payload") ?: return
        ProductPipe.read(fd, ChatFiles.REQUEST_BYTES) { result -> main.post {
            try {
                val input = JSONObject(String(result.getOrThrow(), Charsets.UTF_8))
                val action = input.getString("action")
                val chatId = input.optString("chat_id")
                val answer = when (action) {
                    "count" -> {
                        check(loaded && !busy && !closing) { UiText.get(R.string.ui_token_counter_is_available_when_the_model) }
                        if (chatId.isNotEmpty()) check(chats.get(chatId).getString("model") == model) { UiText.get(R.string.ui_start_a_new_chat_for_the_selected) }
                        val request = JSONObject().put("chat",true).put("thinking",input.optBoolean("thinking",true))
                            .put("messages",chats.preview(chatId,input.getString("text"),ChatFiles.validate(input.optJSONArray("attachments")?:JSONArray(),null))).put("max_tokens",input.getInt("max_tokens"))
                        val context=config.optInt("context",4096);val op=operation
                        worker.execute {
                            val counted=runCatching { (try {NativeRuntime.prepare(request)}catch(f:NativeRuntime.Failure){if(f.kind=="context_overflow")JSONObject(f.details.toString())else throw f}).put("context",context).also {
                                it.put("fits",it.getLong("prompt_tokens")+it.getLong("output_reserve")<=context)
                            } }
                            main.post {
                                if(op!=operation || closing) reply(JSONObject().put("ok",false).put("message",UiText.get(R.string.ui_model_state_changed)))
                                else counted.fold({reply(it.put("ok",true))},{reply(JSONObject().put("ok",false).put("message",it.message))})
                            }
                        }
                        return@post
                    }
                    "delete_model", "forget_model" -> {
                        val id = input.getString("model")
                        check(!busy && !closing) { UiText.get(R.string.ui_wait_for_the_operation_to_finish) }
                        check(!loaded || model != id) { UiText.get(R.string.ui_unload_the_selected_model_first) }
                        busy = true; val op = ++operation
                        worker.execute {
                            val result = runCatching { if(action=="forget_model")ModelStore.forgetExternal(this,id)else ModelStore.deleteOwned(this, id) }
                            main.post { if (op == operation) busy = false
                                reply(JSONObject().put("ok", result.isSuccess).put("message", if (result.isSuccess) UiText.get(R.string.ui_model_deleted) else result.exceptionOrNull()?.message)) }
                        }
                        return@post
                    }
                    "api_info" -> (api?.info() ?: JSONObject().put("running",false)).put("model_id",apiPublicId.ifEmpty {model}).put("model_name",apiPublicName.ifEmpty {model}).put("native_model",model)
                    "api_stop" -> { api?.close();api=null;apiSession=""; if (!busy && !closing) update(if (loaded) "ready" else "unloaded", UiText.get(R.string.ui_api_disabled)); JSONObject().put("running", false) }
                    "api_start" -> {
                        check(loaded && !busy && !closing && api == null) { UiText.get(R.string.ui_load_the_model_and_wait_until_ready) }
                        if(input.has("model"))check(input.getString("model")==model){UiText.get(R.string.ui_selected_model_changed_check_the_profile_before)}
                        val publicId=input.optString("model_id",model).trim();ProviderStore.validModel(publicId)
                        val publicName=input.optString("model_name",publicId).trim();require(publicName.length in 1..100 && publicName.none {it<' ' || it=='\u007f'})
                        val servedModel=model;val session=UUID.randomUUID().toString();apiSession=session;apiPublicId=publicId;apiPublicName=publicName
                        val lan = input.optBoolean("lan", false)
                        val port = input.optInt("port", 8080); require(port in 1024..65535)
                        busy = true; apiStarting = true; val op = ++operation
                        update("starting_api", UiText.get(R.string.ui_preparing_secure_api))
                        worker.execute {
                            var server: LocalApiServer? = null
                            try {
                                server = LocalApiServer(this, lan, port, { apiModels() }, { apiBegin(it.put("_expected_model",servedModel).put("_expected_api_session",session)) }, { apiCancel(it) }) { reason ->
                                    main.post {if(apiSession!=session)return@post;api=null;apiSession="";update(if (loaded) "ready" else "unloaded", reason) }
                                }
                                val ready = server
                                main.post {
                                    if (closing || op != operation || !apiStarting) { ready.close(); reply(JSONObject().put("ok", false).put("message", UiText.get(R.string.ui_api_startup_cancelled))) }
                                    else { api = ready; busy = false; apiStarting = false; update("ready", UiText.get(R.string.ui_api_enabled_97d824, (ready.url))); reply(ready.info().put("ok", true)) }
                                }
                            } catch (failure: Exception) {
                                server?.close()
                                main.post { if (op == operation) { busy = false; apiStarting = false; update("ready", UiText.get(R.string.ui_api_not_started)) }; reply(JSONObject().put("ok", false).put("message", failure.message)) }
                            }
                        }
                        return@post
                    }
                    "ui_language" -> {UiText.applyLegacy(input.getString("language"));JSONObject()}
                    "providers" -> JSONObject().put("providers",providers.list())
                    "provider_save" -> {check(!busy);JSONObject().put("provider",providers.save(input.getJSONObject("profile")))}
                    "provider_delete" -> {check(!busy);providers.delete(input.getString("provider_id"));JSONObject()}
                    "provider_models" -> {
                        check(catalog==null) {UiText.get(R.string.ui_catalogue_request_already_in_progress_wait_for)}
                        val public=providers.public(input.getString("provider_id"))
                        // OpenRouter's public discovery needs no key, including before login/after expiry.
                        val profile=if(public.getString("base_url")=="https://openrouter.ai/api/v1")public else providers.resolve(public.getString("id"));val network=RemoteNetwork();catalog=network
                        providerControl.execute {val result=runCatching {network.catalogue(profile)};main.post {catalog=null
                            val current=runCatching {providers.public(profile.getString("id")).getString("revision")==profile.getString("revision")}.getOrDefault(false)
                            if(!current)reply(JSONObject().put("ok",false).put("message",UiText.get(R.string.ui_connection_changed_repeat_the_check_explicitly)))
                            else result.fold({value->val saved=runCatching {providers.catalogue(profile.getString("id"),value.getJSONObject("details"))};reply(if(saved.isSuccess)value.put("ok",true)else JSONObject().put("ok",false).put("message",UiText.get(R.string.ui_catalogue_received_but_settings_could_not_be)))},{reply(JSONObject().put("ok",false).put("message",if(it is RemoteFailure)it.message else UiText.get(R.string.ui_catalogue_unavailable_check_url_network_and_api)+(it.message?.takeIf {m->m.startsWith("JSON") || m.startsWith(UiText.get(R.string.ui_catalogue_7e2f0a))}?:"")))})}}
                        return@post
                    }
                    "history" -> JSONObject().put("history",chats.settings())
                    "history_policy" -> {check(!busy);if(input.getString("mode")=="never" && chats.settings().getInt("saved_count")>0)require(input.optBoolean("confirm_clear")){UiText.get(R.string.ui_confirm_deletion_of_saved_chats)};JSONObject().put("history",chats.policy(input.getString("mode"),input.optLong("ttl_ms",7L*86400000)))}
                    "history_reset" -> {check(!busy);require(input.optBoolean("confirm_clear"));JSONObject().put("history",chats.resetStorage())}
                    "folder" -> JSONObject().put("history",chats.folder(input.optString("folder_id").takeIf {it.isNotEmpty()},input.optString("name").takeIf {it.isNotEmpty()},input.optBoolean("remove")))
                    "chat_move" -> {chats.move(chatId,input.optString("folder_id"));JSONObject().put("chat",chats.get(chatId))}
                    "chat_pin" -> {chats.pin(chatId,input.getInt("index"),input.getBoolean("pinned"));JSONObject().put("chat",chats.get(chatId))}
                    "chat_rename" -> {chats.rename(chatId,input.getString("title"));JSONObject().put("chat",chats.get(chatId))}
                    "chat_commit" -> {check(!busy);chats.commit(chatId);JSONObject().put("chat",chats.get(chatId))}
                    "chat_permanent" -> {check(!busy);JSONObject().put("chat",chats.permanent(chatId,input.getBoolean("permanent")))}
                    "blank" -> JSONObject().put("chat",chats.create("",permanent=input.optBoolean("permanent"),folder=input.optString("folder_id")))
                    "chat_model" -> {val m=input.getString("model");check(ModelStore.entries(this).any {it.id==m});JSONObject().put("chat",chats.assign(chatId,m))}
                    "chat_remote" -> {val m=input.getString("model");val p=providers.model(input.getString("provider_id"),m);JSONObject().put("chat",chats.assign(chatId,"remote:"+p.getString("id")+":"+m,p))}
                    "provider_enable" -> {check(!busy);JSONObject().put("provider",providers.enable(input.getString("provider_id"),input.getString("model"),input.optBoolean("enabled",true),input.optJSONObject("capabilities")))}
                    "remote_new" -> {
                        val m=input.getString("model");val p=providers.model(input.getString("provider_id"),m)
                        JSONObject().put("chat",chats.create("remote:"+p.getString("id")+":"+m,p))
                    }
                    "remote_send", "send" -> {admitChatSend(input,::reply);return@post}
                    "compact_start" -> {startCompaction(input,null);JSONObject().put("chat",chats.get(chatId))}
                    "compact_info" -> JSONObject().put("chat",chats.get(chatId))
                    "compact_apply" -> {check(!busy);chats.get(chatId).optJSONObject("remote")?.let {check(providers.public(it.getString("id")).getString("revision")==it.getString("revision")){UiText.get(R.string.ui_connection_changed_summary_not_applied)}};JSONObject().put("chat",chats.compactApply(chatId))}
                    "compact_undo" -> {check(!busy);JSONObject().put("chat",chats.compactUndo(chatId))}
                    "compact_discard" -> JSONObject().put("chat",chats.compactDiscard(chatId))
                    "compact_window" -> JSONObject().put("chat",chats.compactWindow(chatId,input.getInt("context")))
                    "compact_resume" -> JSONObject().put("chat",chats.compactResume(chatId))
                    "list" -> if(input.has("since_revision") && input.getLong("since_revision")==chats.revision)JSONObject().put("unchanged",true).put("revision",chats.revision)else JSONObject().put("chats",chats.list()).put("revision",chats.revision).put("history",chats.settings())
                    "get" -> JSONObject().put("chat", chats.get(chatId))
                    "chat_poll" -> chats.poll(chatId,input.optLong("view_revision",-1),input.optInt("message_count",-1),input.optInt("attachment_count",-1))
                    "answer" -> JSONObject().put("chat_id", chats.findAnswer(input.getString("answer_id")) ?: JSONObject.NULL)
                    "new" -> JSONObject().put("chat", chats.create(model))
                    "delete" -> {
                        val notices=getSystemService(NotificationManager::class.java).activeNotifications.filter {it.id==AnswerNotifications.ID && chats.findAnswer(it.tag)==chatId}.map {it.tag}
                        chats.delete(chatId);notices.forEach {AnswerNotifications.read(this,it)};JSONObject().put("chats",chats.list())
                    }
                    "clear" -> { chats.clear(); JSONObject().put("chats", chats.list()) }
                    "draft" -> { chats.draft(chatId, input.getString("text"), input.optString("base_request_id").takeIf {input.has("base_request_id")}); JSONObject() }
                    else -> error(UiText.get(R.string.ui_unknown_command))
                }
                reply(answer.put("ok", true))
            } catch (failure: Exception) { reply(JSONObject().put("ok", false).put("message", failure.message)) }
        } }
    }

    private fun nativeRequest(messages:JSONArray,reserve:Int,thinking:Boolean)=JSONObject().put("chat",true).put("messages",messages).put("max_tokens",reserve).put("thinking",thinking).put("parse_response",true)
    private fun nativeTokens(messages:JSONArray,thinking:Boolean):Long = try {NativeRuntime.prepare(nativeRequest(messages,1,thinking)).getLong("prompt_tokens")}
        catch(f:NativeRuntime.Failure){if(f.kind=="context_overflow")f.details.getLong("prompt_tokens")else throw f}
    private fun compactProfile(chat:JSONObject):JSONObject? {
        val source=chat.optJSONObject("remote")?:return null;val p=providers.resolve(source.getString("id"))
        check(p.getString("revision")==source.getString("revision")){UiText.get(R.string.ui_connection_changed_compaction_was_not_sent_to)}
        providers.model(source.getString("id"),source.getString("model")).optJSONObject("capabilities")?.let {p.put("capabilities",it);source.put("capabilities",it)}
        return p
    }
    private fun admitChatSend(input:JSONObject,reply:(JSONObject)->Unit) {
        val id=input.getString("chat_id");val duplicate=chats.duplicate(input.getString("request_id"))
        if(duplicate!=null){reply(JSONObject().put("ok",true).put("chat",chats.get(duplicate)));return}
        check(!busy && !closing){UiText.get(R.string.ui_wait_for_the_current_operation_to_finish)};val c=chats.get(id)
        require(c.has("remote")== (input.getString("action")=="remote_send"))
        val files=ChatFiles.validate(input.optJSONArray("attachments")?:JSONArray(),c.optJSONObject("remote"));val view=chats.preview(id,input.getString("text"),files)
        val automatic=input.optBoolean("auto_compact") && !c.optBoolean("compact_auto_paused") && c.getJSONArray("messages").length()>=2
        if(c.has("remote")) {
            compactProfile(c);val window=ContextCompactor.remoteWindow(c);val reserve=c.getJSONObject("remote").getInt("max_output")
            if(automatic && window>0 && (ContextCompactor.remoteTokens(c,view)+reserve>=window*0.8 || view.toString().toByteArray().size>=ChatFiles.REQUEST_BYTES*0.8)){startCompaction(input,reply);return}
            reply(sendChatDirect(input).put("ok",true));return
        }
        check(loaded && c.getString("model")==model){UiText.get(R.string.ui_load_this_chat_s_model)}
        val reserve=input.getInt("max_tokens");val window=config.getInt("context");require(reserve in 1 until window)
        busy=true;val op=operation;update("preparing",UiText.get(R.string.ui_checking_context))
        worker.execute {val count=runCatching {nativeTokens(view,input.optBoolean("thinking",true))};main.post {
            if(op!=operation || closing){reply(JSONObject().put("ok",false).put("message",UiText.get(R.string.ui_verification_cancelled)));return@post}
            busy=false
            if(snapshot.optString("state")=="stopping"){update("ready",UiText.get(R.string.ui_verification_cancelled));reply(JSONObject().put("ok",false).put("message",UiText.get(R.string.ui_check_cancelled_draft_kept)));return@post}
            try {val tokens=count.getOrThrow();if(automatic && tokens+reserve>=window*0.8){startCompaction(input,reply);return@post}
                require(tokens+reserve<=window){UiText.get(R.string.ui_input_response_exceed_context_history_was_not, (tokens), (reserve), (window))}
                reply(sendChatDirect(input).put("ok",true))
            }catch(f:Exception){update("ready",f.message?:UiText.get(R.string.ui_request_rejected));reply(JSONObject().put("ok",false).put("message",f.message))}
        }}
    }
    private fun startCompaction(input:JSONObject,parentReply:((JSONObject)->Unit)?) {
        check(!busy && !closing && compactWork==null){UiText.get(R.string.ui_wait_for_the_current_operation_to_finish)}
        val id=input.getString("chat_id");val c=chats.get(id);require(input.optString("text").toByteArray().size<=512*1024){UiText.get(R.string.ui_draft_exceeds_512_kib_a8cf23)};ChatFiles.validate(input.optJSONArray("attachments")?:JSONArray(),c.optJSONObject("remote"));val profile=compactProfile(c)
        if(profile==null)check(loaded && c.getString("model")==model){UiText.get(R.string.ui_load_this_chat_s_model_for_compaction)}
        val window=if(profile==null)config.getInt("context")else ContextCompactor.remoteWindow(c)
        require(window in 1024..2097152){UiText.get(R.string.ui_enter_the_api_window_in_compaction_settings)}
        val job=CompactWork(id,JSONObject(input.toString()),JSONObject(c.toString()),profile,window,parentReply!=null,parentReply)
        chats.compactStart(id);compactWork=job;busy=true;snapshot.put("compacting_chat",id);update("preparing",UiText.get(R.string.ui_preparing_context_compaction))
        worker.execute {val prepared=runCatching {
            val prompt=job.input.optString("text");val files=job.input.optJSONArray("attachments")?:JSONArray();val view=ContextCompactor.working(job.original,prompt,files)
            job.before=if(profile==null)nativeTokens(view,job.input.optBoolean("thinking",true))else ContextCompactor.estimate(view)
            val a=job.original.getJSONArray("messages");val previous=job.original.optJSONObject("compaction")?.optInt("through")?:0
            val normalReserve=if(profile==null)job.input.optInt("max_tokens",minOf(2048,window/2))else profile.getInt("max_output")
            val summaryReserve=minOf(2048,window/4);var through=a.length()
            for(keep in listOf(4,2,0)){val boundary=maxOf(previous,a.length()-keep);if(boundary<2 || boundary%2!=0)continue
                val placeholder=JSONObject().put("id",job.original.optJSONObject("compaction")?.optString("id").orEmpty()+"-next").put("through",boundary).put("text","Conversation memory.")
                val tail=ContextCompactor.working(job.original,prompt,files,placeholder)
                val tokens=if(profile==null)nativeTokens(tail,job.input.optBoolean("thinking",true))else ContextCompactor.estimate(tail)
                if(tokens+normalReserve+summaryReserve<=window){through=boundary;break}
            }
            job.engine=ContextCompactor(job.original,window,through)
        };main.post {if(compactWork!==job)return@post;prepared.fold({if(job.cancelled)finishCompaction(job,Result.failure(IllegalStateException(UiText.get(R.string.ui_compaction_stopped))))else if(profile!=null)remoteCompaction(job)else localCompactionStep(job)},{finishCompaction(job,Result.failure(it))})}}
    }
    private fun summaryProfile(job:CompactWork):JSONObject {
        val p=JSONObject(job.profile!!.toString());p.put("max_output",job.engine!!.output)
        val options=JSONObject(p.getString("options"));options.remove("response_format");options.remove("modalities");p.put("options",options.toString())
        p.optJSONObject("capabilities")?.put("output",JSONArray().put("text"))
        return p
    }
    private fun remoteCompaction(job:CompactWork) {
        val engine=job.engine!!;val p=summaryProfile(job);val source=job.original.getJSONObject("remote");val network=RemoteNetwork();job.network=network;activeRemote=network
        try {ProviderRequestService.enqueue(this,ProviderRequestService.Work(p,source.getString("model"),JSONArray(),network,{v->if(compactWork===job)update("preparing",UiText.get(R.string.ui_compacting_context_step, (v.optInt("compact_step",1))))},{r->finishCompaction(job,r)},sequence={changed->
            while(!engine.done){check(!job.cancelled){UiText.get(R.string.ui_compaction_stopped)};val messages=engine.next {m,reserve->ContextCompactor.estimate(m)+reserve<=job.window && runCatching {RemoteProtocol.request(p,source.getString("model"),m)}.isSuccess}
                changed(JSONObject().put("compact_step",engine.steps+1));engine.accept(network.generate(p,source.getString("model"),messages){check(!job.cancelled){UiText.get(R.string.ui_compaction_stopped)}})
            }
            val temporary=engine.candidate(job.before,0);val after=ContextCompactor.estimate(ContextCompactor.working(job.original,job.input.optString("text"),job.input.optJSONArray("attachments")?:JSONArray(),temporary))
            engine.candidate(job.before,after)
        }))}catch(f:Exception){finishCompaction(job,Result.failure(f))}
    }
    private fun localCompactionStep(job:CompactWork) {
        if(job.cancelled){finishCompaction(job,Result.failure(IllegalStateException(UiText.get(R.string.ui_compaction_stopped))));return}
        val engine=job.engine!!;busy=true;update("preparing",UiText.get(R.string.ui_compacting_context_step, (engine.steps+1)))
        worker.execute {val prepared=runCatching {
            if(engine.done){val temporary=engine.candidate(job.before,0);val after=nativeTokens(ContextCompactor.working(job.original,job.input.optString("text"),job.input.optJSONArray("attachments")?:JSONArray(),temporary),job.input.optBoolean("thinking",true));engine.candidate(job.before,after)}
            else {val messages=engine.next {m,reserve->nativeTokens(m,false)+reserve<=job.window};nativeRequest(messages,engine.output,false).put("temperature",0.0).put("seed",42).put("cache_session","")}
        };main.post {
            if(compactWork!==job)return@post
            try {val value=prepared.getOrThrow();if(job.cancelled)error(UiText.get(R.string.ui_compaction_stopped))
                if(engine.done){finishCompaction(job,Result.success(value));return@post}
                compactNativeDone={r->if(compactWork===job){r.fold({result->try {engine.accept(result);localCompactionStep(job)}catch(f:Exception){finishCompaction(job,Result.failure(f))}},{finishCompaction(job,Result.failure(it))})}}
                busy=false;generate(Bundle().apply {putString("product_request",value.toString());putBoolean("compact_request",true);putBoolean("notify_on_completion",false)})
            }catch(f:Exception){finishCompaction(job,Result.failure(f))}
        }}
    }
    private fun finishCompaction(job:CompactWork,result:Result<JSONObject>) {
        if(compactWork!==job)return
        compactWork=null;compactNativeDone=null;if(activeRemote===job.network)activeRemote=null;busy=false;snapshot.remove("compacting_chat")
        try {
            if(job.cancelled)error(UiText.get(R.string.ui_compaction_stopped))
            val candidate=result.getOrThrow();require(candidate.getLong("after_tokens")<candidate.getLong("before_tokens")){UiText.get(R.string.ui_summary_did_not_reduce_the_working_context)}
            if(job.automatic){val reserve=if(job.profile==null)job.input.getInt("max_tokens")else job.profile.getInt("max_output");require(candidate.getLong("after_tokens")+reserve<=job.window){UiText.get(R.string.ui_input_plus_response_still_does_not_fit)}}
            chats.compactEnd(job.chatId,candidate)
            if(job.automatic){chats.compactApply(job.chatId);job.reply!!.invoke(sendChatDirect(job.input).put("ok",true))}
            else {update(if(loaded)"ready"else "unloaded",UiText.get(R.string.ui_summary_ready_review_and_apply_it))
                main.postDelayed({if(!closing && visibleClients.isEmpty() && runCatching {chats.get(job.chatId).optJSONObject("compact_preview")?.optString("id")==candidate.getString("id")}.getOrDefault(false))AnswerNotifications.show(this,candidate.getString("id"),true,UiText.get(R.string.ui_summary_ready))},500)}
        }catch(f:Throwable){runCatching {chats.compactEnd(job.chatId,null,f.message?:UiText.get(R.string.ui_compaction_incomplete_history_kept),job.automatic)}
            update(if(loaded)"ready"else "unloaded",f.message?:UiText.get(R.string.ui_compaction_incomplete_history_kept));job.reply?.invoke(JSONObject().put("ok",false).put("message",f.message?:UiText.get(R.string.ui_compaction_incomplete)))
        }
        if(unloadAfterRemote){unloadAfterRemote=false;stop(true)}
    }
    private fun sendChatDirect(input:JSONObject):JSONObject {
        val chatId=input.getString("chat_id")
        return if(input.getString("action")=="remote_send") {
                        val duplicate=chats.duplicate(input.getString("request_id"))
                        if(duplicate!=null)JSONObject().put("chat",chats.get(duplicate))else {
                            check(!busy && !closing) {UiText.get(R.string.ui_wait_for_the_current_operation_to_finish)}
                            val chat=chats.get(chatId);val source=chat.getJSONObject("remote");val p=providers.resolve(source.getString("id"));providers.model(source.getString("id"),source.getString("model")).optJSONObject("capabilities")?.let {p.put("capabilities",it);source.put("capabilities",it)}
                            check(p.getString("revision")==source.getString("revision")) {UiText.get(R.string.ui_connection_changed_start_a_new_chat_history)}
                            val prior=chat.getJSONArray("messages");check(prior.length()==0 || prior.getJSONObject(prior.length()-1).optString("status") in listOf("eog","length","blocked") || (chat.optJSONObject("compaction")?.optInt("through")?:0)>=prior.length()) {UiText.get(R.string.ui_previous_response_was_interrupted_start_a_new)}
                            val files=ChatFiles.validate(input.optJSONArray("attachments")?:JSONArray(),source)
                            val history=chats.preview(chatId,input.getString("text"),files);RemoteProtocol.request(p,source.getString("model"),history)
                            val network=RemoteNetwork();val answerId=UUID.randomUUID().toString();var lastMessage:JSONObject?=null
                            chats.begin(chatId,input.getString("request_id"),input.getString("text"),files);busy=true;activeRemote=network
                            snapshot.put("remote_busy",true).put("remote_chat",chatId)
                            fun complete(result:Result<JSONObject>) {
                                if(activeRemote!==network)return
                                activeRemote=null;busy=false;snapshot.remove("remote_busy");snapshot.remove("remote_chat")
                                var successful=result.isSuccess
                                try {result.fold({r->chat.put("remote_error",JSONObject.NULL);chats.finish(chatId,r.getJSONObject("assistant"),r.getString("finish_reason"),answerId)},
                                    {f->chat.put("remote_error",if(f is RemoteFailure)f.message else UiText.get(R.string.ui_api_request_error));chats.finish(chatId,lastMessage,"interrupted",answerId)})
                                }catch(_:Exception){successful=false;runCatching {chats.finish(chatId,lastMessage,"interrupted",answerId)};chat.put("remote_error",UiText.get(R.string.ui_response_exceeded_memory_budget_partial_text_kept))}
                                update(if(loaded)"ready" else "unloaded",if(successful)UiText.get(R.string.ui_api_response_completed) else chat.optString("remote_error"))
                                if(successful)main.postDelayed({if(!closing && visibleClients.isEmpty() && chats.findAnswer(answerId)==chatId)AnswerNotifications.show(this,answerId,true)},500)
                                if(unloadAfterRemote){unloadAfterRemote=false;stop(true)}
                            }
                            try {ProviderRequestService.enqueue(this,ProviderRequestService.Work(p,source.getString("model"),history,network,{value->
                                if(activeRemote===network)try {chats.output(chatId,value);lastMessage=value;publish()}catch(_:Exception){network.cancel()}
                            },{complete(it)}))
                            }catch(_:Exception){complete(Result.failure(RemoteFailure(UiText.get(R.string.ui_could_not_start_request_open_llamur_and))))}
                            update(if(loaded)"ready" else "unloaded",UiText.get(R.string.ui_api_request))
                            JSONObject().put("chat",chat)
                        }
        }else {
                        val requestId = input.getString("request_id")
                        val duplicate = chats.duplicate(requestId)
                        if (duplicate != null) JSONObject().put("chat", chats.get(duplicate)) else {
                            check(loaded && !busy && !closing) { UiText.get(R.string.ui_wait_until_the_model_is_ready) }
                            check(chats.get(chatId).getString("model") == model) { UiText.get(R.string.ui_start_a_new_chat_for_a_different) }
                            val tokens = input.getInt("max_tokens")
                            require(tokens in 1..config.optInt("context", 4096))
                            val temperature = input.optDouble("temperature", 0.0)
                            require(temperature.isFinite() && temperature in 0.0..5.0)
                            val request = chats.begin(chatId, requestId, input.getString("text"),input.optJSONArray("attachments")?:JSONArray())
                                .put("chat", true).put("thinking", input.optBoolean("thinking", true))
                                .put("max_tokens", tokens).put("temperature", temperature).put("seed", 42)
                                .put("cache_session", "chat:" + chatId+":"+chats.get(chatId).optJSONObject("compaction")?.optString("id").orEmpty()).put("parse_response", true)
                            activeChat = chatId
                            generate(Bundle().apply { putString("product_request", request.toString()) })
                            JSONObject().put("chat", chats.get(chatId))
                        }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val done = java.util.concurrent.CountDownLatch(1)
        var result: Result<T>? = null
        main.post { result = runCatching(block); done.countDown() }
        if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw ApiError(503, "service_timeout", "Service did not respond")
        return result!!.getOrThrow()
    }
    private fun apiModels(): JSONObject = onMain {
        val models = JSONArray()
        if (loaded && !closing) models.put(JSONObject().put("id",apiPublicId.ifEmpty {model}).put("name",apiPublicName.ifEmpty {model}).put("object", "model").put("created", 0)
            .put("owned_by", "local").put("context_length", config.optInt("context", 4096)))
        JSONObject().put("object", "list").put("data", models)
    }
    private fun apiCancel(call: ApiCall) { call.cancelled.set(true); main.post {
        if (activeApi === call) NativeRuntime.cancel()
    } }
    private fun apiBegin(request: JSONObject): ApiCall {
        val call = ApiCall(request)
        main.post {
        when {
            call.cancelled.get() -> call.fail(ApiError(409, "cancelled", "Request cancelled before admission"))
            request.optString("_expected_api_session")!=apiSession || request.optString("_expected_model") != model || request.optInt("_expected_context") != config.optInt("context",4096) -> call.fail(ApiError(409,"model_changed","Loaded model or context changed before admission; request not executed"))
            !loaded || closing -> call.fail(ApiError(503, "model_not_loaded", "Load a model in llamur"))
            busy -> call.fail(ApiError(429, "engine_busy", "The shared engine is busy; no request was queued"))
            else -> {
                busy = true; activeApi = call; val op = ++operation
                val context = config.optInt("context", 4096)
                update("preparing", UiText.get(R.string.ui_api_checking_context))
                worker.execute {
                    try {
                        val counted = NativeRuntime.prepare(request)
                        if (counted.getLong("prompt_tokens") + counted.getLong("output_reserve") > context)
                            throw ApiError(400, "context_overflow", "Input ${counted.getLong("prompt_tokens")} + output reserve ${counted.getLong("output_reserve")} exceeds context $context; no input was removed")
                        main.post {
                            if (op != operation || closing || call.cancelled.get()) {
                                call.fail(ApiError(409, "cancelled", "Request cancelled"))
                                if (activeApi === call) { activeApi = null; busy = false; if (!closing) update("ready", UiText.get(R.string.ui_api_request_cancelled)) }
                            } else {
                                busy = false
                                generate(Bundle().apply { putString("product_request", request.toString()); putBoolean("api_request", true); putBoolean("notify_on_completion", false) })
                                call.admitted.countDown()
                            }
                        }
                    } catch (failure: Exception) { main.post {
                        val kind = if (failure is NativeRuntime.Failure) failure.kind else if (failure is ApiError) failure.code else "invalid_request"
                        call.fail(if (failure is ApiError) failure else ApiError(400, kind, failure.message ?: "Invalid request"))
                        if (activeApi === call) { activeApi = null; busy = false; if (!closing) update("ready", UiText.get(R.string.ui_api_request_rejected, (kind))) }
                    } }
                }
            }
        }
        }
        return call
    }

    override fun onCreate() {
        super.onCreate()
        chats;HistoryOwner.observe {publish()}
        snapshot.put("pid", Process.myPid())
        AnswerNotifications.create(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, UiText.get(R.string.ui_local_model), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stop(true) else promote()
        return START_NOT_STICKY // Never repeat a prompt or allocate a model after process death.
    }

    private fun notification(): Notification {
        val target = if (!snapshot.isNull("chat_id") || api != null || !snapshot.has("output_id")) ChatActivity::class.java else MainActivity::class.java
        val open = PendingIntent.getActivity(this, 0, Intent(this, target), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RuntimeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(UiText.get(R.string.ui_llamur_local_model))
            .setContentText(snapshot.optString("message", UiText.get(R.string.ui_done)))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, UiText.get(R.string.ui_stop_and_unload), stop).build()).build()
    }

    private fun promote() {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification)
        foreground = true
    }

    private fun send(client: Messenger) {
        try {
            client.send(Message.obtain(null, EVENT).apply { data = Bundle().apply { putString("snapshot", clientSnapshot(snapshot).toString()) } })
        } catch (_: RemoteException) { clients.remove(client.binder); visibleClients.remove(client.binder) }
    }

    private fun publish() {
        snapshot.put("chats_revision",chats.revision)
        clients.values.toList().forEach { send(it) }
    }

    private fun update(state: String, text: String) {
        snapshot.put("state", state).put("message", text).put("model", model).put("config", config)
            .put("pid", Process.myPid())
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        publish()
    }

    private fun awake(): InferenceWakeLease = synchronized(wakeGuard) {
        check(!closing) { UiText.get(R.string.ui_runtime_is_closing) }
        InferenceWakeLease(getSystemService(PowerManager::class.java), main).also { activeWake = it }
    }

    /** Exact template/tokenizer count on the owning worker; never mutates KV/recurrent state. */
    private fun prepare(data: Bundle, client: Messenger) {
        val id = data.getString("count_id") ?: error("Missing token-count request ID")
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "Invalid token-count request ID" }
        fun reply(result: JSONObject) {
            try { client.send(Message.obtain(null, EVENT).apply { this.data = Bundle().apply {
                putString("count_id", id); putString("count_result", result.toString())
            } }) } catch (_: RemoteException) { clients.remove(client.binder); visibleClients.remove(client.binder) }
        }
        if (busy || !loaded || closing) {
            reply(JSONObject().put("ok", false).put("kind", if (busy) "busy" else "not_loaded")); return
        }
        val payload = data.getString("request") ?: error("Missing token-count request")
        val context = config.optInt("context", 4096)
        val op = operation
        worker.execute {
            val result = try {
                NativeRuntime.prepare(JSONObject(payload)).also {
                    it.put("context", context).put("fits", it.getLong("prompt_tokens") + it.getLong("output_reserve") <= context)
                        .put("remaining_tokens", context - it.getLong("prompt_tokens") - it.getLong("output_reserve"))
                }
            } catch (failure: Exception) {
                JSONObject().put("ok", false).put("kind", if (failure is NativeRuntime.Failure) failure.kind else "invalid_request")
                    .put("message", failure.message)
            }
            main.post {
                if (op == operation && !closing) reply(result)
                else reply(JSONObject().put("ok", false).put("kind", "state_changed"))
            }
        }
    }
    private fun releaseWake(wake: InferenceWakeLease?) = synchronized(wakeGuard) {
        wake?.close()
        if (activeWake === wake) activeWake = null
    }

    private fun planGpuLoad(data: Bundle, entry: ModelStore.Entry, candidate: JSONObject, commandStarted: Long) {
        busy = true; planning = true; planningStopped = false
        val op = ++operation
        val ticket = NativeRuntime.ticket()
        promote(); update("loading", UiText.get(R.string.ui_estimating_memory_for_selected_gpu_profile))
        worker.execute {
            var wake: InferenceWakeLease? = null
            try {
                wake = awake()
                val backendStarted = SystemClock.elapsedRealtimeNanos()
                NativeRuntime.initialize(this, candidate.getString("backend"))
                val initializeUs = (SystemClock.elapsedRealtimeNanos() - backendStarted) / 1000
                val planStarted = SystemClock.elapsedRealtimeNanos()
                val plan = ModelStore.open(this, entry.id).use { NativeRuntime.plan(it.path, candidate, ticket) }
                    .put("first_backend_initialize_us", initializeUs)
                    .put("planning_wall_us", (SystemClock.elapsedRealtimeNanos() - planStarted) / 1000)
                main.post { if (op == operation && !closing) {
                    busy = false; planning = false
                    if (planningStopped) {
                        snapshot.put("error_kind", "cancelled")
                        update(if (loaded) "ready" else "unloaded", UiText.get(R.string.ui_estimation_cancelled))
                        saveDebugReport(data.getString("report_id")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,80}")) }, false)
                        if (!loaded) releaseForeground()
                    } else load(data, plan, commandStarted)
                } }
            } catch (failure: Throwable) {
                main.post { if (op == operation && !closing) {
                    busy = false; planning = false
                    snapshot.put("attempted_model", entry.id)
                        .put("error_kind", if (failure is NativeRuntime.Failure) failure.kind else "gpu_planning")
                    update(if (loaded) "ready" else "unloaded", UiText.get(R.string.ui_gpu_profile_not_loaded, (failure.message)))
                    saveDebugReport(data.getString("report_id")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,80}")) }, false)
                    if (!loaded) releaseForeground()
                } }
            } finally { releaseWake(wake) }
        }
    }

    private fun load(data: Bundle, gpuPlan: JSONObject? = null, commandStarted: Long = SystemClock.elapsedRealtimeNanos()) {
        if (busy || closing) return
        val name = data.getString("model") ?: error(UiText.get(R.string.ui_no_model_selected))
        require(validName(name) && name.endsWith(".gguf")) { UiText.get(R.string.ui_invalid_model_file) }
        val entry = ModelStore.entries(this).firstOrNull { it.id == name } ?: error(UiText.get(R.string.ui_model_file_not_found))
        val candidate = JSONObject(data.getString("config") ?: "{}")
        val context = candidate.optInt("context", 4096)
        require(context in 128..262144) { UiText.get(R.string.ui_invalid_context_size) }
        require(candidate.optInt("threads", 4) in 1..64) { UiText.get(R.string.ui_invalid_thread_count) }
        val batchThreads = candidate.optInt("threads_batch", 0)
        require(batchThreads in 0..64) { UiText.get(R.string.ui_invalid_prefill_thread_count) }
        if (candidate.optString("backend", "cpu") != "cpu" && gpuPlan == null) {
            val free = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            if (free.lowMemory || free.availMem < maxOf(1536L * 1024 * 1024, free.threshold * 2)) {
                snapshot.put("error_kind", "memory_admission").put("attempted_model", name)
                update(if (loaded) "ready" else "unloaded", UiText.get(R.string.ui_not_enough_memory_to_check_gpu_profile))
                saveDebugReport(data.getString("report_id")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,80}")) }, false)
                return
            }
            planGpuLoad(Bundle(data), entry, candidate, commandStarted); return
        }
        val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val available = if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 && candidate.has("_test_memory_budget_bytes"))
            candidate.getLong("_test_memory_budget_bytes").also { require(it in 0..memory.availMem) } else memory.availMem
        // Clean mmap pages are already included in MemAvailable. Count only a
        // conservative part of this process's anonymous old-model residency.
        val oldBuffers = snapshot.optJSONObject("load")?.optJSONArray("buffer_allocations")
        var oldAllocated = 0L
        if (loaded && oldBuffers != null) for (index in 0 until oldBuffers.length()) {
            val item = oldBuffers.getJSONObject(index)
            oldAllocated += item.optLong("model_bytes") + item.optLong("context_bytes") + item.optLong("compute_bytes")
        }
        val anonymous = if (loaded) runCatching { File("/proc/self/status").readLines().first { it.startsWith("RssAnon:") }
            .substringAfter(':').trim().substringBefore(' ').toLong() * 1024 }.getOrDefault(0) else 0L
        val reclaimable = minOf(oldAllocated, (anonymous - 256L * 1024 * 1024).coerceAtLeast(0))
        var admission = LoadAdmission.evaluate(entry.bytes, entry.sha256, context, candidate.optInt("batch", 512),
            candidate.optInt("micro_batch", 128), memory.totalMem, available, memory.threshold, memory.lowMemory, reclaimable,
            maxOf(candidate.optInt("threads", 4), batchThreads), candidate.optString("load_mode", "auto"), candidate.optString("weight_buffers", "native"),
            BuildConfig.STREAMED_REPACK_SOURCES, candidate.optBoolean("transient_repack", false), BuildConfig.TRANSIENT_REPACK, BuildConfig.TRANSIENT_MIN_ROWS,
            candidate.optInt("prefix_cache_mib", 0), BuildConfig.EXACT_STATE_LAYOUT, candidate.optBoolean("swa_full", true))
        if (gpuPlan != null) admission = GpuAdmission.evaluate(admission, gpuPlan, entry.sha256, candidate)
        val pressureThreshold = if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 && candidate.has("_test_load_pressure_threshold_bytes"))
            candidate.getLong("_test_load_pressure_threshold_bytes").also { require(it in maxOf(512L * 1024 * 1024, memory.threshold * 2)..memory.totalMem) }
            else maxOf(512L * 1024 * 1024, memory.threshold * 2,
                if (candidate.optString("weight_buffers", "native") != "native") admission.getLong("headroom_bytes") else 0)
        if (!admission.getBoolean("accepted")) {
            if (!loaded) { model = name; config = candidate; snapshot = JSONObject() }
            val supported = admission.getBoolean("weight_policy_supported")
            snapshot.put("load_admission", admission).put("attempted_model", name).put("error_kind", if (supported) "memory_admission" else "unsupported_profile")
            update(if (loaded) "ready" else "unloaded", if (!supported) UiText.get(R.string.ui_this_experimental_profile_failed_stability_checks_or)
                else UiText.get(R.string.ui_not_enough_free_memory_for_safe_loading, (admission.getLong("effective_available_bytes") / (1024 * 1024)), (admission.getLong("required_bytes") / (1024 * 1024))))
            saveDebugReport(data.getString("report_id")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,80}")) }, false)
            if (!loaded) releaseForeground()
            return
        }
        promote()
        busy = true
        loaded = false
        model = name
        config = candidate
        snapshot = JSONObject()
        snapshot.put("load_admission", admission)
        snapshot.put("memory_before_load", JSONObject().put("available_bytes", memory.availMem)
            .put("total_bytes", memory.totalMem).put("low_memory", memory.lowMemory).put("system_threshold_bytes", memory.threshold)
            .put("model_file_bytes", entry.bytes).put("model_location", entry.location))
        update("loading", UiText.get(R.string.ui_loading_model_into_memory))
        val op = ++operation
        // Capture before scheduling: Stop between enqueue and worker execution must not be lost.
        val ticket = NativeRuntime.ticket()
        // 0 active, 1 completed, 2 pressure cancellation. CAS prevents a late
        // watchdog tick from cancelling an already completed load.
        val watchState = java.util.concurrent.atomic.AtomicInteger(0)
        val minimumAvailable = java.util.concurrent.atomic.AtomicLong(memory.availMem)
        val peakRss = java.util.concurrent.atomic.AtomicLong(0)
        val peakAnonymous = java.util.concurrent.atomic.AtomicLong(0)
        val peakMapped = java.util.concurrent.atomic.AtomicLong(0)
        val loadStarted = SystemClock.elapsedRealtimeNanos()
        val reportId = if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            data.getString("report_id")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,80}")) } else null
        var lastLoadProgress = 0L
        fun watchReport() = JSONObject().put("minimum_available_bytes", minimumAvailable.get())
            .put("peak_sampled_rss_bytes", peakRss.get()).put("peak_sampled_anonymous_bytes", peakAnonymous.get())
            .put("peak_sampled_mapped_bytes", peakMapped.get())
            .put("threshold_bytes", pressureThreshold).put("poll_interval_ms", 100).put("pressure_abort", watchState.get() == 2)
        val watch = object : Runnable {
            override fun run() {
                if (watchState.get() != 0 || op != operation || closing) return
                val current = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                minimumAvailable.getAndUpdate { minOf(it, current.availMem) }
                runCatching {
                    for (line in File("/proc/self/status").readLines()) {
                        val peak = when (line.substringBefore(':')) { "VmRSS" -> peakRss; "RssAnon" -> peakAnonymous; "RssFile" -> peakMapped; else -> null }
                        if (peak != null) {
                            val bytes = line.substringAfter(':').trim().substringBefore(' ').toLong() * 1024
                            peak.getAndUpdate { maxOf(it, bytes) }
                        }
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (reportId != null && now - lastLoadProgress >= 500) {
                    lastLoadProgress = now
                    val progress = JSONObject().put("phase", "load").put("run_id", reportId).put("pid", Process.myPid())
                        .put("elapsed_us", (SystemClock.elapsedRealtimeNanos() - loadStarted) / 1000)
                        .put("load_memory_watch", watchReport()).put("load_admission", admission)
                    runCatching {
                        val file = android.util.AtomicFile(File(filesDir, "service-progress-$reportId.json"))
                        val stream = file.startWrite()
                        try { stream.write(progress.toString().toByteArray()); file.finishWrite(stream) }
                        catch (failure: Throwable) { file.failWrite(stream); throw failure }
                    }
                }
                if ((current.lowMemory || current.availMem < pressureThreshold) && watchState.compareAndSet(0, 2)) {
                    NativeRuntime.cancel()
                    update("stopping", UiText.get(R.string.ui_stopping_load_not_enough_free_memory))
                } else if (watchState.get() == 0) main.postDelayed(this, 100)
            }
        }
        main.postDelayed(watch, if (candidate.has("_test_load_pressure_threshold_bytes")) 0 else 100)
        worker.execute {
            var wake: InferenceWakeLease? = null
            var opened: ModelStore.OpenModel? = null
            try {
                wake = awake()
                val backendStarted = SystemClock.elapsedRealtimeNanos()
                val backend = NativeRuntime.initialize(this, candidate.optString("backend", "cpu"))
                val backendInitializeUs = (SystemClock.elapsedRealtimeNanos() - backendStarted) / 1000
                opened = ModelStore.open(this, name)
                val verifyStarted=SystemClock.elapsedRealtimeNanos()
                main.post {if(op==operation && !closing)update("loading",UiText.get(R.string.ui_verifying_model_file_sha_256))}
                val sourceSha=ModelStore.verifyOpened(opened,entry.sha256) {NativeRuntime.ticket()!=ticket}
                val verifyUs=(SystemClock.elapsedRealtimeNanos()-verifyStarted)/1000
                if(NativeRuntime.ticket()!=ticket)throw NativeRuntime.Failure("cancelled",UiText.get(R.string.ui_download_cancelled))
                val result = NativeRuntime.load(opened.path, candidate, ticket)
                    .put("verified_source_sha256",sourceSha).put("source_verify_us",verifyUs)
                if (!watchState.compareAndSet(0, 1)) throw NativeRuntime.Failure("memory_admission", UiText.get(R.string.ui_memory_became_insufficient_during_loading))
                result.put("model_location", opened.location).put("model_file_bytes", opened.bytes)
                    .put("backend_initialize_us", backendInitializeUs + (gpuPlan?.optLong("first_backend_initialize_us") ?: 0))
                    .put("service_load_wall_us", (SystemClock.elapsedRealtimeNanos() - commandStarted) / 1000)
                // Runtime.load has now released the previous native mapping. An
                // extra unload here would invalidate the already queued ticket.
                modelSource?.close()
                modelSource = opened; opened = null
                val pss = Debug.getPss()
                main.post {
                    initialized = true
                    if (op == operation && !closing) {
                        loaded = true; busy = false
                        snapshot.put("backend", backend).put("load", result).put("loaded_pss_kib", pss).put("load_memory_watch", watchReport())
                        update("ready", UiText.get(R.string.ui_model_ready_0c37f0))
                    }
                }
            } catch (failure: Throwable) {
                // A JNI/config failure may happen before Runtime.load clears the previous model.
                try { NativeRuntime.unload() } catch (_: Throwable) {}
                opened?.close(); modelSource?.close(); modelSource = null
                main.post { if (op == operation && !closing) {
                    busy = false
                    val pressure = watchState.get() == 2
                    snapshot.put("error_kind", if (pressure) "memory_admission" else if (failure is NativeRuntime.Failure) failure.kind else "runtime")
                        .put("load_memory_watch", watchReport())
                    update("unloaded", if (pressure) UiText.get(R.string.ui_loading_stopped_not_enough_free_memory) else if (failure is NativeRuntime.Failure && failure.kind == "cancelled") UiText.get(R.string.ui_download_cancelled) else UiText.get(R.string.ui_loading_failed, (failure.message)))
                    saveDebugReport(data.getString("report_id")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,80}")) }, false)
                    releaseForeground()
                } }
            } finally { watchState.compareAndSet(0, 1); main.removeCallbacks(watch); releaseWake(wake) }
        }
        initialized = true // JNI is loaded by ticket(); cancel is safe before initialize/load.
    }

    private fun generate(data: Bundle) {
        if (busy || !loaded || closing) return
        val started = SystemClock.elapsedRealtimeNanos()
        val clientStarted = data.getLong("client_sent_ns", started)
        val productRequest = data.getString("product_request")
        val supplied = data.getString("request")
        val request = if (productRequest != null) JSONObject(productRequest) else if (supplied != null && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            require(supplied.toByteArray().size <= 65536) { "Fixture exceeds IPC budget" }
            JSONObject(supplied)
        } else {
            val prompt = data.getString("prompt") ?: ""
            require(prompt.isNotBlank() && prompt.toByteArray().size <= 32768) { UiText.get(R.string.ui_enter_a_prompt_up_to_32_kib) }
            val tokens = data.getInt("tokens", 128)
            require(tokens in 1..config.optInt("context", 4096)) { UiText.get(R.string.ui_response_reserve_must_fit_the_context) }
            val raw = data.getBoolean("raw", false)
            val built = JSONObject().put("chat", !raw).put("raw_prompt", if (raw) prompt else "")
            .put("max_tokens", tokens).put("temperature", 0.0).put("seed", 42).put("thinking", true)
            .put("trace", data.getBoolean("trace", false) && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", "You are a helpful assistant."))
                .put(JSONObject().put("role", "user").put("content", prompt)))
            built
        }
        require(request.getInt("max_tokens") in 1..config.optInt("context", 4096)) { UiText.get(R.string.ui_response_reserve_must_fit_the_context) }
        val apiCall = activeApi.takeIf { data.getBoolean("api_request", false) }
        val compactDone=if(data.getBoolean("compact_request"))compactNativeDone.also {compactNativeDone=null}else null
        val chatId = activeChat.takeIf { productRequest != null && compactDone==null }
        val runId = UUID.randomUUID().toString()
        val debugFile = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            (data.getBoolean("_benchmark_output", false) || data.containsKey("report_id"))
        val output = if (debugFile) File(filesDir, "output-$runId.bin") else null
        val memoryOutput = if (debugFile) null else EphemeralOutput(runId)
        liveOutput = memoryOutput
        snapshot.remove("result")
        snapshot.remove("error_kind")
        snapshot.remove("error_details")
        snapshot.remove("native_phase")
        snapshot.remove("progress")
        snapshot.remove("generation_memory_watch")
        snapshot.remove("output_file")
        if (output != null) snapshot.put("output_file", output.name)
        snapshot.put("chat_id", chatId ?: JSONObject.NULL)
        snapshot.put("output_id", runId).put("output_transport", if (debugFile) "debug_file" else "memory")
            .put("output_bytes", 0).put("request", request)
        busy = true
        promote()
        update("generating", UiText.get(R.string.ui_generating))
        val op = ++operation
        val ticket = NativeRuntime.ticket()
        val injectedThreshold = if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            data.containsKey("_test_generate_pressure_threshold_bytes")) data.getLong("_test_generate_pressure_threshold_bytes") else null
        val injectedThermal = if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 && data.containsKey("_test_thermal_status"))
            data.getInt("_test_thermal_status").also { require(it in 0..6) } else null
        val memoryWatch = InferenceMemoryWatch(this, main, injectedThreshold, injectedThermal) {
            if (op == operation && busy && !closing) NativeRuntime.cancel()
        }
        memoryWatch.start()
        val reportId = data.getString("report_id")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,80}")) }
        fun progress(details: JSONObject) {
            if (reportId == null || (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
            details.put("pid", Process.myPid()).put("run_id", reportId)
                .put("service_elapsed_us", (SystemClock.elapsedRealtimeNanos() - started) / 1000)
                .put("cpu_ms", Process.getElapsedCpuTime()).put("output_file", output?.name ?: JSONObject.NULL)
                .put("process_status", runCatching { File("/proc/self/status").readLines().filter {
                    it.startsWith("VmHWM:") || it.startsWith("VmRSS:") || it.startsWith("VmSwap:") || it.startsWith("Cpus_allowed_list:")
                }.joinToString("\n") }.getOrDefault("unavailable"))
                // Debug-only counters, sampled at existing chunk boundaries.
                // stat/io distinguish CPU work, page faults and storage traffic;
                // no smaps walk, per-node sync or new periodic inference polling.
                .put("process_stat", runCatching { File("/proc/self/stat").readText().trim() }.getOrDefault("unavailable"))
                .put("process_io", runCatching { File("/proc/self/io").readText().trim() }.getOrDefault("unavailable"))
                .put("system_memory", runCatching { File("/proc/meminfo").readLines().filter {
                    it.startsWith("MemAvailable:") || it.startsWith("SwapFree:") || it.startsWith("Cached:") || it.startsWith("Dirty:")
                }.joinToString("\n") }.getOrDefault("unavailable"))
            val file = android.util.AtomicFile(File(filesDir, "service-progress-$reportId.json"))
            try {
                val stream = file.startWrite()
                try { stream.write(details.toString().toByteArray()); file.finishWrite(stream) }
                catch (failure: Throwable) { file.failWrite(stream); throw failure }
            } catch (_: Exception) {} // Optional debug telemetry must not fail inference.
        }
        progress(JSONObject().put("phase", "generating"))
        worker.execute {
            var wake: InferenceWakeLease? = null
            var count = 0L
            var firstPieceAt = 0L
            try {
                wake = awake()
                val target: java.io.OutputStream = if (output != null) BufferedOutputStream(output.outputStream())
                    else object : java.io.OutputStream() {
                        override fun write(value: Int) { memoryOutput!!.append(byteArrayOf(value.toByte())) }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            memoryOutput!!.append(if (offset == 0 && length == bytes.size) bytes else bytes.copyOfRange(offset, offset + length))
                        }
                    }
                val result = target.use { stream ->
                    var lastUpdate = 0L
                    NativeRuntime.generate(request, ticket, object : NativeRuntime.Sink {
                        override fun onPhase(phase: String) {
                            progress(JSONObject().put("phase", phase))
                            main.post { if (op == operation && !closing) { snapshot.put("native_phase", phase); publish() } }
                        }
                        override fun onChat(bytes: ByteArray) {
                            if (apiCall != null) {
                                if (apiCall.cancelled.get()) { NativeRuntime.cancel(); return }
                                apiCall.update(JSONObject(String(bytes, Charsets.UTF_8)))
                            }
                            if (chatId == null) return
                            val message = JSONObject(String(bytes, Charsets.UTF_8))
                            // Synchronize a bounded immutable message on the service main thread.
                            val completed = java.util.concurrent.CountDownLatch(1)
                            var failure: Throwable? = null
                            main.post { try { if (op == operation && !closing) chats.output(chatId, message) }
                                catch (error: Throwable) { failure = error } finally { completed.countDown() } }
                            check(completed.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "UI state update timed out" }
                            failure?.let { throw it }
                        }
                        override fun onProgress(bytes: ByteArray) {
                            val details=JSONObject(String(bytes, Charsets.UTF_8))
                            progress(details)
                            val publicProgress=JSONObject().put("phase",details.optString("phase")).put("completed",details.optInt("completed"))
                                .put("total",details.optInt("total")).put("elapsed_us",details.optLong("elapsed_us"))
                            main.post {if(op==operation && !closing) {snapshot.put("progress",publicProgress);publish()}}
                        }
                        override fun onBytes(bytes: ByteArray) {
                            if (firstPieceAt == 0L && bytes.isNotEmpty()) firstPieceAt = SystemClock.elapsedRealtimeNanos()
                            stream.write(bytes); count += bytes.size
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUpdate >= 100) {
                                stream.flush(); lastUpdate = now
                                val available = count
                                main.post { if (op == operation && !closing) { snapshot.put("output_bytes", available); publish() } }
                            }
                        }
                    })
                }
                memoryWatch.finish()
                if (memoryWatch.aborted) throw IllegalStateException("System resource pressure")
                val completedAt = SystemClock.elapsedRealtimeNanos()
                val pss = Debug.getPss()
                main.post { if (op == operation && !closing) {
                    busy = false
                    if(compactDone!=null){compactDone(Result.success(result));return@post}
                    if (apiCall != null) {
                        if (apiCall.cancelled.get()) apiCall.fail(ApiError(409, "cancelled", "Request cancelled"))
                        else { apiCall.result = result; apiCall.done.countDown() }
                        if (activeApi === apiCall) activeApi = null
                    }
                    if (chatId != null) { chats.finish(chatId, result.optJSONObject("assistant"), result.optString("finish_reason"), runId); activeChat = null }
                    val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
                    snapshot.put("generation_memory_watch", memoryWatch.report()).put("service_compute_wall_us", (completedAt - started) / 1000)
                        .put("client_to_first_piece_us", if (firstPieceAt > 0 && clientStarted in 1..started) (firstPieceAt - clientStarted) / 1000 else JSONObject.NULL)
                        .put("completed_importance", importance)
                        .put("completed_interactive", getSystemService(PowerManager::class.java).isInteractive)
                        .put("completed_pss_kib", pss)
                        .put("process_status", runCatching { File("/proc/self/status").readLines().filter { it.startsWith("VmHWM:") || it.startsWith("VmRSS:") || it.startsWith("VmSwap:") || it.startsWith("Cpus_allowed_list:") }.joinToString("\n") }.getOrNull() ?: JSONObject.NULL)
                        .put("thermal_status", if (Build.VERSION.SDK_INT >= 29) getSystemService(PowerManager::class.java).currentThermalStatus else JSONObject.NULL)
                        .put("result", result).put("output_bytes", count)
                    update("ready", if (result.optString("finish_reason") == "length") UiText.get(R.string.ui_response_limit_reached) else UiText.get(R.string.ui_response_completed))
                    saveDebugReport(reportId, result.optInt("generated_tokens") > 0)
                    if (memoryOutput != null && data.getBoolean("notify_on_completion", true) &&
                        result.optInt("generated_tokens") > 0) {
                        main.postDelayed({
                            if (op == operation && !closing && memoryOutput === liveOutput && visibleClients.isEmpty() && (chatId==null || chats.findAnswer(runId)==chatId))
                                AnswerNotifications.show(this, runId, chatId != null)
                        }, 500)
                    }
                } }
            } catch (failure: Throwable) {
                memoryWatch.finish()
                main.post { if (op == operation && !closing) {
                    busy = false
                    if(compactDone!=null){compactDone(Result.failure(failure));return@post}
                    if (apiCall != null) {
                        val kind = if (memoryWatch.thermal) "thermal_pressure" else if (memoryWatch.pressure) "memory_pressure" else if (failure is NativeRuntime.Failure) failure.kind else "runtime"
                        apiCall.fail(ApiError(if (kind == "cancelled") 409 else 500, kind, "Generation failed: $kind")); if (activeApi === apiCall) activeApi = null
                    }
                    if (chatId != null) { chats.finish(chatId, null, if (memoryWatch.thermal) "thermal_pressure" else if (memoryWatch.pressure) "memory_pressure" else "interrupted", runId); activeChat = null }
                    snapshot.put("generation_memory_watch", memoryWatch.report())
                        .put("output_bytes", count).put("error_kind", if (memoryWatch.thermal) "thermal_pressure" else if (memoryWatch.pressure) "memory_pressure" else if (failure is NativeRuntime.Failure) failure.kind else "runtime")
                    if (failure is NativeRuntime.Failure && failure.kind == "context_overflow")
                        snapshot.put("error_details", failure.details)
                    update("ready", if (memoryWatch.thermal) UiText.get(R.string.ui_request_stopped_phone_overheated_let_it_cool)
                        else if (memoryWatch.pressure) UiText.get(R.string.ui_request_stopped_not_enough_free_memory_context)
                        else if (failure is NativeRuntime.Failure && failure.kind == "cancelled") UiText.get(R.string.ui_generation_stopped) else UiText.get(R.string.ui_error_47c833, (failure.message)))
                    saveDebugReport(reportId, false)
                } }
            } finally { memoryWatch.finish(); releaseWake(wake) }
        }
    }

    private fun saveDebugReport(id: String?, passed: Boolean) {
        if (id == null || (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val report = JSONObject(snapshot.toString()).put("run_id", id).put("passed", passed)
        try {
            val file = android.util.AtomicFile(File(filesDir, "service-result-$id.json"))
            val stream = file.startWrite()
            try { stream.write(report.toString(2).toByteArray()); file.finishWrite(stream) }
            catch (failure: Throwable) { file.failWrite(stream); throw failure }
        } catch (_: Exception) { update(snapshot.optString("state"), UiText.get(R.string.ui_could_not_save_test_report)) }
    }

    private fun stop(unload: Boolean) {
        if (closing) return
        compactWork?.let {it.cancelled=true;if(unload && activeRemote==null)finishCompaction(it,Result.failure(IllegalStateException(UiText.get(R.string.ui_compaction_stopped))))}
        activeRemote?.let {it.cancel();unloadAfterRemote=unload;update(snapshot.optString("state"),UiText.get(R.string.ui_stopping_api_request));return}
        if (apiStarting) { apiStarting = false; busy = false; ++operation; update(if (loaded) "ready" else "unloaded", UiText.get(R.string.ui_api_startup_cancelled)) }
        activeApi?.cancelled?.set(true)
        NativeRuntime.cancel() // Also cancel a first load before initialized becomes true.
        if (planning) planningStopped = true
        if (!unload) {
            if (busy) update("stopping", UiText.get(R.string.ui_stopping))
            return
        }
        api?.close();api=null;apiSession=""
        activeApi?.fail(ApiError(409, "cancelled", "Model unloaded")); activeApi = null
        activeChat?.let { chats.finish(it, null, "interrupted", snapshot.optString("output_id")) }; activeChat = null
        closing = true
        ++operation
        update("unloading", UiText.get(R.string.ui_unloading_model))
        worker.execute {
            try { if (initialized) NativeRuntime.unload() }
            finally { modelSource?.close(); modelSource = null; main.post {
                busy = false; planning = false; loaded = false; closing = false; model = ""
                update("unloaded", UiText.get(R.string.ui_model_unloaded))
                releaseForeground()
            } }
        }
    }

    private fun releaseForeground() {
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        stopSelf()
    }

    override fun onDestroy() {
        HistoryOwner.observe(null)
        compactWork?.let {it.cancelled=true;runCatching {chats.compactEnd(it.chatId,null,UiText.get(R.string.ui_compaction_interrupted_original_history_kept),it.automatic)};runCatching {it.reply?.invoke(JSONObject().put("ok",false).put("message",UiText.get(R.string.ui_compaction_interrupted)))}};compactWork=null;compactNativeDone=null
        activeChat?.let {runCatching {chats.finish(it,null,"interrupted",snapshot.optString("output_id"))}};activeChat=null
        snapshot.optString("remote_chat").takeIf {it.isNotEmpty()}?.let {runCatching {chats.finish(it,null,"interrupted",UUID.randomUUID().toString())}}
        activeRemote?.cancel();activeRemote=null;catalog?.cancel();catalog=null;providerControl.shutdownNow()
        api?.close();api=null;apiSession=""
        activeApi?.fail(ApiError(503, "service_destroyed", "Service destroyed")); activeApi = null
        synchronized(wakeGuard) { closing = true; activeWake?.close(); activeWake = null }
        if(initialized)NativeRuntime.cancel()
        worker.execute { try { if (initialized) NativeRuntime.unload() } finally { modelSource?.close(); modelSource = null } }
        worker.shutdown()
        clients.clear(); visibleClients.clear()
        super.onDestroy()
    }
}
