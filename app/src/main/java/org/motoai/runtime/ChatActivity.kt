package org.motoai.runtime

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.*
import android.text.*
import android.text.style.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Messenger UI. Backend owns RAM/opt-in encrypted history; text never enters savedInstanceState. */
class ChatActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("preferences", MODE_PRIVATE) }
    private var service: Messenger? = null
    private var bound = false
    private var visible = false
    private var chatId = ""
    private var model = ""
    private var modelCaption = ""
    private var runtimeState = "unloaded"
    private var lastSnapshot = JSONObject()
    private var current = JSONObject()
    private val callbacks = mutableMapOf<String, (JSONObject) -> Unit>()
    private var fetching = false
    private var pendingChatRefresh = false
    private var counting = false
    private var submitting = false
    private var pendingAnswer: String? = null
    private var draftChanging = false
    private var draftSaving = false
    private var lastRender = ""
    private var apiDraft:JSONObject?=null
    private var lastDraft = ""
    private data class ComposerState(val chatId:String,val text:String,val screen:String="dialogs",val tab:Int=0,val folder:String="",val search:String="",val apiDraft:String?=null,val files:String?=null,val export:String?=null)
    private var composerFiles=JSONObject()
    private fun pendingFiles()=composerFiles.optJSONArray(chatId)?:JSONArray()
    private lateinit var fileChips:LinearLayout
    private lateinit var attachButton:IconButton
    private var attachmentLoading=false
    private var attachmentKind="text"
    private var attachmentChat=""
    private var attachmentModel=""
    private var exportFile:JSONObject?=null
    private var restoreDraft = true
    private var fullChatNeeded = true
    private var flushRetainedDraft = false
    private var retainedComposer:ComposerState? = null
    private lateinit var sourceButton:Button
    private fun remote()=current.optJSONObject("remote")
    private lateinit var mainContent:LinearLayout
    private lateinit var shell:FrameLayout
    private lateinit var pages:SettingsPages
    private lateinit var home:MessengerHome
    private lateinit var timeline:ChatTimeline
    private lateinit var chatName:TextView
    private lateinit var chatSubtitle:TextView
    private lateinit var avatarBox:FrameLayout
    private lateinit var compactBar:TextView
    private lateinit var pinBar:LinearLayout
    private lateinit var pinText:TextView
    private lateinit var chatSearchRow:LinearLayout
    private lateinit var chatSearch:EditText
    private lateinit var searchCount:TextView
    private var searchMatches=emptyList<Int>();private var searchCursor=0;private var pinCursor=0
    private var style=MessengerStyle(false)
    private var screen="dialogs";private var currentTab=0;private var navigationRequest=0L
    private var catalogRevision=-1L;private var catalogFetching=false;private var catalogItems=JSONArray();private var historyConfig=JSONObject()
    private var navigationBack:android.window.OnBackInvokedCallback?=null
    private var avatarSignature="";private var apiSignature=""
    private fun composeBusy()=lastSnapshot.optBoolean("remote_busy") || runtimeState in listOf("generating","loading","stopping","preparing","unloading","starting_api")
    private fun canSend()=service!=null && !submitting && !composeBusy() && if(remote()!=null)runtimeState in listOf("ready","unloaded") else runtimeState=="ready" && current.optString("model")==model && model.isNotEmpty()
    private fun syncSource() {
        val r=remote();val selected=current.optString("model")
        val needsLoad=r==null && selected.isNotEmpty() && (model!=selected || runtimeState in listOf("unloaded","loading","unloading"))
        sourceButton.text=when {needsLoad->if(composeBusy())UiText.get(R.string.ui_wait_for_the_operation_to_finish)else UiText.get(R.string.ui_load_model, (modelLabel(selected)));r!=null->UiText.get(R.string.ui_api, (r.optString("name")), (r.optString("model")));selected.isNotEmpty()->UiText.get(R.string.ui_on_device, (modelLabel(selected)));else->UiText.get(R.string.ui_choose_model)}
        if(r==null && selected.isEmpty())status.text=if(composeBusy())UiText.get(R.string.ui_a_request_is_running_in_another_chat)else UiText.get(R.string.ui_no_model_selected_yet)
        if(r!=null)status.text=if(current.optBoolean("compacting"))lastSnapshot.optString("message",UiText.get(R.string.ui_compacting_context))else if(current.optBoolean("active"))UiText.get(R.string.ui_api_request)else current.optString("remote_error").takeIf {it.isNotBlank() && it!="null"}?:UiText.get(R.string.ui_api_31d58b)+if(current.optBoolean("permanent"))UiText.get(R.string.ui_permanent_chat)else UiText.get(R.string.ui_temporary_chat)
        if(needsLoad && !composeBusy())status.text=UiText.get(R.string.ui_load_this_chat_s_local_model_to)
        status.visibility=if(needsLoad || selected.isEmpty() || composeBusy() || current.optString("save_error").isNotEmpty() || current.optString("remote_error").let {it.isNotEmpty() && it!="null"})View.VISIBLE else View.GONE
        sourceButton.visibility=if(selected.isEmpty() || needsLoad)View.VISIBLE else View.GONE
        sourceButton.isEnabled=service!=null && !composeBusy() && !submitting
        sourceButton.contentDescription=if(needsLoad)UiText.get(R.string.ui_load_chat_model)else UiText.get(R.string.ui_choose_chat_model)
        syncCompaction()
        syncChatHeader()
        val busy=composeBusy();val ownBusy=busy && current.optBoolean("active");sendButton.text=if(ownBusy)"■"else "↑";sendButton.contentDescription=if(ownBusy)UiText.get(R.string.ui_stop)else UiText.get(R.string.ui_send)
        sendButton.isEnabled=ownBusy || canSend() && !attachmentLoading && (input.text.isNotBlank() || pendingFiles().length()>0);sendButton.alpha=if(sendButton.isEnabled)1f else .4f
        if(::attachButton.isInitialized){attachButton.isEnabled=service!=null && !composeBusy() && !submitting && !attachmentLoading && current.optString("model").isNotEmpty();attachButton.alpha=if(attachButton.isEnabled)1f else .4f}
    }
    private fun providerUi()=ProviderUi(this,style,pages,{request,callback->product(request,callback)})
    private fun showSource()=showActiveModels()
    private fun showActiveModels() {
        saveDraft.run()
        if(chatId.isEmpty()){createDialog();return}
        if(current.optJSONArray("messages")?.length()!=0) {
            AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_choose_a_different_model)).setMessage(UiText.get(R.string.ui_a_new_empty_chat_will_open_this))
                .setPositiveButton(UiText.get(R.string.ui_new_chat)){_,_->createDialog()}.setNegativeButton(UiText.get(R.string.ui_keep),null).show();return
        }
        product(JSONObject().put("action","providers")){r->checked(r){
            data class Choice(val label:String,val local:ModelStore.Entry?=null,val provider:String="",val model:String="")
            val choices=mutableListOf<Choice>()
            ModelStore.entries(this).filter {prefs.getBoolean("active:${it.id}",true)}.forEach {choices.add(Choice(UiText.get(R.string.ui_on_device, (it.label)),it))}
            val a=r.getJSONArray("providers");for(i in 0 until a.length()){val v=a.getJSONObject(i);val models=v.optJSONArray("enabled_models")?:JSONArray();for(j in 0 until models.length())choices.add(Choice("${ProviderStore.displayName(v)} · ${models.getString(j)}",provider=v.getString("id"),model=models.getString(j)))}
            if(choices.isEmpty()){AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_no_active_models)).setMessage(UiText.get(R.string.ui_add_a_gguf_model_or_an_api)).setPositiveButton(UiText.get(R.string.ui_settings)){_,_->showSettings()}.setNegativeButton(UiText.get(R.string.ui_back),null).show();return@checked}
            AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_active_models)).setMenuItems(choices.map {it.label}.toTypedArray()){_,n->
                val choice=choices[n];val entry=choice.local
                val request=JSONObject().put("chat_id",chatId)
                if(entry!=null)request.put("action","chat_model").put("model",entry.id)else request.put("action","chat_remote").put("provider_id",choice.provider).put("model",choice.model)
                product(request){answer->checked(answer){selectChat(answer.getJSONObject("chat"));if(entry!=null && (model!=entry.id || runtimeState!="ready"))configure(entry)}}
            }.setNeutralButton(UiText.get(R.string.ui_settings)){_,_->showSettings()}.setNegativeButton(UiText.get(R.string.ui_back),null).show()
        }}
    }
    private fun showEnabledLocalModels() {
        val entries=ModelStore.entries(this);val box=panel();box.addView(text(UiText.get(R.string.ui_active_models_appear_when_you_create_a),14f))
        for(entry in entries)box.addView(Switch(this).apply {text=entry.label;setTextColor(foregroundColor);minimumHeight=dp(64);isChecked=prefs.getBoolean("active:${entry.id}",true);setOnCheckedChangeListener {_,on->prefs.edit().putBoolean("active:${entry.id}",on).apply()}})
        box.addView(button(UiText.get(R.string.ui_add_gguf_hf)){showModels()});pages.open(UiText.get(R.string.ui_active_local_models),ScrollView(this).apply {addView(box)})
    }
    private fun updateBackCallback() {
        if(Build.VERSION.SDK_INT<33 || !::home.isInitialized)return
        val handles=(::pages.isInitialized && pages.showing) || screen=="chat" || currentTab!=0 || home.searching
        if(handles && navigationBack==null){navigationBack=android.window.OnBackInvokedCallback {navigateBack()};onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,navigationBack!!)}
        if(!handles)navigationBack?.let {onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it);navigationBack=null}
    }
    private fun navigateBack() {
        if(::pages.isInitialized && pages.back())return
        if(screen=="chat" && chatSearchRow.visibility==View.VISIBLE){closeChatSearch();return}
        if(screen=="chat"){saveDraft.run();showTab(0);return}
        if(currentTab!=0){showTab(0);return}
        if(home.searching){home.closeSearch();updateBackCallback()}
    }
    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean {if(Build.VERSION.SDK_INT<33 && keyCode==KeyEvent.KEYCODE_BACK && ((::pages.isInitialized && pages.showing) || screen=="chat" || currentTab!=0 || home.searching)){navigateBack();return true};return super.onKeyUp(keyCode,event)}
    private fun updateSystemBars() {
        if(Build.VERSION.SDK_INT>=30){val mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;window.decorView.windowInsetsController?.setSystemBarsAppearance(if(darkMode)0 else mask,mask)}
        else {val mask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;window.decorView.systemUiVisibility=(window.decorView.systemUiVisibility and mask.inv()) or if(darkMode)0 else mask}
    }
    override fun onWindowFocusChanged(hasFocus:Boolean) {super.onWindowFocusChanged(hasFocus);if(hasFocus && ::mainContent.isInitialized)updateSystemBars()}
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var budget: TextView
    private var foregroundColor = 0
    private var mutedColor = 0
    private var cardColor = 0
    private var accent = 0
    private var darkMode = false
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        val data = message.data
        when {
            data.containsKey("product_id") -> {
                val id = data.getString("product_id")!!
                val fd = data.getParcelable<ParcelFileDescriptor>("payload")
                if (fd != null) ProductPipe.read(fd, 34 * 1024 * 1024) { result -> main.post {
                    val answer = runCatching { JSONObject(String(result.getOrThrow(), Charsets.UTF_8)) }
                        .getOrElse { JSONObject().put("ok", false).put("message", UiText.get(R.string.ui_could_not_retrieve_chat)) }
                    callbacks.remove(id)?.invoke(answer)
                } }
            }
            data.containsKey("snapshot") -> {
                lastSnapshot = JSONObject(data.getString("snapshot")!!)
                runtimeState = lastSnapshot.optString("state")
                val nextModel=lastSnapshot.optString("model")
                if(nextModel!=model)modelCaption=modelLabel(nextModel)
                model = nextModel
                val progress=lastSnapshot.optJSONObject("progress")
                val messageText=if(runtimeState=="generating" && progress!=null) when(progress.optString("phase")) {
                    "prefill" -> UiText.get(R.string.ui_processing_input_tokens, (progress.optInt("completed")), (progress.optInt("total")))
                    "decode" -> UiText.get(R.string.ui_response_reserved_tokens, (progress.optInt("completed")), (progress.optInt("total")))
                    else -> lastSnapshot.optString("message")
                } else lastSnapshot.optString("message")
                if(screen=="chat")status.text = messageText + if (model.isNotEmpty()) "\n$modelCaption" else ""
                
                if(visible && runtimeState=="unloaded" && !deletionDialog)ModelStore.pendingDeletionSource()?.let {source->main.post {offerDeleteSource(source)}}
                if(screen=="chat")syncSource()
                if(pendingAnswer!=null)openPendingAnswer()else if(screen=="chat")refreshChat()else {refreshCatalog();if(currentTab==1)refreshApiIfChanged()}
            }
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder); register(); exportCertificate()
            if(pendingAnswer!=null)openPendingAnswer()else if(screen=="chat" && chatId.isNotEmpty())refreshChat()else {refreshCatalog();if(currentTab!=0)showTab(currentTab)}
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service=null;submitting=false;fetching=false;pendingChatRefresh=false;counting=false;catalogFetching=false;draftSaving=false;runtimeState="unloaded"
            callbacks.clear();catalogRevision=-1;fullChatNeeded=true;main.removeCallbacks(saveDraft)
            if(current.optBoolean("permanent")) {
                // Keep the live composer through an inference-process restart. Never replay a request.
                restoreDraft=false;flushRetainedDraft=true
            }else {
                chatId="";current=JSONObject();restoreDraft=true;flushRetainedDraft=false
                draftChanging=true;input.setText("");draftChanging=false;lastDraft="";showTab(0)
            }
            status.text=UiText.get(R.string.ui_connection_to_the_model_process_lost_the);sendButton.isEnabled=false
        }
        override fun onBindingDied(name:ComponentName) {
            onServiceDisconnected(name)
            if(bound){unbindService(this);bound=false}
            if(!isDestroyed)main.post {if(!isDestroyed)bindRuntime()}
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 16f) = TextView(this).apply {
        this.text = value; textSize = size; setTextColor(foregroundColor)
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }
    private fun rounded(color: Int, radius: Int = 14) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        this.text = value; textSize = 14f; isAllCaps = false; minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        setPadding(dp(8), dp(6), dp(8), dp(6)); setTextColor(foregroundColor)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22009988), rounded(cardColor), null)
        stateListAnimator = null
        setOnClickListener { safely(action) }
    }
    private fun welcome() {
        if(!::timeline.isInitialized)return
        val chosen=current.optString("model").isNotEmpty()
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(28),dp(12),dp(28),dp(30))}
        box.addView(AiAvatar(this,if(chosen)sourceName()else "AI",style),LinearLayout.LayoutParams(dp(72),dp(72)).apply {bottomMargin=dp(20)})
        box.addView(text(if(chosen)UiText.get(R.string.ui_start_a_conversation)else UiText.get(R.string.ui_choose_a_model),24f).apply {typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);gravity=Gravity.CENTER})
        box.addView(text(if(chosen)UiText.get(R.string.ui_write_a_message_below)else UiText.get(R.string.ui_on_device_or_via_api_your_choice),15f).apply {setTextColor(mutedColor);gravity=Gravity.CENTER})
        if(!chosen)box.addView(button(UiText.get(R.string.ui_choose_model_4c36d5)){showActiveModels()})
        else if(remote()==null && (model!=current.optString("model") || runtimeState!="ready"))ModelStore.entries(this).firstOrNull {it.id==current.optString("model")}?.let {entry->box.addView(button(UiText.get(R.string.ui_load_model_28b19c)){configure(entry)})}
        box.addView(text(if(current.optBoolean("permanent"))UiText.get(R.string.ui_permanent_encrypted_storage)else UiText.get(R.string.ui_temporary_no_saved_history),12f).apply {setTextColor(mutedColor);gravity=Gravity.CENTER})
        timeline.empty(box)
    }
    private fun safely(action: () -> Unit) { try { action() } catch (e: Exception) { notice(e.message ?: UiText.get(R.string.ui_error)) } }
    private fun notice(message: String) { if (!isFinishing) AlertDialog.Builder(this).setMessage(message).setPositiveButton(UiText.get(R.string.ui_ok), null).show() }
    private fun modelLabel(id: String) = ModelStore.entries(this).firstOrNull { it.id == id }?.label?.substringBefore(" (") ?: id
    override fun onCreate(savedInstanceState: Bundle?) {
        val mode = prefs.getInt("theme", 0)
        val dark = mode == 2 || (mode == 0 && resources.configuration.uiMode and 0x30 == 0x20)
        setTheme(if (dark) R.style.LlamurDark else R.style.LlamurLight)
        super.onCreate(savedInstanceState)
        retainedComposer = lastNonConfigurationInstance as? ComposerState
        composerFiles=retainedComposer?.files?.let {JSONObject(it)}?:JSONObject();exportFile=retainedComposer?.export?.let {JSONObject(it)}
        attachmentKind=savedInstanceState?.getString("attachment_kind")?:"text";attachmentChat=savedInstanceState?.getString("attachment_chat")?:"";attachmentModel=savedInstanceState?.getString("attachment_model")?:""
        restoreDraft = retainedComposer == null
        flushRetainedDraft = retainedComposer != null
        chatId = retainedComposer?.chatId ?: savedInstanceState?.getString("chat_id") ?: ""
        apiDraft=retainedComposer?.apiDraft?.let {JSONObject(it)}
        screen=retainedComposer?.screen?:savedInstanceState?.getString("screen")?:"dialogs";currentTab=retainedComposer?.tab?:savedInstanceState?.getInt("tab",0)?:0
        darkMode=dark;style=MessengerStyle(dark);accent=style.accent;foregroundColor=style.text;mutedColor=style.muted;cardColor=style.surface
        window.statusBarColor=style.background;window.navigationBarColor=style.background;window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root=LinearLayout(this).also {mainContent=it}.apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(style.background)}
        val header=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(6),dp(4),dp(6),dp(4))}
        header.addView(IconButton(this,AppIcon.BACK,UiText.get(R.string.ui_back_to_chats),style).apply {setOnClickListener {closeChatSearch();showTab(0)}},LinearLayout.LayoutParams(dp(44),dp(48)))
        avatarBox=FrameLayout(this).apply {contentDescription=UiText.get(R.string.ui_about_the_model);isClickable=true;isFocusable=true;setOnClickListener {showModelInfo()}};header.addView(avatarBox,LinearLayout.LayoutParams(dp(44),dp(44)).apply {marginEnd=dp(10)})
        val titles=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;setOnClickListener {showModelInfo()};contentDescription=UiText.get(R.string.ui_about_the_model)}
        chatName=text(UiText.get(R.string.ui_ai),16.5f).apply {typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);maxLines=1;ellipsize=TextUtils.TruncateAt.END;setPadding(0,0,0,dp(2))};chatSubtitle=text("",12f).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END;setTextColor(mutedColor);setPadding(0,0,0,0)};titles.addView(chatName);titles.addView(chatSubtitle);header.addView(titles,LinearLayout.LayoutParams(0,dp(54),1f))
        header.addView(IconButton(this,AppIcon.SEARCH,UiText.get(R.string.ui_search_in_chat),style).apply {setOnClickListener {showChatSearch()}},LinearLayout.LayoutParams(dp(42),dp(48)))
        header.addView(IconButton(this,AppIcon.MORE,UiText.get(R.string.ui_chat_actions),style).apply {setOnClickListener {if(chatId.isNotEmpty())showChatMenu(chatId)}},LinearLayout.LayoutParams(dp(42),dp(48)));root.addView(header)
        sourceButton=button(UiText.get(R.string.ui_choose_model_4c36d5)){val selected=current.optString("model");if(remote()==null && selected.isNotEmpty()){val entry=ModelStore.entries(this).firstOrNull {it.id==selected};if(entry!=null)configure(entry)else showModels()}else showSource()}.apply {contentDescription=UiText.get(R.string.ui_choose_chat_model)};root.addView(sourceButton)
        compactBar=text("",13f).apply {setTextColor(accent);setPadding(dp(16),dp(12),dp(16),dp(12));minimumHeight=dp(48);background=rounded(style.surface,14);visibility=View.GONE;setOnClickListener {showCompaction(chatId)}}
        root.addView(compactBar,LinearLayout.LayoutParams(-1,-2).apply {setMargins(dp(10),dp(2),dp(10),dp(3))})
        pinBar=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(3),dp(4),dp(3));background=rounded(style.surface,14);visibility=View.GONE}
        pinBar.addView(IconButton(this,AppIcon.PIN,UiText.get(R.string.ui_go_to_pinned_message),style,ink=accent).apply {setOnClickListener {jumpPin()}},LinearLayout.LayoutParams(dp(36),dp(40)))
        pinText=text("",13f).apply {maxLines=2;ellipsize=TextUtils.TruncateAt.END;setPadding(dp(6),0,0,0);setOnClickListener {jumpPin()}};pinBar.addView(pinText,LinearLayout.LayoutParams(0,-2,1f));pinBar.addView(IconButton(this,AppIcon.CLOSE,UiText.get(R.string.ui_unpin_message),style,ink=mutedColor).apply {setOnClickListener {unpinCurrent()}},LinearLayout.LayoutParams(dp(40),dp(40)));root.addView(pinBar,LinearLayout.LayoutParams(-1,-2).apply {setMargins(dp(10),dp(2),dp(10),dp(3))})
        chatSearchRow=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(6),0);visibility=View.GONE}
        chatSearch=EditText(this).apply {hint=UiText.get(R.string.ui_search_in_chat);textSize=15f;setTextColor(foregroundColor);setHintTextColor(mutedColor);isSingleLine=true;isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS;imeOptions=EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING};chatSearchRow.addView(chatSearch,LinearLayout.LayoutParams(0,dp(48),1f));searchCount=text("",11f);chatSearchRow.addView(searchCount)
        for(direction in listOf(-1,1))chatSearchRow.addView(IconButton(this,AppIcon.BACK,if(direction<0)UiText.get(R.string.ui_previous_match)else UiText.get(R.string.ui_next_match),style,ink=mutedColor).apply {rotation=if(direction<0)90f else 270f;setOnClickListener {stepSearch(direction)}},LinearLayout.LayoutParams(dp(36),dp(40)));chatSearchRow.addView(IconButton(this,AppIcon.CLOSE,UiText.get(R.string.ui_close_chat_search),style,ink=mutedColor).apply {setOnClickListener {closeChatSearch()}},LinearLayout.LayoutParams(dp(36),dp(40)));root.addView(chatSearchRow)
        timeline=ChatTimeline(this,style,{styled(it)},{index,anchor->messageMenu(index,anchor)},{saveFile(it)});root.addView(timeline,LinearLayout.LayoutParams(-1,0,1f))
        status=text("",12f).apply {setTextColor(mutedColor);maxLines=2;setPadding(dp(14),dp(2),dp(14),dp(2));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};root.addView(status)
        budget=text("",11f).apply {setTextColor(mutedColor);setPadding(dp(14),0,dp(14),dp(4))};root.addView(budget)
        input=object:EditText(this){override fun onCreateInputConnection(attributes:EditorInfo):android.view.inputmethod.InputConnection?{val connection=super.onCreateInputConnection(attributes);attributes.imeOptions=(attributes.imeOptions and EditorInfo.IME_MASK_ACTION.inv() and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()) or EditorInfo.IME_ACTION_SEND;return connection}}.apply {
            background=rounded(cardColor,26);setPadding(dp(16),dp(12),dp(16),dp(12));hint=UiText.get(R.string.ui_message);textSize=16f;setTextColor(foregroundColor);setHintTextColor(mutedColor);minLines=1;maxLines=5
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions=EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        };retainedComposer?.let {input.setText(it.text)}
        fileChips=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(12),0);visibility=View.GONE};root.addView(fileChips)
        val composer=LinearLayout(this).apply {gravity=Gravity.BOTTOM;setPadding(dp(6),dp(4),dp(10),dp(8))}
        attachButton=IconButton(this,AppIcon.PLUS,UiText.get(R.string.ui_attach_file),style,ink=mutedColor).apply {setOnClickListener {chooseAttachment()}};composer.addView(attachButton,LinearLayout.LayoutParams(dp(44),dp(50)))
        composer.addView(input,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=dp(8)})
        sendButton=IconButton(this,AppIcon.SEND,UiText.get(R.string.ui_send),style,0xff249fe6.toInt(),android.graphics.Color.WHITE).apply {setOnClickListener {if(composeBusy() && current.optBoolean("active"))command(RuntimeService.STOP)else submit()};isEnabled=false;alpha=.4f};stopButton=sendButton;composer.addView(sendButton,LinearLayout.LayoutParams(dp(50),dp(50)));root.addView(composer)
        input.setOnEditorActionListener {_,action,event->if(event?.isShiftPressed==true)false else if(action==EditorInfo.IME_ACTION_SEND || event?.keyCode==KeyEvent.KEYCODE_ENTER && !event.isShiftPressed){if(event==null || event.action==KeyEvent.ACTION_DOWN)submit();true}else false}
        input.setOnKeyListener {_,key,event->if(key==KeyEvent.KEYCODE_ENTER && !event.isShiftPressed){if(event.action==KeyEvent.ACTION_DOWN)submit();true}else false}
        chatSearch.addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){searchMatches=timeline.search(s.toString());searchCursor=0;stepSearch(0)};override fun afterTextChanged(s:Editable?) {}})
        home=MessengerHome(this,style,{modelLabel(it)},{openChat(it)},{createDialog()},{showTab(it)},{folderActions(it)},{showChatMenu(it)},{if(::home.isInitialized)updateBackCallback()})
        home.restore(retainedComposer?.folder?:savedInstanceState?.getString("folder")?:"",retainedComposer?.search?:"")
        shell=FrameLayout(this).apply {setBackgroundColor(style.background)};shell.addView(home,FrameLayout.LayoutParams(-1,-1));shell.addView(root,FrameLayout.LayoutParams(-1,-1))
        pages=SettingsPages(this,shell,style){updateBackCallback()}
        shell.setOnApplyWindowInsetsListener {v,insets->if(Build.VERSION.SDK_INT>=30){val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom)};insets};setContentView(shell)
        // A theme/locale relaunch may retain the old appearance until the new decor is attached.
        window.decorView.post {updateSystemBars()}
        mainContent.visibility=if(screen=="chat")View.VISIBLE else View.GONE;home.visibility=if(screen=="chat")View.GONE else View.VISIBLE;updateBackCallback()
        if(Build.VERSION.SDK_INT>=33)setRecentsScreenshotEnabled(false) else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        welcome()
        pendingAnswer = intent.getStringExtra(AnswerNotifications.EXTRA)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!draftChanging) { syncSource();main.removeCallbacks(saveDraft); main.postDelayed(saveDraft, 500) } }
        })
        bindRuntime()
    }
    private fun bindRuntime() { if (!bound) bound = bindService(Intent(this, RuntimeService::class.java), connection, BIND_AUTO_CREATE) }
    private val saveDraft:Runnable = Runnable {
        if((restoreDraft || fullChatNeeded) && chatId.isNotEmpty())return@Runnable
        if (!draftSaving && chatId.isNotEmpty() && service != null && input.text.toString() != lastDraft) {
            draftSaving=true
            val draft=input.text.toString();val id=chatId;val base=current.optString("request_id")
            product(JSONObject().put("action","draft").put("chat_id",id).put("base_request_id",base).put("text",draft)){r->
                draftSaving=false
                if(chatId==id && current.optString("request_id")==base){if(r.optBoolean("ok"))lastDraft=draft else if(screen=="chat")status.text=r.optString("message")}
                // Serialize writes; a slower earlier pipe cannot overwrite a newer draft.
                if(r.optBoolean("ok") && input.text.toString()!=lastDraft)main.post(saveDraft)
            }
        }
        if(screen=="chat")countDraft()
    }
    private fun countDraft() {
        val prompt=input.text.toString()
        if(prompt.toByteArray().size>512*1024) { budget.text=UiText.get(R.string.ui_message_exceeds_512_kib_all_text_is);sendButton.isEnabled=composeBusy() && current.optBoolean("active");return }
        if(remote()!=null){syncSource();budget.text=UiText.get(R.string.ui_response_limit_provider_counts_tokens_context_history, (ContextCompactor.remoteOutput(current)));return}
        if(counting || runtimeState!="ready" || prompt.isBlank() && pendingFiles().length()==0)return
        counting=true;val selectedChat=chatId
        product(JSONObject().put("action","count").put("chat_id",selectedChat).put("text",prompt).put("attachments",pendingFiles()).put("max_tokens",prefs.getInt("output:$model",2048)).put("thinking",prefs.getBoolean("thinking",true))) {r->
            counting=false
            if(prompt!=input.text.toString() || selectedChat!=chatId) {main.postDelayed(saveDraft,300);return@product}
            if(r.optBoolean("ok")) {
                budget.text=UiText.get(R.string.ui_input_response_tokens, (r.getInt("prompt_tokens")), (r.getInt("output_reserve")), (r.getInt("context"))) + if(r.getBoolean("fits"))UiText.get(R.string.ui_fits) else UiText.get(R.string.ui_overflow_full_history_retained)
                sendButton.isEnabled=composeBusy() && current.optBoolean("active") || (r.getBoolean("fits") || prefs.getBoolean("auto_compact",false) && !current.optBoolean("compact_auto_paused") && current.getJSONArray("messages").length()>=2) && canSend() && !attachmentLoading && (input.text.isNotBlank() || pendingFiles().length()>0)
            } else budget.text=r.optString("message")
        }
    }
    private fun command(what: Int, data: Bundle = Bundle()) {
        val endpoint = service ?: error(UiText.get(R.string.ui_no_connection_to_the_model_reopen_the))
        endpoint.send(Message.obtain(null, what).apply { this.data = data; replyTo = incoming })
    }
    private fun register() = command(RuntimeService.REGISTER, Bundle().apply { putBoolean("ui_visible", visible) })
    private fun product(request: JSONObject, callback: (JSONObject) -> Unit) {
        if (service == null) { callback(JSONObject().put("ok", false).put("message", UiText.get(R.string.ui_no_connection_to_the_model_process))); return }
        val id = UUID.randomUUID().toString(); callbacks[id] = callback
        val bytes = request.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > ChatFiles.REQUEST_BYTES) { callbacks.remove(id); callback(JSONObject().put("ok",false).put("message",UiText.get(R.string.ui_request_exceeds_8_mib_nothing_was_truncated))); return }
        val fd = ProductPipe.write(bytes)
        try { command(RuntimeService.PRODUCT, Bundle().apply { putString("id", id); putParcelable("payload", fd) }) }
        catch (e: Exception) { callbacks.remove(id); notice(e.message ?: "IPC error") } finally { fd.close() }
    }
    private fun checked(r: JSONObject, action: () -> Unit) { if (r.optBoolean("ok")) action() else notice(r.optString("message", UiText.get(R.string.ui_error))) }
    private fun newChat(after:(()->Unit)?=null) {
        saveDraft.run();product(JSONObject().put("action","blank")){r->checked(r){selectChat(r.getJSONObject("chat"));after?.invoke()}}
    }
    private fun selectChat(chat: JSONObject) {
        if(::pages.isInitialized)pages.clear()
        if(chatId==chat.optString("id") && current.optString("model")!=chat.optString("model") && pendingFiles().length()>0){composerFiles.remove(chatId);Toast.makeText(this,UiText.get(R.string.ui_attachments_removed_after_changing_the_model_original),Toast.LENGTH_LONG).show()}
        if(chatId!=chat.optString("id"))closeChatSearch()
        restoreDraft=false;fullChatNeeded=false;flushRetainedDraft=false;pinCursor=0;lastJumpedPin=-1
        chatId = chat.getString("id"); current = chat; lastRender = ""
        draftChanging = true; input.setText(chat.optString("draft")); lastDraft = input.text.toString(); draftChanging = false
        screen="chat";mainContent.visibility=View.VISIBLE;home.visibility=View.GONE;updateBackCallback();render(chat);syncSource()
        renderFileChips()
    }
    private fun refreshChat() {
        if(chatId.isEmpty() || service==null || !visible || screen!="chat")return
        if(fetching){pendingChatRefresh=true;return}
        fetching=true;pendingChatRefresh=false;val id=chatId
        product(JSONObject().put("action",if(restoreDraft || fullChatNeeded)"get"else "chat_poll").put("chat_id",id).put("view_revision",current.optLong("view_revision",-1)).put("message_count",current.optJSONArray("messages")?.length()?:-1).put("attachment_count",current.optJSONArray("messages")?.let {it.optJSONObject(it.length()-1)?.optJSONArray("attachments")?.length()}?:0)){r->
            fetching=false;val again=pendingChatRefresh;pendingChatRefresh=false;if(id!=chatId || screen!="chat"){if(again)refreshChat();return@product}
            if(r.optBoolean("ok")) {
                if(r.has("chat")){
                    current=r.getJSONObject("chat");fullChatNeeded=false
                    if(restoreDraft)selectChat(current)else {render(current);if(flushRetainedDraft){flushRetainedDraft=false;lastDraft=current.optString("draft");main.post(saveDraft)}}
                }
                else if(r.has("tail")){val a=current.getJSONArray("messages");val tail=r.getJSONObject("tail");if(tail.optBoolean("preserve_attachments"))a.optJSONObject(a.length()-1)?.optJSONArray("attachments")?.let {tail.put("attachments",it)};tail.remove("preserve_attachments");a.put(a.length()-1,tail);val metadata=r.getJSONObject("metadata");metadata.keys().forEach {key->if(key!="remote")current.put(key,metadata.get(key))};current.put("view_revision",r.getLong("view_revision"));render(current)}
                syncSource()
            }else {chatId="";current=JSONObject();input.setText("");showTab(0);notice(r.optString("message"))}
            if(again)refreshChat()
        }
    }
    private fun openPendingAnswer() {
        val answer = pendingAnswer ?: return; pendingAnswer = null
        product(JSONObject().put("action", "answer").put("answer_id", answer)) { r ->
            if (r.optBoolean("ok") && !r.isNull("chat_id")) {
                product(JSONObject().put("action", "get").put("chat_id", r.getString("chat_id"))) { c -> checked(c) { selectChat(c.getJSONObject("chat")); AnswerNotifications.read(this, answer) } }
            } else {
                chatId="";current=JSONObject();lastRender="";input.setText("");showTab(0)
                notice(UiText.get(R.string.ui_this_response_is_no_longer_available_the))
            }
        }
    }
    private fun submit() {
        val prompt=input.text.toString()
        if(prompt.isBlank() && pendingFiles().length()==0 || !canSend() || attachmentLoading)return
        // Admission includes creating the first dialog; a second tap must not
        // create another empty dialog before the first asynchronous reply.
        submitting=true;sendButton.isEnabled=false
        if(chatId.isEmpty()) {
            product(JSONObject().put("action","new")) {r->
                if(!r.optBoolean("ok")) {submitting=false;sendButton.isEnabled=runtimeState=="ready";notice(r.optString("message"));return@product}
                val chat=r.getJSONObject("chat");chatId=chat.getString("id");current=chat;restoreDraft=false;lastRender="";render(chat)
                sendPrompt(prompt)
            }
        } else sendPrompt(prompt)
    }
    private fun sendPrompt(prompt:String) {
        val submittedChat=chatId
        val request = JSONObject().put("action", if(remote()!=null)"remote_send" else "send").put("chat_id", submittedChat).put("request_id", UUID.randomUUID().toString())
            .put("text", prompt).put("max_tokens", prefs.getInt("output:$model", 2048))
            .put("thinking", prefs.getBoolean("thinking", true)).put("temperature", prefs.getFloat("temperature", 0f).toDouble())
        request.put("attachments",pendingFiles()).put("auto_compact",prefs.getBoolean("auto_compact",false))
        product(request) { r ->
            submitting=false
            checked(r) {
                composerFiles.remove(submittedChat);renderFileChips()
                if(chatId==submittedChat) {
                    if(input.text.toString()==prompt) {draftChanging=true;input.setText("");draftChanging=false}
                    lastDraft="";current=r.getJSONObject("chat");render(current);refreshChat()
                    if(input.text.isNotEmpty())main.post(saveDraft)
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
                }
            }
            syncSource()
        }
    }
    private fun styled(content: String): CharSequence {
        val result = SpannableString(content)
        val code = Regex("(?s)```[^\\n]*\\n(.*?)```")
        for (match in code.findAll(content)) { result.setSpan(TypefaceSpan("monospace"), match.range.first, match.range.last+1, 0) }
        for (match in Regex("(?m)^#{1,4} .+$").findAll(content)) result.setSpan(StyleSpan(Typeface.BOLD), match.range.first, match.range.last+1, 0)
        return result
    }
    private fun render(chat:JSONObject) {
        if(!::timeline.isInitialized)return
        timeline.render(chat);if(chat.getJSONArray("messages").length()==0)welcome();syncSource();syncPins()
        val result=if(remote()==null)lastSnapshot.optJSONObject("result")else null
        budget.text=when {current.optString("save_error").isNotEmpty()->current.optString("save_error");result!=null && lastSnapshot.optString("chat_id")==chatId->UiText.get(R.string.ui_input_response_context, (result.optInt("prompt_tokens")), (result.optInt("generated_tokens")), (lastSnapshot.optJSONObject("config")?.optInt("context")));remote()!=null->UiText.get(R.string.ui_api_limit, (ContextCompactor.remoteOutput(current)))+if(current.optBoolean("permanent"))UiText.get(R.string.ui_permanent)else UiText.get(R.string.ui_temporary);else->if(current.optBoolean("permanent"))UiText.get(R.string.ui_permanent_encrypted_storage)else UiText.get(R.string.ui_temporary_no_saved_history)}
    }
    private fun openChat(id:String) {
        saveDraft.run();val request=++navigationRequest
        product(JSONObject().put("action","get").put("chat_id",id)){r->if(request==navigationRequest)checked(r){selectChat(r.getJSONObject("chat"))}}
    }
    private fun showTab(index:Int) {
        if(::pages.isInitialized)pages.clear()
        navigationRequest++;if(screen=="chat")saveDraft.run();screen=when(index){1->"api";2->"settings";else->"dialogs"};currentTab=index
        if(!::home.isInitialized)return
        mainContent.visibility=View.GONE;home.visibility=View.VISIBLE;(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
        when(index){1->{home.showTab(1,text(UiText.get(R.string.ui_connecting_to_model_process)));refreshApiIfChanged(true)};2->{home.showTab(2,settingsPane());refreshCatalog()};else->{home.showTab(0);refreshCatalog()}}
        updateBackCallback()
    }
    private fun refreshCatalog(force:Boolean=false) {
        if(service==null || catalogFetching || !force && catalogRevision>=0 && lastSnapshot.optLong("chats_revision",-1)==catalogRevision)return
        catalogFetching=true
        product(JSONObject().put("action","list").put("since_revision",if(force)-1 else catalogRevision)){r->catalogFetching=false;checked(r){
            if(r.has("chats")){catalogItems=r.getJSONArray("chats");catalogRevision=r.optLong("revision",-1);historyConfig=r.optJSONObject("history")?:JSONObject();home.render(catalogItems,historyConfig);if(currentTab==2 && screen!="chat")home.showTab(2,settingsPane())}
            // A newer catalog event may arrive while this pipe is still being read.
            if(lastSnapshot.optLong("chats_revision",-1)>catalogRevision)refreshCatalog()
        }}
    }
    private fun sourceName()=remote()?.optString("model")?:current.optString("model").takeIf {it.isNotEmpty()}?.let {modelLabel(it)}?:UiText.get(R.string.ui_ai)
    private fun syncChatHeader() {
        if(!::chatName.isInitialized)return
        val source=sourceName();chatName.text=source
        chatSubtitle.text=(remote()?.optString("name")?:UiText.get(R.string.ui_on_device_a8395a))+" · "+if(current.optBoolean("active"))UiText.get(R.string.ui_responding)else if(current.optBoolean("permanent"))UiText.get(R.string.ui_permanent)else UiText.get(R.string.ui_temporary)
        val signature=current.optString("model")+source
        if(signature!=avatarSignature){avatarSignature=signature;avatarBox.removeAllViews();avatarBox.addView(AiAvatar(this,source,style,current.optString("model")),FrameLayout.LayoutParams(-1,-1))}
        if(current.optString("save_error").isNotEmpty()){status.text=current.getString("save_error");status.setTextColor(0xffe7a34c.toInt())}else status.setTextColor(mutedColor)
    }
    private data class SourceChoice(val title:String,val model:String,val local:ModelStore.Entry?=null,val provider:String="")
    private fun activeSources(done:(List<SourceChoice>)->Unit) {
        product(JSONObject().put("action","providers")){r->checked(r){val choices=mutableListOf<SourceChoice>()
            ModelStore.entries(this).filter {prefs.getBoolean("active:${it.id}",true)}.forEach {choices.add(SourceChoice(UiText.get(R.string.ui_on_device_50a404, (it.label.substringBefore(" ("))),it.id,it))}
            val profiles=r.getJSONArray("providers");for(i in 0 until profiles.length()){val p=profiles.getJSONObject(i);val models=p.optJSONArray("enabled_models")?:JSONArray();for(j in 0 until models.length()){val id=models.getString(j);choices.add(SourceChoice(ProviderStore.displayName(p,id),id,provider=p.getString("id")))}}
            done(choices)
        }}
    }
    private fun createDialog() {
        if(service==null){notice(UiText.get(R.string.ui_wait_for_the_model_process_to_connect));return}
        product(JSONObject().put("action","history")){h->checked(h){historyConfig=h.getJSONObject("history");activeSources {choices->
            if(choices.isEmpty()){AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_add_a_model)).setMessage(UiText.get(R.string.ui_connect_a_gguf_file_on_your_phone)).setPositiveButton(UiText.get(R.string.ui_settings)){_,_->showTab(2)}.setNegativeButton(UiText.get(R.string.ui_cancel),null).show();return@activeSources}
            val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(8))};box.addView(text(UiText.get(R.string.ui_who_shall_we_talk_to),14f).apply {setTextColor(mutedColor)})
            val models=Spinner(this).apply {adapter=ArrayAdapter(this@ChatActivity,android.R.layout.simple_spinner_dropdown_item,choices.map {it.title});contentDescription=UiText.get(R.string.ui_model_for_new_chat)};box.addView(models,LinearLayout.LayoutParams(-1,dp(56)))
            val kind=RadioGroup(this);val temporary=RadioButton(this).apply {id=View.generateViewId();text=UiText.get(R.string.ui_temporary_no_saved_history)};val permanent=RadioButton(this).apply {id=View.generateViewId();text=UiText.get(R.string.ui_permanent_save_on_device);isEnabled=historyConfig.optString("storage_error").isEmpty()};kind.addView(temporary);kind.addView(permanent);kind.check(temporary.id);box.addView(kind)
            val explain=text(UiText.get(R.string.ui_temporary_conversations_disappear_when_the_process_ends),12f).apply {setTextColor(mutedColor)};box.addView(explain)
            val enable=CheckBox(this).apply {text=UiText.get(R.string.ui_allow_permanent_chat_storage);visibility=View.GONE};box.addView(enable)
            val folders=historyConfig.optJSONArray("folders")?:JSONArray();val folderNames=listOf(UiText.get(R.string.ui_no_folder))+(0 until folders.length()).map {folders.getJSONObject(it).getString("name")};box.addView(text(UiText.get(R.string.ui_folder),13f).apply {setTextColor(mutedColor)})
            val folder=Spinner(this).apply {adapter=ArrayAdapter(this@ChatActivity,android.R.layout.simple_spinner_dropdown_item,folderNames);setSelection((0 until folders.length()).firstOrNull {folders.getJSONObject(it).getString("id")==home.folder}?.plus(1)?:0);contentDescription=UiText.get(R.string.ui_folder_for_new_chat)};box.addView(folder)
            val dialog=AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_new_chat)).setView(ScrollView(this).apply {addView(box)}).setPositiveButton(UiText.get(R.string.ui_create),null).setNegativeButton(UiText.get(R.string.ui_cancel),null).create()
            fun sync(){val needs=kind.checkedRadioButtonId==permanent.id && historyConfig.optString("mode","never")=="never";enable.visibility=if(needs)View.VISIBLE else View.GONE;dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let {it.isEnabled=!needs || enable.isChecked;it.text=if(needs)UiText.get(R.string.ui_enable_and_create)else UiText.get(R.string.ui_create)}}
            kind.setOnCheckedChangeListener {_,_->sync()};enable.setOnCheckedChangeListener {_,_->sync()}
            dialog.setOnShowListener {sync();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val durable=kind.checkedRadioButtonId==permanent.id;val choice=choices[models.selectedItemPosition];val folderId=if(folder.selectedItemPosition==0)""else folders.getJSONObject(folder.selectedItemPosition-1).getString("id")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
                fun create(){product(JSONObject().put("action","blank").put("permanent",durable).put("folder_id",folderId)){r->if(!r.optBoolean("ok")){sync();notice(r.optString("message"));return@product}
                    val id=r.getJSONObject("chat").getString("id");val request=JSONObject().put("chat_id",id).put("model",choice.model)
                    if(choice.local!=null)request.put("action","chat_model")else request.put("action","chat_remote").put("provider_id",choice.provider)
                    product(request){a->if(a.optBoolean("ok")){dialog.dismiss();navigationRequest++;selectChat(a.getJSONObject("chat"));refreshCatalog(true);if(choice.local!=null && (model!=choice.model || runtimeState!="ready") && !composeBusy())configure(choice.local)}else {product(JSONObject().put("action","delete").put("chat_id",id)){};sync();notice(a.optString("message"))}}
                }}
                if(durable && historyConfig.optString("mode")=="never")product(JSONObject().put("action","history_policy").put("mode","keep")){r->if(r.optBoolean("ok")){historyConfig=r.getJSONObject("history");create()}else {sync();notice(r.optString("message"))}}else create()
            }};dialog.show()
        }}}
    }
    private fun syncPins() {
        val pins=current.optJSONArray("pinned")?:JSONArray();pinBar.visibility=if(pins.length()==0)View.GONE else View.VISIBLE;if(pins.length()==0)return
        pinCursor=pinCursor.coerceIn(0,pins.length()-1);val at=pins.getInt(pinCursor);val m=current.optJSONArray("messages")?.optJSONObject(at)
        pinText.text=UiText.get(R.string.ui_pinned, (if(pins.length()>1)" ${pinCursor+1}/${pins.length()}"else ""))+(m?.optString("content")?.replace('\n',' ')?.take(100)?:UiText.get(R.string.ui_message))
    }
    private var lastJumpedPin=-1
    private fun jumpPin(){val a=current.optJSONArray("pinned")?:return;if(a.length()==0)return
        pinCursor=pinCursor.coerceIn(0,a.length()-1)
        if(lastJumpedPin==a.getInt(pinCursor))pinCursor=(pinCursor+1)%a.length()
        lastJumpedPin=a.getInt(pinCursor);syncPins();timeline.jump(lastJumpedPin)
    }
    private fun unpinCurrent(){val a=current.optJSONArray("pinned")?:return;if(a.length()>0)pinMessage(a.getInt(pinCursor.coerceIn(0,a.length()-1)),false)}
    private fun pinMessage(index:Int,value:Boolean){val id=chatId;product(JSONObject().put("action","chat_pin").put("chat_id",id).put("index",index).put("pinned",value)){r->checked(r){if(chatId==id){current=r.getJSONObject("chat");render(current)}}}}
    private fun messageMenu(index:Int,anchor:View){val m=current.optJSONArray("messages")?.optJSONObject(index)?:return;val pins=current.optJSONArray("pinned")?:JSONArray();val pinned=(0 until pins.length()).any {pins.getInt(it)==index}
        PopupMenu(this,anchor).apply {menu.add(if(pinned)UiText.get(R.string.ui_unpin)else UiText.get(R.string.ui_pin));menu.add(UiText.get(R.string.ui_copy));menu.add(UiText.get(R.string.ui_save_text_to_file));setOnMenuItemClickListener {item->when(item.title.toString()){UiText.get(R.string.ui_copy)->copyText(UiText.get(R.string.ui_llamur_message),m.getString("content"),true);UiText.get(R.string.ui_save_text_to_file)->saveFile(JSONObject().put("name","message.txt").put("mime","text/plain").put("text",m.getString("content")));else->pinMessage(index,!pinned)};true};show()}}
    private fun chooseAttachment() {
        if(!attachButton.isEnabled)return
        val c=remote()?.optJSONObject("capabilities");val kinds=mutableListOf("text" to UiText.get(R.string.ui_text_file_utf_8))
        if(ModelCapabilities.has(c,"input","image"))kinds.add("image" to UiText.get(R.string.ui_image_png_jpeg_webp_gif))
        if(ModelCapabilities.has(c,"input","file") || ModelCapabilities.has(c,"input","pdf"))kinds.add("pdf" to UiText.get(R.string.ui_pdf_document))
        val target=chatId;val selected=current.optString("model")
        AlertDialog.Builder(this).setTitle(if(remote()!=null)UiText.get(R.string.ui_attachments, (remote()!!.optString("name")))else UiText.get(R.string.ui_attachments_for_local_model))
            .setMenuItems(kinds.map {it.second}.toTypedArray()){_,n->
                if(chatId!=target || current.optString("model")!=selected)return@setMenuItems
                attachmentKind=kinds[n].first;attachmentChat=target;attachmentModel=selected
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(when(attachmentKind){"image"->"image/*";"pdf"->"application/pdf";else->"*/*"}).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),10)
            }.setNegativeButton(UiText.get(R.string.ui_cancel),null).show()
    }
    private fun renderFileChips() {
        if(!::fileChips.isInitialized)return
        fileChips.removeAllViews();val files=pendingFiles();fileChips.visibility=if(files.length()>0)View.VISIBLE else View.GONE
        for(i in 0 until files.length()){val f=files.getJSONObject(i);fileChips.addView(button("${f.getString("name")}  ×"){
            val next=JSONArray();for(n in 0 until files.length())if(n!=i)next.put(files.getJSONObject(n));composerFiles.put(chatId,next);renderFileChips();countDraft();syncSource()
        },LinearLayout.LayoutParams(-1,dp(42)))}
    }
    private fun saveFile(file:JSONObject) {
        if(exportFile!=null){notice(UiText.get(R.string.ui_finish_the_previous_file_save_first));return}
        exportFile=file
        try {startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(file.optString("mime","application/octet-stream")).putExtra(Intent.EXTRA_TITLE,ChatFiles.name(file.getString("name"))),11)}
        catch(_:Exception){exportFile=null;notice(UiText.get(R.string.ui_no_app_found_for_saving_files))}
    }
    private fun closeChatSearch(){chatSearch.setText("");chatSearchRow.visibility=View.GONE;(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(chatSearch.windowToken,0)}
    private fun showChatSearch(){chatSearchRow.visibility=View.VISIBLE;chatSearch.requestFocus();chatSearch.post {(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(chatSearch,InputMethodManager.SHOW_IMPLICIT)}}
    private fun stepSearch(direction:Int){if(searchMatches.isEmpty()){searchCount.text=if(chatSearch.text.isEmpty())""else "0";return};searchCursor=Math.floorMod(searchCursor+direction,searchMatches.size);searchCount.text="${searchCursor+1}/${searchMatches.size}";timeline.jump(searchMatches[searchCursor])}
    private fun copyText(label:String,value:String,sensitive:Boolean=false){val clip=ClipData.newPlainText(label,value);if(sensitive)clip.description.extras=PersistableBundle().apply {putBoolean("android.content.extra.IS_SENSITIVE",true)};getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip);Toast.makeText(this,UiText.get(R.string.ui_copied),Toast.LENGTH_SHORT).show()}
    private fun showChatMenu(id:String) {
        val m=(0 until catalogItems.length()).map {catalogItems.getJSONObject(it)}.firstOrNull {it.getString("id")==id}?:current.takeIf {it.optString("id")==id}?:return
        val options=arrayOf(UiText.get(R.string.ui_open),UiText.get(R.string.ui_rename),UiText.get(R.string.ui_move_to_folder),if(m.optBoolean("permanent"))UiText.get(R.string.ui_make_temporary)else UiText.get(R.string.ui_make_permanent),UiText.get(R.string.ui_delete_chat),UiText.get(R.string.ui_context_and_compaction))
        AlertDialog.Builder(this).setTitle(m.optString("title",UiText.get(R.string.ui_chat))).setMenuItems(options){_,n->when(n){0->openChat(id);1->{val field=field(UiText.get(R.string.ui_chat_name),m.optString("title"));AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_name)).setView(field).setPositiveButton(UiText.get(R.string.ui_save)){_,_->product(JSONObject().put("action","chat_rename").put("chat_id",id).put("title",field.text.toString())){r->checked(r){refreshCatalog(true);if(id==chatId){current=r.getJSONObject("chat");render(current)}}}}.setNegativeButton(UiText.get(R.string.ui_cancel),null).show()};2->moveDialog(id);3->changeDurability(id,!m.optBoolean("permanent"));4->AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_delete_chat_525ef8)).setMessage(if(m.optBoolean("active"))UiText.get(R.string.ui_stop_the_response_in_this_chat_first)else if(m.optBoolean("permanent"))UiText.get(R.string.ui_this_conversation_will_be_removed_from_the)else UiText.get(R.string.ui_this_temporary_conversation_will_be_closed)).setPositiveButton(if(m.optBoolean("active"))UiText.get(R.string.ui_open)else UiText.get(R.string.ui_delete)){_,_->if(m.optBoolean("active"))openChat(id)else product(JSONObject().put("action","delete").put("chat_id",id)){r->checked(r){composerFiles.remove(id);if(chatId==id){chatId="";current=JSONObject();input.setText("");showTab(0)};refreshCatalog(true)}}}.setNegativeButton(UiText.get(R.string.ui_cancel),null).show();5->showCompaction(id)}}.setNegativeButton(UiText.get(R.string.ui_back),null).show()
    }
    private fun moveDialog(id:String){product(JSONObject().put("action","history")){r->checked(r){val a=r.getJSONObject("history").getJSONArray("folders");AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_chat_folder)).setMenuItems((listOf(UiText.get(R.string.ui_no_folder))+(0 until a.length()).map {a.getJSONObject(it).getString("name")}).toTypedArray()){_,n->product(JSONObject().put("action","chat_move").put("chat_id",id).put("folder_id",if(n==0)""else a.getJSONObject(n-1).getString("id"))){v->checked(v){refreshCatalog(true);if(chatId==id){current=v.getJSONObject("chat");render(current)}}}}.setNegativeButton(UiText.get(R.string.ui_back),null).show()}}}
    private fun changeDurability(id:String,permanent:Boolean) {
        fun change(){product(JSONObject().put("action","chat_permanent").put("chat_id",id).put("permanent",permanent)){r->checked(r){if(id==chatId){current=r.getJSONObject("chat");render(current)};refreshCatalog(true)}}}
        product(JSONObject().put("action","history")){r->checked(r){val h=r.getJSONObject("history")
            if(permanent && h.optString("mode")=="never")AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_allow_storage)).setMessage(UiText.get(R.string.ui_permanent_chat_storage_will_be_enabled_only)).setPositiveButton(UiText.get(R.string.ui_enable_and_save)){_,_->product(JSONObject().put("action","history_policy").put("mode","keep")){v->checked(v){change()}}}.setNegativeButton(UiText.get(R.string.ui_cancel),null).show()
            else if(!permanent)AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_make_temporary_7ccb87)).setMessage(UiText.get(R.string.ui_the_saved_copy_will_be_deleted_the)).setPositiveButton(UiText.get(R.string.ui_make_temporary)){_,_->change()}.setNegativeButton(UiText.get(R.string.ui_cancel),null).show()else change()
        }}
    }
    private fun folderActions(id:String?) {
        product(JSONObject().put("action","history")){r->checked(r){val a=r.getJSONObject("history").getJSONArray("folders")
            fun edit(existing:JSONObject?){val f=field(UiText.get(R.string.ui_folder_name),existing?.optString("name")?:"");AlertDialog.Builder(this).setTitle(if(existing==null)UiText.get(R.string.ui_new_folder)else UiText.get(R.string.ui_rename_folder)).setView(f).setPositiveButton(UiText.get(R.string.ui_save)){_,_->val request=JSONObject().put("action","folder").put("name",f.text.toString());existing?.let {request.put("folder_id",it.getString("id"))};product(request){v->checked(v){refreshCatalog(true)}}}.setNegativeButton(UiText.get(R.string.ui_cancel),null).show()}
            if(id==""){edit(null);return@checked}
            val selected=(0 until a.length()).map {a.getJSONObject(it)}.firstOrNull {it.getString("id")==id}
            if(selected!=null)AlertDialog.Builder(this).setTitle(selected.getString("name")).setMenuItems(arrayOf(UiText.get(R.string.ui_rename),UiText.get(R.string.ui_delete_folder))){_,n->if(n==0)edit(selected)else AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_delete_folder_445e61)).setMessage(UiText.get(R.string.ui_chats_stay_in_the_main_list_their)).setPositiveButton(UiText.get(R.string.ui_delete_folder)){_,_->product(JSONObject().put("action","folder").put("folder_id",selected.getString("id")).put("remove",true)){v->checked(v){refreshCatalog(true)}}}.setNegativeButton(UiText.get(R.string.ui_cancel),null).show()}.setNegativeButton(UiText.get(R.string.ui_back),null).show()
            else AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_folders)).setMenuItems((listOf(UiText.get(R.string.ui_create_folder))+(0 until a.length()).map {a.getJSONObject(it).getString("name")}).toTypedArray()){_,n->if(n==0)edit(null)else folderActions(a.getJSONObject(n-1).getString("id"))}.setNegativeButton(UiText.get(R.string.ui_back),null).show()
        }}
    }
    private fun field(label:String,value:String="")=EditText(this).apply {minimumHeight=dp(52);layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8);bottomMargin=dp(8)};hint=label;contentDescription=label;setText(value);setTextColor(foregroundColor);setHintTextColor(mutedColor);isSingleLine=true;isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS;imeOptions=imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;setPadding(dp(16),dp(12),dp(16),dp(12))}
    private fun panel()=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(10),dp(16),dp(24))}
    private fun settingRow(title:String,subtitle:String,icon:AppIcon=AppIcon.SETTINGS,action:()->Unit):View {
        val row=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(10),dp(8),dp(10));minimumHeight=dp(72);background=style.ripple(this@ChatActivity,style.surface,16f);setOnClickListener {action()}}
        row.addView(IconButton(this,icon,title,style,style.selected,ink=accent).apply {isClickable=false;isFocusable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(42),dp(44)))
        val texts=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(8),0,dp(6),0)};texts.addView(text(title,15.5f).apply {setPadding(0,0,0,dp(3))});texts.addView(text(subtitle,12.5f).apply {setPadding(0,0,0,0);setTextColor(mutedColor)});row.addView(texts,LinearLayout.LayoutParams(0,-2,1f));row.addView(IconButton(this,AppIcon.CHEVRON,"",style,ink=mutedColor).apply {isClickable=false;isFocusable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(26),dp(32)));return row
    }
    private fun settingsPane():View {
        val box=panel();fun group(title:String){box.addView(text(title,12f).apply {setTextColor(accent);setPadding(dp(8),dp(16),0,dp(8))})};fun row(t:String,d:String,i:AppIcon=AppIcon.SETTINGS,f:()->Unit){box.addView(settingRow(t,d,i,f),LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(8)})}
        group(UiText.get(R.string.ui_models_and_connections));row(UiText.get(R.string.ui_models_on_device),UiText.get(R.string.ui_gguf_import_hugging_face_profiles_and_storage),AppIcon.FOLDER){showModels()};row(UiText.get(R.string.ui_api_connections),UiText.get(R.string.ui_openrouter_openai_claude_and_custom_servers),AppIcon.SERVER){providerUi().show()};row(UiText.get(R.string.ui_active_local_models),UiText.get(R.string.ui_models_available_when_creating_a_chat),AppIcon.CHATS){showEnabledLocalModels()}
        group(UiText.get(R.string.ui_chats));val mode=when(historyConfig.optString("mode","never")){"keep"->UiText.get(R.string.ui_keep_permanent_chats);"auto"->UiText.get(R.string.ui_auto_delete_permanent_chats);else->UiText.get(R.string.ui_never_save)};row(UiText.get(R.string.ui_chat_storage),mode+UiText.get(R.string.ui_temporary_chats_are_not_saved),AppIcon.CHATS){historySettings()};row(UiText.get(R.string.ui_folders),UiText.get(R.string.ui_create_rename_and_organize_chat_folders),AppIcon.FOLDER){folderActions(null)}
        box.addView(Switch(this).apply {text=UiText.get(R.string.ui_automatically_compact_context);textSize=15.5f;setTextColor(foregroundColor);setPadding(dp(16),dp(12),dp(16),dp(12));minimumHeight=dp(64);isChecked=prefs.getBoolean("auto_compact",false);contentDescription=text;background=style.shape(this@ChatActivity,style.surface,18f);setOnCheckedChangeListener {_,on->prefs.edit().putBoolean("auto_compact",on).apply()}})
        box.addView(text(UiText.get(R.string.ui_off_by_default_before_sending_when_the),12f).apply {setTextColor(mutedColor)})
        if(historyConfig.optString("storage_error").isNotEmpty())row(UiText.get(R.string.ui_storage_unavailable),historyConfig.getString("storage_error"),AppIcon.INFO){historySettings()}
        group(UiText.get(R.string.ui_appearance_and_behavior));row(UiText.get(R.string.ui_theme),arrayOf(UiText.get(R.string.ui_system),UiText.get(R.string.ui_light),UiText.get(R.string.ui_dark))[prefs.getInt("theme",0).coerceIn(0,2)]){themeSettings()};row(UiText.get(R.string.ui_app_language),when(UiText.language()){"ru"->UiText.get(R.string.ui_russian);"en"->"English";else->UiText.get(R.string.ui_follow_system)}){languageSettings()};row(UiText.get(R.string.ui_notifications),UiText.get(R.string.ui_response_ready_no_conversation_text),AppIcon.CHATS){explainNotifications()}
        val thinking=Switch(this).apply {text=UiText.get(R.string.ui_local_model_reasoning);textSize=15.5f;setTextColor(foregroundColor);setPadding(dp(16),dp(12),dp(16),dp(12));minimumHeight=dp(64);isChecked=prefs.getBoolean("thinking",true);contentDescription=UiText.get(R.string.ui_local_model_reasoning);background=style.shape(this@ChatActivity,style.surface,18f);setOnCheckedChangeListener {_,on->prefs.edit().putBoolean("thinking",on).apply()}}
        box.addView(thinking,LinearLayout.LayoutParams(-1,-2));box.addView(text(UiText.get(R.string.ui_for_local_models_with_switchable_thinking_configure),12f).apply {setTextColor(mutedColor)})
        row(UiText.get(R.string.ui_local_model_temperature),prefs.getFloat("temperature",0f).toString()){val f=field(UiText.get(R.string.ui_temperature_0_5),prefs.getFloat("temperature",0f).toString()).apply {inputType=8194};AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_temperature)).setView(f).setPositiveButton(UiText.get(R.string.ui_save)){_,_->safely {val n=f.text.toString().toFloat();require(n.isFinite() && n in 0f..5f);prefs.edit().putFloat("temperature",n).apply();home.showTab(2,settingsPane())}}.setNegativeButton(UiText.get(R.string.ui_cancel),null).show()}
        row(UiText.get(R.string.ui_work_with_screen_off),UiText.get(R.string.ui_android_limits_and_battery_settings),AppIcon.INFO){AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_background_operation)).setMessage(UiText.get(R.string.ui_a_foreground_service_supports_a_response_you)).setPositiveButton(UiText.get(R.string.ui_battery_settings)){_,_->startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}.setNegativeButton(UiText.get(R.string.ui_back),null).show()}
        group(UiText.get(R.string.ui_application));row(UiText.get(R.string.ui_about_llamur),BuildConfig.VERSION_NAME,AppIcon.INFO){showAbout()};row(UiText.get(R.string.ui_diagnostics),UiText.get(R.string.ui_device_capabilities_and_runtime_checks),AppIcon.INFO){startActivity(Intent(this,MainActivity::class.java))};row(UiText.get(R.string.ui_licenses),BuildConfig.VERSION_NAME,AppIcon.INFO){val names=assets.list("licenses").orEmpty();AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_licenses)).setMenuItems(names){_,n->val content=assets.open("licenses/"+names[n]).bufferedReader().use {it.readText()};noticeText(names[n],text(content,12f).apply {setTextIsSelectable(true)})}.setNegativeButton(UiText.get(R.string.ui_back),null).show()}
        return ScrollView(this).apply {isFillViewport=true;addView(box)}
    }
    private fun showAbout() {
        val url="https://github.com/gordeev/llamur"
        val box=panel();box.addView(text("llamur",26f));box.addView(text(BuildConfig.VERSION_NAME,14f))
        box.addView(text(UiText.get(R.string.ui_about_llamur_description),15f))
        box.addView(button(UiText.get(R.string.ui_source_code_on_github)){startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)))})
        box.addView(text(url,13f).apply {setTextIsSelectable(true);setTextColor(accent)})
        pages.open(UiText.get(R.string.ui_about_llamur),ScrollView(this).apply {addView(box)})
    }
    private fun historySettings(replace:Boolean=false) {val pageRevision=pages.revision;product(JSONObject().put("action","history")){r->if(isDestroyed || isFinishing || pages.revision!=pageRevision)return@product;checked(r){
        historyConfig=r.getJSONObject("history");val error=historyConfig.optString("storage_error")
        if(error.isNotEmpty()){AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_storage_unavailable)).setMessage(error+UiText.get(R.string.ui_you_can_continue_temporary_chats_resetting_deletes)).setPositiveButton(UiText.get(R.string.ui_delete_saved_data)){_,_->AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_confirm_reset)).setMessage(UiText.get(R.string.ui_delete_all_saved_chats_and_the_history)).setPositiveButton(UiText.get(R.string.ui_delete)){_,_->product(JSONObject().put("action","history_reset").put("confirm_clear",true)){v->checked(v){refreshCatalog(true);if(pages.revision==pageRevision)historySettings(replace)}}}.setNegativeButton(UiText.get(R.string.ui_cancel),null).show()}.setNegativeButton(UiText.get(R.string.ui_continue_without_saving),null).show();return@checked}
        fun apply(mode:String,ttl:Long=7L*86400000){val revision=pages.revision;product(JSONObject().put("action","history_policy").put("mode",mode).put("ttl_ms",ttl).put("confirm_clear",true)){v->checked(v){historyConfig=v.getJSONObject("history");refreshCatalog(true);if(pages.revision==revision)historySettings(true)}}}
        val options=listOf(
            SettingsPages.Option(UiText.get(R.string.ui_never_save),UiText.get(R.string.ui_all_chats_stay_in_memory_only_history),AppIcon.CHATS),
            SettingsPages.Option(UiText.get(R.string.ui_keep_permanent_chats),UiText.get(R.string.ui_permanent_chats_are_encrypted_on_your_device),AppIcon.FOLDER),
            SettingsPages.Option(UiText.get(R.string.ui_auto_delete_permanent_chats),UiText.get(R.string.ui_encrypted_history_is_deleted_after_a_selected),AppIcon.TRASH))
        pages.choices(UiText.get(R.string.ui_chat_storage),UiText.get(R.string.ui_choose_what_stays_on_your_device_temporary),options,listOf("never","keep","auto").indexOf(historyConfig.optString("mode","never")),replace){n->when(n){
            0->if(historyConfig.optInt("saved_count")>0)AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_delete_saved_chats)).setMessage(UiText.get(R.string.ui_all_saved_copies_will_be_deleted_conversations)).setPositiveButton(UiText.get(R.string.ui_delete_and_stop_saving)){_,_->apply("never")}.setNegativeButton(UiText.get(R.string.ui_cancel),null).show()else apply("never")
            1->apply("keep")
            2->{val labels=listOf(UiText.get(R.string.ui_after_1_hour),UiText.get(R.string.ui_after_24_hours),UiText.get(R.string.ui_after_7_days),UiText.get(R.string.ui_after_30_days));val times=listOf(3600000L,86400000L,7L*86400000,30L*86400000)
                pages.choices(UiText.get(R.string.ui_auto_delete),UiText.get(R.string.ui_retention_starts_from_the_last_activity_android),labels.map {SettingsPages.Option(it,icon=AppIcon.TRASH)},if(historyConfig.optString("mode")=="auto")times.indexOf(historyConfig.optLong("ttl_ms"))else -1){at->
                    AlertDialog.Builder(this).setTitle(labels[at]).setMessage(UiText.get(R.string.ui_apply_this_retention_period_to_all_permanent)).setPositiveButton(UiText.get(R.string.ui_apply)){_,_->pages.back();apply("auto",times[at])}.setNegativeButton(UiText.get(R.string.ui_cancel),null).show()
                }
            }
        }}
    }}}
    private fun themeSettings() {
        val options=listOf(SettingsPages.Option(UiText.get(R.string.ui_system),UiText.get(R.string.ui_follow_the_android_theme)),SettingsPages.Option(UiText.get(R.string.ui_light),UiText.get(R.string.ui_light_background_and_subtle_dividers)),SettingsPages.Option(UiText.get(R.string.ui_dark),UiText.get(R.string.ui_dark_surfaces_and_soft_accents)))
        pages.choices(UiText.get(R.string.ui_theme),UiText.get(R.string.ui_choose_the_app_s_appearance),options,prefs.getInt("theme",0).coerceIn(0,2)){n->prefs.edit().putInt("theme",n).apply();recreate()}
    }
    private fun languageSettings() {
        pages.choices(UiText.get(R.string.ui_app_language),UiText.get(R.string.ui_interface_language_does_not_change_your_messages),listOf(SettingsPages.Option(UiText.get(R.string.ui_follow_system),UiText.get(R.string.ui_russian_or_english_english_for_other_languages)),SettingsPages.Option(UiText.get(R.string.ui_russian),UiText.get(R.string.ui_russian_interface)),SettingsPages.Option("English","English interface")),listOf("","ru","en").indexOf(UiText.language())){n->
            val tag=listOf("","ru","en")[n]
            if(android.os.Build.VERSION.SDK_INT>=33)UiText.choose(tag)else product(JSONObject().put("action","ui_language").put("language",tag)){r->checked(r){UiText.choose(tag);recreate()}}
        }
    }
    private fun syncCompaction() {
        if(!::compactBar.isInitialized)return
        val active=current.optBoolean("compaction_active",current.has("compaction"));val pending=current.optBoolean("compaction_pending",current.has("compact_preview"))
        compactBar.text=when {current.optBoolean("compacting")->UiText.get(R.string.ui_compacting_context_stop_with);pending->UiText.get(R.string.ui_summary_ready_view_and_apply);current.optString("compaction_error").isNotEmpty()->UiText.get(R.string.ui_compaction_incomplete_details);active->UiText.get(R.string.ui_context_compacted_view_summary_undo);else->""}
        compactBar.visibility=if(compactBar.text.isEmpty())View.GONE else View.VISIBLE
    }
    private fun editSummary(id:String,preview:Boolean,value:JSONObject) {
        val box=panel();box.addView(text(UiText.get(R.string.ui_edit_summary_help),14f))
        val edit=field(UiText.get(R.string.ui_summary),value.getString("text")).apply {isSingleLine=false;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;minLines=8;gravity=Gravity.TOP};box.addView(edit)
        box.addView(button(UiText.get(R.string.ui_copy_all)){copyText(UiText.get(R.string.ui_summary),edit.text.toString(),true)})
        box.addView(button(UiText.get(R.string.ui_save)){
            if(edit.text.isBlank() || edit.text.length>65536){edit.error=UiText.get(R.string.ui_summary_edit_limit);return@button}
            val revision=pages.revision
            product(JSONObject().put("action","compact_edit").put("chat_id",id).put("preview",preview).put("summary_id",value.getString("id")).put("text",edit.text.toString())){a->checked(a){
                if(id==chatId){current=a.getJSONObject("chat");render(current)};refreshCatalog(true)
                if(pages.revision==revision){pages.back();showCompaction(id,true)}
            }}
        })
        pages.open(UiText.get(R.string.ui_edit_summary),ScrollView(this).apply {addView(box)})
    }
    private fun showCompaction(id:String,replace:Boolean=false) {
        if(id.isEmpty() || replace && !pages.showing)return
        val pageRevision=pages.revision
        product(JSONObject().put("action","compact_info").put("chat_id",id)){r->if(isDestroyed || isFinishing || pages.revision!=pageRevision)return@product;checked(r){
            val c=r.getJSONObject("chat");val box=panel();val remote=c.optJSONObject("remote");val working=c.optJSONObject("compaction");val pending=c.optJSONObject("compact_preview");val running=c.optBoolean("active")
            box.addView(text(c.getString("title"),21f));box.addView(text(UiText.get(R.string.ui_original_messages, (c.getJSONArray("messages").length()))+if(c.optBoolean("permanent"))UiText.get(R.string.ui_encrypted_storage_with_chat_retention)else UiText.get(R.string.ui_temporary_chat_ram_only),13f).apply {setTextColor(mutedColor)})
            box.addView(text(UiText.get(R.string.ui_the_summary_replaces_earlier_messages_only_in),14f))
            val global=prefs.getBoolean("auto_compact",false)
            box.addView(text(if(c.optBoolean("compact_auto_paused"))UiText.get(R.string.ui_auto_compaction_is_paused_for_this_chat)else if(global)UiText.get(R.string.ui_auto_compaction_is_on_runs_before_sending)else UiText.get(R.string.ui_auto_compaction_is_off_enable_it_in),13f).apply {setTextColor(accent)})
            if(c.optString("compaction_error").isNotEmpty())box.addView(text(c.getString("compaction_error"),14f))
            fun action(name:String,command:String,extra:JSONObject=JSONObject()) {
                box.addView(button(name){val revision=pages.revision;extra.put("action",command).put("chat_id",id);product(extra){a->checked(a){if(id==chatId){current=a.getJSONObject("chat");render(current)};refreshCatalog(true);if(pages.revision==revision)showCompaction(id,true)}}}.apply {isEnabled=!running})
            }
            fun summary(title:String,value:JSONObject,preview:Boolean){
                box.addView(text(title,18f))
                box.addView(text(if(value.optBoolean("edited"))UiText.get(R.string.ui_summary_manually_edited)else UiText.get(R.string.ui_steps_messages_summarized, (if(remote==null)UiText.get(R.string.ui_tokens)else UiText.get(R.string.ui_estimated_api_tokens)), (value.optLong("before_tokens")), (value.optLong("after_tokens")), (value.optInt("calls")), (value.optInt("through"))),12f).apply {setTextColor(mutedColor)})
                box.addView(text(value.getString("text"),15f).apply {setTextIsSelectable(true)})
                box.addView(button(UiText.get(R.string.ui_copy_all)){copyText(title,value.getString("text"),true)})
                box.addView(button(UiText.get(R.string.ui_edit_summary)){editSummary(id,preview,value)}.apply {isEnabled=!running})
            }
            if(pending!=null){summary(UiText.get(R.string.ui_preview_not_applied_yet),pending,true);action(UiText.get(R.string.ui_apply_summary),"compact_apply");action(UiText.get(R.string.ui_discard_preview),"compact_discard")}
            if(working!=null){summary(UiText.get(R.string.ui_active_summary),working,false);action(UiText.get(R.string.ui_undo_compaction),"compact_undo")}
            if(c.optBoolean("compact_auto_paused"))action(UiText.get(R.string.ui_resume_auto_compaction),"compact_resume")
            if(remote!=null){val known=remote.optJSONObject("capabilities")?.optInt("context")?:0;box.addView(text(UiText.get(R.string.ui_catalogue_window_api_usage_is_estimated_from, (if(known>0)known.toString()else UiText.get(R.string.ui_unknown))),13f))
                val f=field(UiText.get(R.string.ui_api_window_in_tokens_0_catalogue),c.optInt("compact_window").toString()).apply {inputType=2};box.addView(f)
                box.addView(button(UiText.get(R.string.ui_save_api_window)){safely {val revision=pages.revision;val n=f.text.toString().toInt();require(n==0 || n in 1024..2097152){UiText.get(R.string.ui_enter_0_or_1024_2097152)};product(JSONObject().put("action","compact_window").put("chat_id",id).put("context",n)){a->checked(a){if(pages.revision==revision)showCompaction(id,true)}}}}.apply {isEnabled=!running})
            }
            box.addView(button(if(running)UiText.get(R.string.ui_operation_in_progress)else UiText.get(R.string.ui_compact_context)){AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_create_summary)).setMessage(UiText.get(R.string.ui_the_same_model_will_process_earlier_conversation)+if(remote!=null)UiText.get(R.string.ui_this_uses_up_to_16_additional_requests)else UiText.get(R.string.ui_this_takes_time_on_your_phone_you)).setPositiveButton(UiText.get(R.string.ui_create_summary_72b9b5)){_,_->
                val revision=pages.revision;val req=JSONObject().put("action","compact_start").put("chat_id",id).put("text",if(id==chatId)input.text.toString()else "").put("attachments",if(id==chatId)pendingFiles()else JSONArray()).put("max_tokens",prefs.getInt("output:${c.optString("model")}",2048)).put("thinking",prefs.getBoolean("thinking",true))
                product(req){a->checked(a){if(pages.revision==revision)pages.clear();if(id==chatId){current=a.getJSONObject("chat");render(current)};refreshCatalog(true);notice(UiText.get(R.string.ui_compaction_started_the_completed_summary_will_appear))}}
            }.setNegativeButton(UiText.get(R.string.ui_cancel),null).show()}.apply {isEnabled=!running && c.getJSONArray("messages").length()>=2})
            if(running)box.addView(button(UiText.get(R.string.ui_refresh)){showCompaction(id,true)})
            pages.open(UiText.get(R.string.ui_context_and_compaction),ScrollView(this).apply {addView(box)},replace)
        }}
    }
    private fun showModelInfo() {
        if(current.optString("model").isEmpty()){showActiveModels();return}
        val box=panel();val source=remote();box.addView(text(sourceName(),22f).apply {typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)})
        box.addView(text(if(source==null)UiText.get(R.string.ui_on_device_original_gguf)else UiText.get(R.string.ui_api_256236, (source.optString("name"))),14f).apply {setTextColor(accent)})
        box.addView(text("ID: "+if(source==null)current.getString("model")else source.getString("model"),13f).apply {setTextIsSelectable(true)})
        if(source!=null){box.addView(text("${source.optString("protocol")}\n${source.optString("base_url")}",13f));val limitInfo=text(UiText.get(R.string.ui_response_reserve_context_and_usage_are_determined,ContextCompactor.remoteOutput(current)),13f);box.addView(limitInfo)
            box.addView(button(UiText.get(R.string.ui_api_response_limit)){
                val id=current.getString("id");val settings=panel();settings.addView(text(UiText.get(R.string.ui_api_response_limit_help),14f))
                val edit=field(UiText.get(R.string.ui_api_response_limit),current.optInt("remote_max_output").toString()).apply {inputType=2};settings.addView(edit)
                val dialog=AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_api_response_limit)).setView(settings).setPositiveButton(UiText.get(R.string.ui_save),null).setNegativeButton(UiText.get(R.string.ui_cancel),null).create()
                dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val limit=edit.text.toString().toIntOrNull();if(limit==null || limit !in 0..131072){edit.error="0–131072";return@setOnClickListener}
                    product(JSONObject().put("action","chat_output_limit").put("chat_id",id).put("max_output",limit)){a->checked(a){val c=a.getJSONObject("chat");if(id==chatId){current=c;render(current)};limitInfo.text=UiText.get(R.string.ui_response_reserve_context_and_usage_are_determined,ContextCompactor.remoteOutput(c));dialog.dismiss()}}
                }};dialog.show()
            }.apply {isEnabled=!composeBusy()})
            val a=current.getJSONArray("messages");var total=0.0;var count=0;val units=mutableSetOf<String>();for(i in 0 until a.length()){val usage=a.getJSONObject(i).optJSONObject("remote_usage")?:continue;val cost=usage.opt("cost");if(cost is Number && cost.toDouble().isFinite() && cost.toDouble()>=0){total+=cost.toDouble();count++;units.add(usage.optString("currency").ifBlank {UiText.get(R.string.ui_api_units)})}}
            box.addView(text(if(count>0 && units.size==1 && total.isFinite())UiText.get(R.string.ui_reported_cost_responses, ("%.6f".format(java.util.Locale.ROOT,total)), (units.single()), (count))else UiText.get(R.string.ui_api_did_not_report_comparable_cost),13f));box.addView(button(UiText.get(R.string.ui_api_connections)){providerUi().show()})
        }else {val id=current.getString("model");val context=if(model==id)lastSnapshot.optJSONObject("config")?.optInt("context")?:prefs.getInt("context:$id",4096)else prefs.getInt("context:$id",4096);box.addView(text(UiText.get(R.string.ui_context_response_cpu_f16_kv_weights_unchanged, (context), (prefs.getInt("output:$id",2048))),13f));ModelStore.entries(this).firstOrNull {it.id==id}?.let {entry->box.addView(button(if(model==id && runtimeState=="ready")UiText.get(R.string.ui_model_profile)else UiText.get(R.string.ui_load_model_profile)){configure(entry)})}}
        box.addView(text(if(current.optBoolean("permanent"))UiText.get(R.string.ui_permanent_chat_encrypted_storage)else UiText.get(R.string.ui_temporary_chat_ram_only_be9cea),13f))
        if(current.optString("save_error").isNotEmpty())box.addView(button(UiText.get(R.string.ui_retry_saving)){product(JSONObject().put("action","chat_commit").put("chat_id",chatId)){r->checked(r){current=r.getJSONObject("chat");render(current)}}})
        if(current.getJSONArray("messages").length()==0)box.addView(button(UiText.get(R.string.ui_choose_another_model)){showActiveModels()})
        AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_about_model_and_chat)).setView(ScrollView(this).apply {addView(box)}).setPositiveButton(UiText.get(R.string.ui_done),null).show()
    }
    private fun refreshApiIfChanged(force:Boolean=false) {
        if(screen!="api" || service==null)return
        val signature="$model:$runtimeState:${composeBusy()}";if(!force && apiSignature==signature)return;apiSignature=signature
        product(JSONObject().put("action","api_info")){r->if(screen=="api")checked(r){home.showTab(1,apiPane(r))}}
    }
    private fun apiPane(info:JSONObject):View {
        val box=panel();val running=info.optBoolean("running");val entries=ModelStore.entries(this)
        box.addView(text(if(running)UiText.get(R.string.ui_server_running)else UiText.get(R.string.ui_server_stopped),22f).apply {typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setTextColor(if(running)accent else foregroundColor)})
        box.addView(text(UiText.get(R.string.ui_only_a_local_model_is_served_client),13f).apply {setTextColor(mutedColor)})
        if(entries.isEmpty()){box.addView(button(UiText.get(R.string.ui_add_gguf_hugging_face)){showModels()});return ScrollView(this).apply {addView(box)}}
        val selected=if(running)model else apiDraft?.optString("model")?.takeIf {it.isNotEmpty()}?:prefs.getString("api_selected_model",model.ifEmpty {entries.first().id});var entry=entries.firstOrNull {it.id==selected}?:entries.first()
        val picker=Spinner(this).apply {adapter=ArrayAdapter(this@ChatActivity,android.R.layout.simple_spinner_dropdown_item,entries.map {it.label});setSelection(entries.indexOf(entry));isEnabled=!running;contentDescription=UiText.get(R.string.ui_local_api_model);visibility=if(running)View.GONE else View.VISIBLE};box.addView(picker,LinearLayout.LayoutParams(-1,dp(56)))
        val publicId=field(UiText.get(R.string.ui_public_model_id),(if(running)info.optString("model_id",model)else apiDraft?.takeIf {it.optString("model")==entry.id}?.optString("id")?:prefs.getString("api_id:${entry.id}",entry.id))?:entry.id).apply {isEnabled=!running};val name=field(UiText.get(R.string.ui_model_name),(if(running)info.optString("model_name",modelLabel(model))else apiDraft?.takeIf {it.optString("model")==entry.id}?.optString("name")?:prefs.getString("api_name:${entry.id}",modelLabel(entry.id)))?:modelLabel(entry.id)).apply {isEnabled=!running};box.addView(publicId);box.addView(name)
        val port=field(UiText.get(R.string.ui_port),apiDraft?.optString("port")?:prefs.getInt("api_port",8080).toString()).apply {inputType=android.text.InputType.TYPE_CLASS_NUMBER;isEnabled=!running};box.addView(port)
        val lan=CheckBox(this).apply {text=UiText.get(R.string.ui_wi_fi_access_https_bearer);isChecked=if(running)info.optBoolean("lan")else apiDraft?.optBoolean("lan")?:prefs.getBoolean("api_lan",false);isEnabled=!running};box.addView(lan)
        fun saveApiDraft(){if(!running)apiDraft=JSONObject().put("model",entry.id).put("id",publicId.text.toString()).put("name",name.text.toString()).put("port",port.text.toString()).put("lan",lan.isChecked)}
        val formWatcher=object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){saveApiDraft()};override fun afterTextChanged(s:Editable?) {}}
        listOf(publicId,name,port).forEach {it.addTextChangedListener(formWatcher)};lan.setOnCheckedChangeListener {_,_->saveApiDraft()};saveApiDraft()
        picker.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(parent:AdapterView<*>?){};override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){val next=entries[position];if(next.id!=entry.id){prefs.edit().putString("api_id:${entry.id}",publicId.text.toString()).putString("api_name:${entry.id}",name.text.toString()).putString("api_selected_model",next.id).apply();entry=next;publicId.setText(prefs.getString("api_id:${entry.id}",entry.id));name.setText(prefs.getString("api_name:${entry.id}",modelLabel(entry.id)));saveApiDraft();refreshApiIfChanged(true)}}}
        if(running){box.addView(text(UiText.get(R.string.ui_model_id_name, (info.optString("model_id",model)), (info.optString("model_name",modelLabel(model))), (info.getString("url"))),14f).apply {setTextIsSelectable(true)})
            box.addView(button(UiText.get(R.string.ui_copy_base_url)){copyText("llamur API URL",info.getString("url"))});box.addView(button(UiText.get(R.string.ui_copy_bearer_token)){copyText("llamur API token",info.getString("token"),true)})
            if(info.optBoolean("lan")){box.addView(text("TLS SHA256: ${info.getString("fingerprint")}",11f));box.addView(button(UiText.get(R.string.ui_export_certificate)){startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/x-pem-file").putExtra(Intent.EXTRA_TITLE,"llamur-api.pem"),3)})}
            box.addView(button(UiText.get(R.string.ui_stop_api)){product(JSONObject().put("action","api_stop")){r->checked(r){refreshApiIfChanged(true)}}})
        }else {
            box.addView(button(if(model==entry.id && runtimeState=="ready")UiText.get(R.string.ui_loaded_model_profile)else UiText.get(R.string.ui_load_selected_model)){configure(entry)})
            box.addView(button(UiText.get(R.string.ui_start_api)) {safely {check(model==entry.id && runtimeState=="ready" && !composeBusy()){ UiText.get(R.string.ui_load_the_selected_model_and_wait_until) };val id=publicId.text.toString().trim();val title=name.text.toString().trim();ProviderStore.validModel(id);require(title.length in 1..100 && title.none {it<' '}){UiText.get(R.string.ui_enter_a_name_up_to_100_characters)};val p=port.text.toString().toInt();require(p in 1024..65535)
                prefs.edit().putString("api_selected_model",entry.id).putString("api_id:${entry.id}",id).putString("api_name:${entry.id}",title).putInt("api_port",p).putBoolean("api_lan",lan.isChecked).apply()
                product(JSONObject().put("action","api_start").put("model",entry.id).put("model_id",id).put("model_name",title).put("lan",lan.isChecked).put("port",p)){r->checked(r){refreshApiIfChanged(true)}}
            }}.apply {isEnabled=model==entry.id && runtimeState=="ready" && !composeBusy()})
        }
        box.addView(text(UiText.get(R.string.ui_loopback_http_bearer_wi_fi_tls_and),12f).apply {setTextColor(mutedColor)})
        return ScrollView(this).apply {addView(box)}
    }

    private var modelListRefresh:(()->Unit)?=null
    private fun showModels() {
        val box=panel()
        fun refresh() {
            val entries=ModelStore.entries(this);box.removeAllViews()
            box.addView(text(UiText.get(R.string.ui_connect_an_existing_gguf_or_download_a),14f))
            box.addView(settingRow(UiText.get(R.string.ui_import_gguf),UiText.get(R.string.ui_choose_a_file_on_device),AppIcon.FOLDER){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {type="*/*";addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},1)})
            box.addView(settingRow("Hugging Face",UiText.get(R.string.ui_download_gguf_from_a_repository),AppIcon.SERVER){showDownload()},LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
            box.addView(settingRow(UiText.get(R.string.ui_storage_manager),UiText.get(R.string.ui_files_and_incomplete_downloads),AppIcon.FOLDER){showStorage()},LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
            if(entries.isEmpty())box.addView(text(UiText.get(R.string.ui_no_connected_models_yet),17f))
            for(entry in entries)box.addView(settingRow(entry.label,if(entry.external)UiText.get(R.string.ui_external_gguf_model_profile)else UiText.get(R.string.ui_on_device_model_profile),AppIcon.CHATS){configure(entry)},LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
        }
        modelListRefresh={if(box.isAttachedToWindow && box.isShown)refresh()}
        pages.open(UiText.get(R.string.ui_models_on_device),ScrollView(this).apply {addView(box)},onResume={refresh()});refresh()
    }
    private fun showStorage() {
        if(ModelStore.transferState().second){notice(UiText.get(R.string.ui_a_model_operation_is_in_progress_cancel));return}
        val box=panel()
        fun refresh() {
            box.removeAllViews();val entries=ModelStore.entries(this);val partials=ModelStore.partials(this)
            box.addView(text(UiText.get(R.string.ui_import_sources_are_kept_disconnecting_an_external),14f))
            for(entry in entries)box.addView(settingRow(entry.label,if(entry.external)UiText.get(R.string.ui_external_file_disconnect)else UiText.get(R.string.ui_managed_model_delete),AppIcon.TRASH){
                AlertDialog.Builder(this).setTitle(if(entry.external)UiText.get(R.string.ui_disconnect_external_file)else UiText.get(R.string.ui_delete_model)).setMessage(entry.label+if(entry.external)UiText.get(R.string.ui_the_source_file_will_remain)else UiText.get(R.string.ui_the_managed_copy_will_be_deleted_the))
                    .setPositiveButton(if(entry.external)UiText.get(R.string.ui_disconnect)else UiText.get(R.string.ui_delete)){_,_->product(JSONObject().put("action",if(entry.external)"forget_model"else "delete_model").put("model",entry.id)){r->checked(r){refresh()}}}.setNegativeButton(UiText.get(R.string.ui_keep),null).show()
            },LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
            for(partial in partials)box.addView(settingRow(UiText.get(R.string.ui_incomplete_download),android.text.format.Formatter.formatShortFileSize(this,partial.second),AppIcon.TRASH){
                AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_delete_incomplete_download)).setMessage(UiText.get(R.string.ui_you_will_not_be_able_to_resume)).setPositiveButton(UiText.get(R.string.ui_delete)){_,_->safely {ModelStore.discardPartial(this,partial.first);refresh()}}.setNegativeButton(UiText.get(R.string.ui_keep),null).show()
            },LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
            if(entries.isEmpty() && partials.isEmpty())box.addView(text(UiText.get(R.string.ui_no_model_files_or_incomplete_downloads),16f))
        }
        pages.open(UiText.get(R.string.ui_device_storage),ScrollView(this).apply {addView(box)},onResume={refresh()});refresh()
    }
    private fun configure(entry: ModelStore.Entry) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        box.addView(text(entry.label, 14f))
        val points = intArrayOf(2048, 4096, 8192, 16384, 32768)
        var selected = points.indexOf(prefs.getInt("context:${entry.id}", 4096)).coerceAtLeast(0)
        val caption = text(""); box.addView(caption)
        fun warning() { caption.text = UiText.get(R.string.ui_context_tokens_history_response, (points[selected])) + when {
            points[selected] >= 16384 -> UiText.get(R.string.ui_experimental_the_first_response_can_take_tens)
            points[selected] >= 8192 -> UiText.get(R.string.ui_long_input_takes_several_minutes_to_process)
            else -> UiText.get(R.string.ui_cpu_f16_kv_original_weights_no_history)
        } }
        warning()
        box.addView(SeekBar(this).apply { minimumHeight=dp(48); max = points.lastIndex; progress = selected; contentDescription = UiText.get(R.string.ui_context_size)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, n: Int, user: Boolean) { selected = n; warning() }
                override fun onStartTrackingTouch(bar: SeekBar?) {}; override fun onStopTrackingTouch(bar: SeekBar?) {}
            }) })
        val output = field(UiText.get(R.string.ui_response_reserve_tokens),prefs.getInt("output:${entry.id}",2048).toString()).apply {inputType=2};box.addView(output)
        val transient = CheckBox(this).apply { minimumHeight=dp(56); text = UiText.get(R.string.ui_experimental_prefill_acceleration_two_verified_models); isChecked = prefs.getBoolean("transient:${entry.id}", false) }; box.addView(transient)
        val cache = CheckBox(this).apply { minimumHeight=dp(56); text = UiText.get(R.string.ui_chat_prefix_cache_in_ram_128_mib); isChecked = prefs.getBoolean("cache:${entry.id}", false) }; box.addView(cache)
        box.addView(button(UiText.get(R.string.ui_load)) {
            val reserve = output.text.toString().toInt(); require(reserve in 1 until points[selected]) { UiText.get(R.string.ui_response_reserve_must_be_smaller_than_context) }
            require(!cache.isChecked || points[selected] <= 8192) { UiText.get(R.string.ui_verified_cache_works_only_up_to_8k) }
            if(composeBusy())error(UiText.get(R.string.ui_wait_for_the_current_operation_to_finish))
            prefs.edit().putInt("context:${entry.id}", points[selected]).putInt("output:${entry.id}", reserve)
                .putBoolean("transient:${entry.id}", transient.isChecked).putBoolean("cache:${entry.id}", cache.isChecked).apply()
            startForegroundService(Intent(this, RuntimeService::class.java).setAction(RuntimeService.ACTION_SESSION))
            bindRuntime()
            command(RuntimeService.LOAD, Bundle().apply { putString("model", entry.id); putString("config", JSONObject()
                .put("backend", "cpu").put("threads", 4).put("context", points[selected]).put("batch", 512).put("micro_batch", 128)
                .put("load_mode", "mmap").put("mmap_prefault", false).put("weight_buffers", "native").put("swa_full", true)
                .put("transient_repack", transient.isChecked).put("prefix_cache_mib", if (cache.isChecked) 128 else 0).toString()) })
            pages.back()
        })
        box.addView(button(UiText.get(R.string.ui_unload)) {command(RuntimeService.UNLOAD)})
        pages.open(UiText.get(R.string.ui_model_profile),ScrollView(this).apply {addView(box)})
    }
    private fun explainNotifications() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_notify_when_a_response_is_ready)).setMessage(UiText.get(R.string.ui_you_can_minimize_the_app_or_turn))
                .setPositiveButton(UiText.get(R.string.ui_allow)) {_,_->requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),2)}.setNegativeButton(UiText.get(R.string.ui_not_now),null).show()
        else startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))
    }
    private fun showSettings(){showTab(2)}
    private fun noticeText(title: String, view: View) { AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(view) }).setPositiveButton(UiText.get(R.string.ui_done), null).show() }
    private fun showDownload() { DownloadUi.show(this,pages,style) }
    private var certificateDestination: android.net.Uri? = null
    private fun exportCertificate() {
        val uri=certificateDestination ?: return
        if(service==null)return
        certificateDestination=null
        product(JSONObject().put("action","api_info")) {r->
            if(!r.optBoolean("ok") || !r.optBoolean("running") || !r.optBoolean("lan")) {notice(UiText.get(R.string.ui_https_server_is_already_off_turn_it));return@product}
            val pem=r.getString("certificate")
            Thread {val result=runCatching {contentResolver.openOutputStream(uri,"wt")!!.use {it.write(pem.toByteArray(Charsets.US_ASCII))}}
                main.post {if(!isDestroyed)notice(if(result.isSuccess)UiText.get(R.string.ui_certificate_saved_configure_the_client_to_trust) else UiText.get(R.string.ui_could_not_save_certificate))}
            }.start()
        }
    }
    private fun showApi() {
        product(JSONObject().put("action", "api_info")) { r -> checked(r) {
            if (r.optBoolean("running")) {
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
                box.addView(text(r.getString("url")).apply { setTextIsSelectable(true) })
                box.addView(button(UiText.get(R.string.ui_copy_base_url)) {
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("llamur API base URL", r.getString("url")))
                    Toast.makeText(this, UiText.get(R.string.ui_api_address_copied), Toast.LENGTH_SHORT).show()
                })
                box.addView(text(UiText.get(R.string.ui_bearer_token_access_to_your_model)))
                box.addView(button(UiText.get(R.string.ui_copy_token)) { val clip=ClipData.newPlainText("llamur API token",r.getString("token"))
                    clip.description.extras=PersistableBundle().apply {putBoolean("android.content.extra.IS_SENSITIVE",true)}
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip) })
                if (r.optBoolean("lan")) {
                    box.addView(text("TLS SHA-256: " + r.getString("fingerprint"), 12f).apply { setTextIsSelectable(true) })
                    box.addView(button(UiText.get(R.string.ui_export_certificate_for_client)) {
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/x-pem-file").putExtra(Intent.EXTRA_TITLE, "llamur-api.pem"), 3)
                    })
                }
                box.addView(text(UiText.get(R.string.ui_chats_share_one_engine_request_text_is), 13f))
                AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_api_enabled)).setView(ScrollView(this).apply { addView(box) })
                    .setPositiveButton(UiText.get(R.string.ui_stop_api)) { _, _ -> product(JSONObject().put("action", "api_stop")) { c -> checked(c) { status.text = UiText.get(R.string.ui_api_disabled) } } }
                    .setNegativeButton(UiText.get(R.string.ui_back), null).show()
            } else {
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
                val lan = CheckBox(this).apply { text = UiText.get(R.string.ui_computer_access_via_wi_fi_https_token) }; box.addView(lan)
                box.addView(text(UiText.get(R.string.ui_default_127_0_0_1_8080_http), 13f))
                AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_local_api)).setView(box).setPositiveButton(UiText.get(R.string.ui_enable)) { _, _ ->
                    product(JSONObject().put("action", "api_start").put("lan", lan.isChecked).put("port", 8080)) { c -> checked(c) { showApi() } }
                }.setNegativeButton(UiText.get(R.string.ui_back), null).show()
            }
        } }
    }
    // Framework retains this object in RAM only; input never enters a Bundle.
    override fun onRetainNonConfigurationInstance():Any = ComposerState(chatId,input.text.toString(),screen,currentTab,home.folder,home.searchText,apiDraft?.toString(),composerFiles.toString(),exportFile?.toString())
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("attachment_kind",attachmentKind);outState.putString("attachment_chat",attachmentChat);outState.putString("attachment_model",attachmentModel);outState.putString("chat_id",chatId);outState.putString("screen",screen);outState.putInt("tab",currentTab);outState.putString("folder",home.folder);super.onSaveInstanceState(outState) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); pendingAnswer = intent.getStringExtra(AnswerNotifications.EXTRA); if (service != null) openPendingAnswer() }
    override fun onResume() { super.onResume(); visible = true; if(service!=null){register();if(screen=="chat")refreshChat()else refreshCatalog()}
        ModelStore.observe { message, busy -> status.text = message; if (!busy) {modelListRefresh?.invoke();ModelStore.pendingDeletionSource()?.let { source -> offerDeleteSource(source) }} }
    }
    override fun onPause() { saveDraft.run(); visible = false; if (service != null) register(); ModelStore.observe(null); super.onPause() }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); if (service != null) runCatching { command(RuntimeService.UNREGISTER) }; if (bound) unbindService(connection); callbacks.clear(); super.onDestroy() }
    private var importedSource: android.net.Uri? = null
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode==10 && resultCode==RESULT_OK)data?.data?.let {uri->
            val target=attachmentChat;val selectedModel=attachmentModel;val kind=attachmentKind;val source=remote()?.let {JSONObject(it.toString())};attachmentLoading=true;syncSource()
            Thread {val result=runCatching {ChatFiles.read(applicationContext,uri,kind)};main.post {
                attachmentLoading=false;if(isFinishing || isDestroyed)return@post
                result.fold({file->safely {require(chatId==target && current.optString("model")==selectedModel){UiText.get(R.string.ui_model_or_chat_changed_choose_the_attachment)};val files=JSONArray(pendingFiles().toString()).put(file);ChatFiles.validate(files,source);val next=JSONObject(composerFiles.toString()).put(target,files);require(next.toString().toByteArray().size<=6*1024*1024){UiText.get(R.string.ui_attachment_drafts_use_6_mib_send_or)};composerFiles=next;renderFileChips();countDraft()}},{notice(it.message?:UiText.get(R.string.ui_could_not_read_file))});syncSource()
            }}.start()
        }
        if(requestCode==11){val file=exportFile;exportFile=null;if(resultCode==RESULT_OK && file!=null)data?.data?.let {uri->Thread {val result=runCatching {contentResolver.openOutputStream(uri,"wt")?.use {it.write(ChatFiles.bytes(file))}?:error(UiText.get(R.string.ui_file_unavailable))};main.post {if(!isFinishing)result.fold({Toast.makeText(this,UiText.get(R.string.ui_file_saved),Toast.LENGTH_SHORT).show()},{notice(UiText.get(R.string.ui_could_not_save_file))})}}.start()}}
        if(requestCode==3 && resultCode==RESULT_OK) { certificateDestination=data?.data;exportCertificate() }
        if (requestCode == 1 && resultCode == RESULT_OK) data?.data?.let { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if(data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            importedSource = uri
            Thread {
                val info=runCatching {
                    var name=UiText.get(R.string.ui_selected_document);var bytes:Long?=null
                    contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE),null,null,null)?.use {c->
                        if(c.moveToFirst()) {val n=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);val b=c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if(n>=0 && !c.isNull(n))name=c.getString(n).take(200);if(b>=0 && !c.isNull(b))bytes=c.getLong(b)}
                    };name to bytes
                }
                main.post { if(!isFinishing && !isDestroyed) info.fold({(name,bytes)->
                    val size=bytes?.let {android.text.format.Formatter.formatFileSize(this,it)}?:UiText.get(R.string.ui_size_unknown)
                    AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_import_model))
                        .setMessage(UiText.get(R.string.ui_an_independent_copy_will_be_created_in, (name), (size), (if(Build.VERSION.SDK_INT>=29)"Downloads/llamur/Models" else UiText.get(R.string.ui_app_storage))))
                        .setPositiveButton(UiText.get(R.string.ui_copy)) {_,_->safely {ModelTransferService.import(this,uri)}}
                        .setNeutralButton(UiText.get(R.string.ui_without_copying)) {_,_->AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_use_in_place))
                            .setMessage(UiText.get(R.string.ui_permanent_access_to_a_seekable_gguf_is))
                            .setPositiveButton(UiText.get(R.string.ui_connect)) {_,_->safely {ModelTransferService.attach(this,uri)}}.setNegativeButton(UiText.get(R.string.ui_back),null).show()}
                        .setNegativeButton(UiText.get(R.string.ui_back),null).show()
                },{failure->notice(failure.message?:UiText.get(R.string.ui_could_not_read_document))}) }
            }.start()
        }
    }
    private var deletionDialog = false
    private fun offerDeleteSource(uri: android.net.Uri) {
        if (deletionDialog) return
        if (!ModelStore.safeToOfferSourceDeletion(this, uri)) {
            ModelStore.clearDeletionOffer()
            if(!ModelStore.registeredUri(this,uri)) notice(UiText.get(R.string.ui_model_copy_verified_the_source_document_provider))
            return
        }
        if(runtimeState!="unloaded") {
            deletionDialog=true
            AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_copy_verified)).setMessage(UiText.get(R.string.ui_stop_the_current_operation_and_unload_the))
                .setPositiveButton(UiText.get(R.string.ui_unload)) {_,_->command(RuntimeService.UNLOAD)}.setNegativeButton(UiText.get(R.string.ui_keep)) {_,_->ModelStore.clearDeletionOffer()}
                .setOnDismissListener {deletionDialog=false}.show();return
        }
        deletionDialog = true
        AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_import_verified)).setMessage(UiText.get(R.string.ui_the_model_copy_passed_another_sha_256, (ModelStore.deletionLabel(uri))))
            .setPositiveButton(UiText.get(R.string.ui_delete_source)) { _, _ ->
                val sha=ModelStore.deletionDigest(uri);ModelStore.clearDeletionOffer()
                if(sha!=null) {
                    status.text=UiText.get(R.string.ui_checking_source_integrity_before_deletion)
                    Thread {val result=runCatching {ModelStore.deleteVerifiedOriginal(this,uri,sha)}
                        main.post {if(!isDestroyed) {status.text=if(result.isSuccess)UiText.get(R.string.ui_source_deleted_verified_copy_kept) else result.exceptionOrNull()?.message}}
                    }.start()
                }
            }
            .setNegativeButton(UiText.get(R.string.ui_keep)) { _, _ -> ModelStore.clearDeletionOffer() }.setOnDismissListener { deletionDialog = false }.show()
    }
}
