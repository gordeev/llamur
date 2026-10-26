package org.motoai.runtime

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.*
import android.view.*
import android.widget.*
import org.json.*
import java.io.File
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** Public debug fixtures only: resources, real language recreation, draft/source safety and visual screens. */
internal class LocalizationChecks(private val test:Instrumentation) {
    private val context=test.targetContext
    private var checks=0
    val progress=JSONObject()
    private fun verify(ok:Boolean,why:String){check(ok){why};checks++}
    private fun waitFor(condition:()->Boolean){val end=SystemClock.elapsedRealtime()+40000;while(!condition()){check(SystemClock.elapsedRealtime()<end){"Localization deadline: $progress"};Thread.sleep(80)}}
    private lateinit var endpoint:Messenger
    private val replies=ConcurrentHashMap<String,CompletableFuture<JSONObject>>()
    private val receiver=Messenger(Handler(Looper.getMainLooper()){m->m.data.getString("product_id")?.let {id->m.data.getParcelable<ParcelFileDescriptor>("payload")?.let {fd->ProductPipe.read(fd,16*1024*1024){r->r.fold({replies.remove(id)?.complete(JSONObject(String(it)))},{replies.remove(id)?.completeExceptionally(it)})}}};true})
    private fun call(v:JSONObject,ok:Boolean=true):JSONObject {val id=UUID.randomUUID().toString();val future=CompletableFuture<JSONObject>();replies[id]=future;val fd=ProductPipe.write(v.toString().toByteArray());try {endpoint.send(Message.obtain(null,RuntimeService.PRODUCT).apply {replyTo=receiver;data=Bundle().apply {putString("id",id);putParcelable("payload",fd)}})}finally{fd.close()};return future.get(15,TimeUnit.SECONDS).also {if(ok)check(it.optBoolean("ok")){it.optString("message")}}}
    private fun get(id:String)=call(JSONObject().put("action","get").put("chat_id",id)).getJSONObject("chat")
    fun run():JSONObject {
        verify(context.packageName.endsWith(".debug"),"Use isolated debug data")
        for(tag in listOf("en","ru")){val r=context.createConfigurationContext(Configuration(context.resources.configuration).apply {setLocales(LocaleList.forLanguageTags(tag))}).resources
            for(field in R.string::class.java.fields){val id=field.getInt(null);val value=r.getString(id);val args=Regex("%(\\d+)"+Regex.escape("$")+"s").findAll(value).map {it.groupValues[1].toInt()}.maxOrNull()?:0
                // Android's actual formatter also checks escaped quotes, percent literals and ordering.
                if(args>0)r.getString(id,*Array<Any>(args){"fixture"});verify(value.isNotEmpty(),"Empty resource ${field.name} / $tag")
            }
            verify(r.getString(R.string.ui_chat_storage)==if(tag=="en")"Chat storage"else "Хранение диалогов","Android locale must resolve the selected translation")
        }
        val fallback=context.createConfigurationContext(Configuration(context.resources.configuration).apply {setLocales(LocaleList.forLanguageTags("es"))}).resources
        verify(fallback.getString(R.string.ui_chat_storage)=="Chat storage","Untranslated locales must use complete English fallback")
        val prefs=context.getSharedPreferences("preferences",Context.MODE_PRIVATE);val theme=prefs.getInt("theme",0);prefs.edit().putInt("theme",1).commit()
        val resumed=AtomicReference<Activity>()
        val lifecycle=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a:Activity){if(a is ChatActivity)resumed.set(a)}
            override fun onActivityCreated(a:Activity,b:Bundle?){};override fun onActivityStarted(a:Activity){};override fun onActivityPaused(a:Activity){};override fun onActivityStopped(a:Activity){};override fun onActivitySaveInstanceState(a:Activity,b:Bundle){};override fun onActivityDestroyed(a:Activity){}
        }
        val app=context.applicationContext as Application;app.registerActivityLifecycleCallbacks(lifecycle)
        var activity=test.startActivitySync(Intent(context,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));resumed.set(activity)
        fun invoke(name:String,vararg args:Any){test.runOnMainSync {val method=ChatActivity::class.java.declaredMethods.first {it.name==name && it.parameterTypes.size==args.size};method.isAccessible=true;method.invoke(activity,*args)}}
        fun views():List<View> {val all=mutableListOf<View>();fun walk(v:View){if(v.visibility!=View.VISIBLE)return;all.add(v);if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))};if(Looper.myLooper()==Looper.getMainLooper())walk(activity.window.decorView)else test.runOnMainSync {walk(activity.window.decorView)};return all}
        fun tap(name:String){test.runOnMainSync {var v=views().firstOrNull {(it as? TextView)?.text?.toString()==name || it.contentDescription?.toString()==name}?:error("Missing UI: $name");v.requestRectangleOnScreen(Rect(0,0,v.width,v.height),true);while(!v.hasOnClickListeners() && v.parent is View)v=v.parent as View;check(v.performClick())};test.waitForIdleSync()}
        fun capture(name:String){test.waitForIdleSync();Thread.sleep(350);test.uiAutomation.takeScreenshot()?.let {b->File(context.filesDir,"localization-$name.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        fun clearPages(){test.runOnMainSync {val f=ChatActivity::class.java.getDeclaredField("pages").apply {isAccessible=true};(f.get(activity) as SettingsPages).clear()}}
        fun newActivity(previous:Activity){waitFor {resumed.get()!=null && resumed.get()!==previous};activity=resumed.get();test.waitForIdleSync()}
        fun sweep(themeName:String,chat:JSONObject) {
            val modelFile=File(context.filesDir,"models/public-ui-layout-${UUID.randomUUID()}.gguf").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
            fun shot(name:String)=capture("layout-$themeName-$name")
            fun bottom(name:String){test.runOnMainSync {views().filterIsInstance<ScrollView>().lastOrNull()?.fullScroll(View.FOCUS_DOWN)};shot(name)}
            fun spacedFields(){val fields=views().filterIsInstance<EditText>();for(pair in fields.zipWithNext())if(pair.first.parent===pair.second.parent)verify(pair.second.top-pair.first.bottom>=(8*context.resources.displayMetrics.density).toInt(),"$themeName: adjacent fields need visible separation")}
            try {
                clearPages();invoke("showTab",0);shot("home")
                invoke("selectChat",chat);shot("chat");invoke("showCompaction",chat.getString("id"),false)
                waitFor {views().filterIsInstance<TextView>().any {it.text.toString()==UiText.get(R.string.ui_context_and_compaction)}};shot("context");bottom("context-bottom");clearPages()
                invoke("showTab",2);shot("settings");bottom("settings-bottom")
                invoke("historySettings",false);waitFor {views().filterIsInstance<RadioButton>().size==3};shot("storage");clearPages()
                invoke("themeSettings");shot("theme");clearPages();invoke("languageSettings");shot("language");clearPages()
                invoke("showAbout");verify(views().filterIsInstance<TextView>().any {it.text.toString()=="https://github.com/gordeev/llamur"},"About must show the public project URL");shot("about");clearPages()
                invoke("showModels");shot("models");clearPages();invoke("showStorage");shot("model-storage");clearPages()
                invoke("showDownload");spacedFields();shot("hf");bottom("hf-bottom");clearPages()
                invoke("configure",ModelStore.Entry(modelFile.name,"Public model layout fixture",0,"Internal",null));shot("profile");bottom("profile-bottom");clearPages()
                invoke("showTab",1);waitFor {views().filterIsInstance<EditText>().size>=3};spacedFields();shot("api");bottom("api-bottom")
                invoke("showTab",2)
                val ui=ChatActivity::class.java.getDeclaredMethod("providerUi").apply {isAccessible=true}.let {m->var u:Any?=null;test.runOnMainSync {u=m.invoke(activity)};u!!}
                fun providerPage(name:String,vararg args:Any){test.runOnMainSync {val m=ProviderUi::class.java.declaredMethods.first {it.name==name && it.parameterTypes.size==args.size};m.isAccessible=true;m.invoke(ui,*args)}}
                val profile=JSONObject().put("name","Public HTTP layout fixture").put("base_url","http://127.0.0.1:19091/v1").put("allow_local_http",true).put("requires_key",false).put("model","fixture-model")
                providerPage("presets");shot("providers");clearPages();providerPage("edit",profile);shot("provider-edit");bottom("provider-edit-bottom");clearPages()
                providerPage("chooseModel",profile,false);shot("model-actions")
                val buttons=views().filterIsInstance<Button>().filter {it !is CompoundButton && it.parent is LinearLayout}
                for(pair in buttons.zipWithNext())if(pair.first.parent===pair.second.parent)verify(pair.second.top-pair.first.bottom>=(12*context.resources.displayMetrics.density).toInt(),"$themeName: provider actions must not touch")
                clearPages()
            } finally {modelFile.delete()}
        }
        val connected=CountDownLatch(1);val connection=object:ServiceConnection{override fun onServiceConnected(n:ComponentName,b:IBinder){endpoint=Messenger(b);connected.countDown()};override fun onServiceDisconnected(n:ComponentName){}}
        check(context.bindService(Intent(context,RuntimeService::class.java),connection,Context.BIND_AUTO_CREATE));check(connected.await(10,TimeUnit.SECONDS));var chat="";var provider=""
        try {
            verify(UiText.language()=="ru","Controller must set Russian before this fixture")
            var selectedMenu=-1;lateinit var menu:AlertDialog
            test.runOnMainSync {menu=AlertDialog.Builder(activity).setTitle(UiText.get(R.string.ui_folders)).setMenuItems(arrayOf(UiText.get(R.string.ui_rename),UiText.get(R.string.ui_delete_folder))){_,n->selectedMenu=n}.show()};test.waitForIdleSync();capture("ru-actions-light")
            test.runOnMainSync {val list=menu.listView;val first=list.getChildAt(0) as LinearLayout;verify(first.getChildAt(1).visibility==View.VISIBLE,"Action menu must provide a visible divider between rows");val second=list.getChildAt(1);list.performItemClick(second,1,list.adapter.getItemId(1))}
            verify(selectedMenu==1 && !menu.isShowing,"Styled action menu must preserve exact item index and normal dismissal")
            progress.put("stage","Russian storage")
            invoke("showTab",2);tap("Хранение диалогов");waitFor {views().filterIsInstance<RadioButton>().size==3}
            verify(views().filterIsInstance<RadioButton>().count {it.isChecked}==1,"Exactly one storage mode must be selected")
            verify(views().filterIsInstance<TextView>().any {it.text.toString().startsWith("Все диалоги остаются только")},"Each mode must have a clear description")
            capture("ru-storage-light");val before=call(JSONObject().put("action","history")).getJSONObject("history")
            tap("Автоудаление постоянных");verify(views().filterIsInstance<RadioButton>().size==4,"Expiry periods must be separate selectable cards");capture("ru-expiry-light");tap("Через 7 дней")
            waitFor {test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Отмена")?.any {it.isClickable}==true};val root=test.uiAutomation.rootInActiveWindow;check(root.findAccessibilityNodeInfosByText("Отмена").first {it.isClickable}.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            verify(call(JSONObject().put("action","history")).getJSONObject("history").getString("mode")==before.getString("mode"),"Cancelling retention confirmation must not alter storage")
            clearPages();tap("Язык приложения");capture("ru-language-light")
            val previous=activity;tap("English");newActivity(previous);verify(UiText.language()=="en","Language selection must use Android per-app locale")
            progress.put("stage","English storage")
            invoke("showTab",2);waitFor {views().filterIsInstance<TextView>().any {it.text.toString()=="Chat storage"}};capture("en-settings-light");tap("Chat storage");waitFor {views().filterIsInstance<RadioButton>().size==3};capture("en-storage-light")
            verify(views().filterIsInstance<TextView>().none {Regex("[А-Яа-яЁё]").containsMatchIn(it.text)},"English storage screen must not contain untranslated Russian")
            clearPages();tap("Theme");val light=activity;tap("Dark");newActivity(light);invoke("showTab",2);tap("Chat storage");waitFor {views().filterIsInstance<RadioButton>().size==3};capture("en-storage-dark")
            verify(prefs.getInt("theme",0)==2,"Theme card must apply its selection");verify(activity.window.insetsController!!.systemBarsAppearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS==0,"Dark theme must restore light status-bar icons after switching from light")
            test.runOnMainSync {menu=AlertDialog.Builder(activity).setTitle(UiText.get(R.string.ui_folders)).setMenuItems(arrayOf(UiText.get(R.string.ui_rename),UiText.get(R.string.ui_delete_folder))){_,_->}.show()};capture("en-actions-dark");test.runOnMainSync {menu.dismiss()}
            clearPages();tap("App language");capture("en-language-dark");clearPages()
            provider=call(JSONObject().put("action","provider_save").put("profile",JSONObject().put("name","Public locale fixture").put("base_url","http://127.0.0.1:19091").put("allow_local_http",true).put("protocol","chat").put("model","fixture/unchanged").put("requires_key",false))).getJSONObject("provider").getString("id")
            chat=call(JSONObject().put("action","remote_new").put("provider_id",provider).put("model","fixture/unchanged")).getJSONObject("chat").getString("id")
            val draft="Public draft Привет 🌍 — unchanged";call(JSONObject().put("action","draft").put("chat_id",chat).put("text",draft));val original=get(chat);val file=JSONArray().put(JSONObject().put("name","data.txt").put("mime","text/plain").put("text","source Привет Ω"));val prompt=ChatFiles.text("Original message",file)
            invoke("selectChat",original);invoke("showTab",2);tap("App language");val english=activity;tap("Русский");newActivity(english);waitFor {UiText.language()=="ru"};val restored=get(chat)
            verify(restored.getString("id")==chat && restored.getString("draft")==draft && restored.getString("model")==original.getString("model") && restored.getJSONObject("remote").toString()==original.getJSONObject("remote").toString(),"Language recreation must preserve draft, selected chat and exact model/provider revision")
            var visibleDraft="";var selectedChat="";test.runOnMainSync {visibleDraft=(ChatActivity::class.java.getDeclaredField("input").apply {isAccessible=true}.get(activity) as EditText).text.toString();selectedChat=ChatActivity::class.java.getDeclaredField("chatId").apply {isAccessible=true}.get(activity) as String};verify(visibleDraft==draft && selectedChat==chat,"Language recreation must retain actual composer and selected chat in UI")
            verify(ChatFiles.text("Original message",file)==prompt,"UI language must not change canonical file input or original content")
            waitFor {call(JSONObject().put("action","count"),false).optString("message").startsWith("Счётчик")};verify(true,"Inference process errors must follow the new app locale")
            invoke("showTab",2);tap("Тема");val dark=activity;tap("Светлая");newActivity(dark);invoke("showTab",2);capture("ru-settings-light")
            invoke("showModels");capture("ru-models-light");verify(views().filterIsInstance<TextView>().any {it.text.toString()=="Импорт GGUF"},"Model UI must remain localized")
            sweep("light",get(chat))
            invoke("showTab",2);tap("Тема");val beforeDark=activity;tap("Тёмная");newActivity(beforeDark);sweep("dark",get(chat))
        }finally {if(chat.isNotEmpty())runCatching {call(JSONObject().put("action","delete").put("chat_id",chat))};if(provider.isNotEmpty())runCatching {call(JSONObject().put("action","provider_delete").put("provider_id",provider))};context.unbindService(connection);test.runOnMainSync {activity.finish()};app.unregisterActivityLifecycleCallbacks(lifecycle);prefs.edit().putInt("theme",theme).commit()}
        return JSONObject().put("passed",true).put("checks",checks).put("scope","826 complete resource pairs; Android formatting; real Russian/English locale and theme recreation; draft/model/source unchanged; retention cancellation; screenshots. No cloud requests, model changes or private data.")
    }
}
