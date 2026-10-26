package org.motoai.runtime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** Native recycled rows, folder gestures and three-tab shell. Contains no backend or disk owner. */
internal class MessengerHome(context:Context,private val style:MessengerStyle,private val modelName:(String)->String,
    private val open:(String)->Unit,private val create:()->Unit,private val tab:(Int)->Unit,
    private val manageFolder:(String?)->Unit,private val chatMenu:(String)->Unit,private val navigationChanged:()->Unit={}):FrameLayout(context) {
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun label(value:String,size:Float,color:Int=style.text)=TextView(context).apply {text=value;textSize=size;setTextColor(color);isSaveEnabled=false}
    private val main=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
    private val heading=label(UiText.get(R.string.ui_chats_8f3146),25f).apply {typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)}
    private val query=EditText(context).apply {hint=UiText.get(R.string.ui_search_chats);setTextColor(style.text);setHintTextColor(style.muted);textSize=17f;isSingleLine=true;background=null;setPadding(0,0,0,0);isSaveEnabled=false;importantForAutofill=IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS;imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;visibility=GONE}
    private val search=IconButton(context,AppIcon.SEARCH,UiText.get(R.string.ui_search_chats),style).apply {setOnClickListener {if(searching)closeSearch()else openSearch()}}
    private val foldersScroll=HorizontalScrollView(context).apply {isHorizontalScrollBarEnabled=false}
    private val foldersBar=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(12),dp(8))}
    private val body=FrameLayout(context)
    private val list=ListView(context).apply {divider=null;isVerticalScrollBarEnabled=false;clipToPadding=false;setPadding(0,0,0,dp(80));selector=style.ripple(context,Color.TRANSPARENT,0f)}
    private val empty=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(28),dp(12),dp(28),dp(80))}
    private val emptyTitle=label(UiText.get(R.string.ui_no_chats_yet),23f).apply {typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);gravity=Gravity.CENTER}
    private val emptyText=label(UiText.get(R.string.ui_start_a_conversation_with_a_model_on),15f,style.muted).apply {gravity=Gravity.CENTER;setPadding(0,dp(12),0,dp(22))}
    private val navigation=LinearLayout(context).apply {gravity=Gravity.CENTER;setPadding(dp(12),dp(8),dp(12),dp(8))}
    private val fab=IconButton(context,AppIcon.PLUS,UiText.get(R.string.ui_new_chat),style,0xff249fe6.toInt(),Color.WHITE).apply {elevation=dp(6).toFloat();setOnClickListener {create()}}
    private var custom:View?=null
    private var all=emptyList<JSONObject>();private var shown=emptyList<JSONObject>();private var groups=emptyList<JSONObject>()
    private var folderSignature="\u0000";private var selectedTab=0
    var folder="";private set
    var searching=false;private set
    val searchText get()=query.text.toString()
    private data class Row(val avatar:AiAvatar,val title:TextView,val preview:TextView,val time:TextView,val kind:TextView)
    private val adapter=object:BaseAdapter() {
        override fun getCount()=shown.size
        override fun getItem(position:Int)=shown[position]
        override fun getItemId(position:Int)=shown.getOrNull(position)?.let {UUID.fromString(it.getString("id")).mostSignificantBits}?:AdapterView.INVALID_ROW_ID
        override fun hasStableIds()=true
        override fun getView(position:Int,convert:View?,parent:ViewGroup):View {
            val row=convert as? LinearLayout ?: LinearLayout(context).apply {
                orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(82);setPadding(dp(16),dp(10),dp(16),dp(10));background=style.ripple(context,style.background,0f)
                val avatar=AiAvatar(context,"AI",style);addView(avatar,LinearLayout.LayoutParams(dp(54),dp(54)).apply {marginEnd=dp(12)})
                val middle=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL};val title=label("",16.5f).apply {typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);maxLines=1;ellipsize=TextUtils.TruncateAt.END};val preview=label("",14f,style.muted).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END;setPadding(0,dp(6),0,0)};middle.addView(title);middle.addView(preview);addView(middle,LinearLayout.LayoutParams(0,-2,1f))
                val right=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.END};val time=label("",11f,style.muted);val kind=label("",10f,style.muted).apply {setPadding(0,dp(8),0,0)};right.addView(time);right.addView(kind);addView(right,LinearLayout.LayoutParams(-2,-2).apply {marginStart=dp(10)})
                tag=Row(avatar,title,preview,time,kind);isSaveEnabled=false
            }
            val c=shown[position];val views=row.tag as Row;val remote=c.optJSONObject("remote");val source=remote?.optString("model")?:modelName(c.optString("model"));views.avatar.bind(source.ifEmpty {"AI"},c.optString("model"));views.title.text=c.getString("title")
            views.preview.text=if(c.optBoolean("active"))UiText.get(R.string.ui_responding_7ec809)else c.optString("preview").ifBlank {if(remote!=null)UiText.get(R.string.ui_api_256236, (source))else UiText.get(R.string.ui_on_device, (source))};views.preview.setTextColor(if(c.optBoolean("active"))style.accent else style.muted)
            val updated=c.optLong("updated_at");val today=SimpleDateFormat("yyyyMMdd",Locale.ROOT);views.time.text=SimpleDateFormat(if(today.format(Date(updated))==today.format(Date()))"HH:mm"else "d MMM",UiText.locale()).format(Date(updated))
            views.kind.text=if(c.optString("save_error").isNotEmpty())UiText.get(R.string.ui_not_saved)else if(c.optBoolean("permanent"))UiText.get(R.string.ui_permanent_a3d227)else UiText.get(R.string.ui_temporary_b48314);views.kind.setTextColor(if(c.optString("save_error").isNotEmpty())0xffe7a34c.toInt()else style.muted)
            row.setOnClickListener {open(c.getString("id"))};row.setOnLongClickListener {chatMenu(c.getString("id"));true};return row
        }
    }
    init {
        setBackgroundColor(style.background)
        val top=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(20),dp(4),dp(8),dp(4))}
        val titleBox=FrameLayout(context);titleBox.addView(heading,LayoutParams(-1,-2,Gravity.CENTER_VERTICAL));titleBox.addView(query,LayoutParams(-1,dp(48),Gravity.CENTER_VERTICAL));top.addView(titleBox,LinearLayout.LayoutParams(0,dp(56),1f));top.addView(search,LinearLayout.LayoutParams(dp(48),dp(48)));top.addView(IconButton(context,AppIcon.MORE,UiText.get(R.string.ui_chat_folders),style).apply {setOnClickListener {manageFolder(null)}},LinearLayout.LayoutParams(dp(48),dp(48)))
        main.addView(top);foldersScroll.addView(foldersBar);main.addView(foldersScroll,LinearLayout.LayoutParams(-1,dp(52)))
        list.adapter=adapter;body.addView(list,LayoutParams(-1,-1));empty.addView(AiAvatar(context,"AI",style),LinearLayout.LayoutParams(dp(76),dp(76)).apply {bottomMargin=dp(20)});empty.addView(emptyTitle);empty.addView(emptyText)
        empty.addView(Button(context).apply {text=UiText.get(R.string.ui_create_new_chat);isAllCaps=false;textSize=15f;setTextColor(Color.WHITE);backgroundTintList=null;background=style.ripple(context,0xff249fe6.toInt(),24f);setPadding(dp(24),0,dp(24),0);setOnClickListener {create()};contentDescription=UiText.get(R.string.ui_create_new_chat)},LinearLayout.LayoutParams(-2,dp(50)))
        body.addView(empty,LayoutParams(-1,-1));main.addView(body,LinearLayout.LayoutParams(-1,0,1f));main.addView(navigation,LinearLayout.LayoutParams(-1,dp(80)));addView(main,LayoutParams(-1,-1));addView(fab,LayoutParams(dp(58),dp(58),Gravity.BOTTOM or Gravity.END).apply {marginEnd=dp(20);bottomMargin=dp(96)})
        query.addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){filter()};override fun afterTextChanged(s:Editable?) {}})
        val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){override fun onFling(a:MotionEvent?,b:MotionEvent,vx:Float,vy:Float):Boolean {if(a==null)return false;val dx=b.x-a.x;val dy=b.y-a.y;if(kotlin.math.abs(dx)<dp(70) || kotlin.math.abs(dx)<kotlin.math.abs(dy)*1.5)return false;val ids=listOf("")+groups.map {it.getString("id")};val next=(ids.indexOf(folder)+(if(dx<0)1 else -1)).coerceIn(0,ids.lastIndex);selectFolder(ids[next]);list.isPressed=false;return true}})
        list.setOnTouchListener {_,event->gestures.onTouchEvent(event)};empty.setOnTouchListener {_,event->gestures.onTouchEvent(event);true};renderNavigation();renderFolders();filter()
    }
    fun openSearch(){if(selectedTab!=0)return;searching=true;heading.visibility=GONE;query.visibility=VISIBLE;search.icon=AppIcon.CLOSE;search.contentDescription=UiText.get(R.string.ui_close_search);search.invalidate();navigationChanged();query.requestFocus();query.post {(context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(query,InputMethodManager.SHOW_IMPLICIT)}}
    fun closeSearch(){searching=false;navigationChanged();query.setText("");query.clearFocus();query.visibility=VISIBLE;heading.visibility=GONE;search.icon=AppIcon.SEARCH;search.contentDescription=UiText.get(R.string.ui_search_chats);search.invalidate();(context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(query.windowToken,0)}
    fun restore(folder:String,search:String){this.folder=folder;query.setText(search);searching=search.isNotEmpty();if(searching){heading.visibility=GONE;query.visibility=VISIBLE;this.search.icon=AppIcon.CLOSE};renderFolders();filter()}
    fun render(rows:JSONArray,history:JSONObject){all=(0 until rows.length()).map {rows.getJSONObject(it)};val f=history.optJSONArray("folders")?:JSONArray();groups=(0 until f.length()).map {f.getJSONObject(it)};if(folder.isNotEmpty() && groups.none {it.getString("id")==folder})folder="";renderFolders();filter()}
    private fun selectFolder(id:String){folder=id;renderFolders();filter();if(shown.isNotEmpty())list.setSelection(0)}
    private fun renderFolders() {
        val signature=groups.joinToString {it.toString()}+folder;if(signature==folderSignature)return;folderSignature=signature;foldersBar.removeAllViews()
        val tabs=listOf(JSONObject().put("id","").put("name",UiText.get(R.string.ui_all)))+groups
        for(f in tabs){val id=f.getString("id");val selected=id==folder;val button=Button(context).apply {text=f.getString("name");isAllCaps=false;textSize=14f;minWidth=0;minimumWidth=0;setPadding(dp(17),0,dp(17),0);setTextColor(if(selected)style.accent else style.muted);backgroundTintList=null;background=style.ripple(context,if(selected)style.selected else Color.TRANSPARENT,20f);isSelected=selected;setOnClickListener {selectFolder(id)};if(id.isNotEmpty())setOnLongClickListener {manageFolder(id);true}}
            foldersBar.addView(button,LinearLayout.LayoutParams(-2,dp(40)).apply {marginEnd=dp(4)})
            if(selected)button.post {foldersScroll.smoothScrollTo((button.left-dp(12)).coerceAtLeast(0),0)}
        }
        foldersBar.addView(IconButton(context,AppIcon.PLUS,UiText.get(R.string.ui_create_folder),style,ink=style.muted).apply {setOnClickListener {manageFolder("")}},LinearLayout.LayoutParams(dp(40),dp(40)))
    }
    private fun filter() {
        val q=query.text.toString().trim();shown=all.filter {(folder.isEmpty() || it.optString("folder_id")==folder) && (q.isEmpty() || (it.optString("title")+" "+it.optString("preview")+" "+it.optString("model")).contains(q,true))}.sortedByDescending {it.optLong("updated_at")}
        adapter.notifyDataSetChanged();if(selectedTab==0){empty.visibility=if(shown.isEmpty())VISIBLE else GONE;list.visibility=if(shown.isEmpty())GONE else VISIBLE}
        emptyTitle.text=when {q.isNotEmpty()->UiText.get(R.string.ui_nothing_found);folder.isNotEmpty()->UiText.get(R.string.ui_this_folder_is_empty);else->UiText.get(R.string.ui_no_chats_yet)};emptyText.text=if(q.isNotEmpty())UiText.get(R.string.ui_try_another_name_or_start_a_new)else UiText.get(R.string.ui_choose_a_model_and_start_a_conversation)
    }
    fun showTab(index:Int,page:View?=null) {
        selectedTab=index;custom?.let {body.removeView(it)};custom=page
        if(page!=null)body.addView(page,LayoutParams(-1,-1))
        foldersScroll.visibility=if(index==0)VISIBLE else GONE;fab.visibility=if(index==0)VISIBLE else GONE;search.visibility=if(index==0)VISIBLE else GONE
        heading.text=when(index){1->UiText.get(R.string.ui_local_api);2->UiText.get(R.string.ui_settings);else->UiText.get(R.string.ui_chats_8f3146)};heading.visibility=if(index==0)GONE else VISIBLE;query.visibility=if(index==0)VISIBLE else GONE
        if(index!=0){list.visibility=GONE;empty.visibility=GONE}else filter();renderNavigation()
    }
    private fun renderNavigation(){navigation.removeAllViews();val bar=LinearLayout(context).apply {gravity=Gravity.CENTER;setPadding(dp(6),dp(4),dp(6),dp(4));background=style.shape(context,style.surface,28f);elevation=dp(2).toFloat()}
        val labels=listOf(UiText.get(R.string.ui_chats_8f3146),UiText.get(R.string.ui_local_api),UiText.get(R.string.ui_settings));val icons=listOf(AppIcon.CHATS,AppIcon.SERVER,AppIcon.SETTINGS)
        labels.forEachIndexed {i,name->val selected=selectedTab==i;val item=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;background=style.ripple(context,if(selected)style.selected else Color.TRANSPARENT,24f);contentDescription=name;isSelected=selected;isFocusable=true;setOnClickListener {tab(i)}}
            item.addView(IconButton(context,icons[i],name,style,ink=if(selected)style.accent else style.muted).apply {isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(36),dp(30)));item.addView(label(name,11f,if(selected)style.accent else style.muted).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END;gravity=Gravity.CENTER;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO});bar.addView(item,LinearLayout.LayoutParams(0,-1,1f))}
        navigation.addView(bar,LinearLayout.LayoutParams(-1,-1))
    }
}
