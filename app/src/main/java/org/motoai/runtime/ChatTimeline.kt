package org.motoai.runtime

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** Recycled message bubbles. Streaming updates touch visible changed text only. */
internal class ChatTimeline(context:Context,private val style:MessengerStyle,private val format:(String)->CharSequence,private val action:(Int,View)->Unit,private val save:(JSONObject)->Unit={}):FrameLayout(context) {
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private var messages=JSONArray();private var pinned=emptySet<Int>();private var chat="";private var query="";private val expanded=mutableSetOf<Int>()
    private val list=ListView(context).apply {divider=null;isStackFromBottom=true;selector=android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT);isVerticalScrollBarEnabled=false;clipToPadding=false;setPadding(dp(10),dp(8),dp(10),dp(12));isSaveEnabled=false}
    private var empty:View?=null
    private data class Bubble(val box:LinearLayout,val heading:TextView,val thoughtToggle:TextView,val thought:TextView,val text:TextView,val files:LinearLayout,val footer:TextView,var content:String="",var reasoning:String="",var index:Int=-1,var fileSignature:String="")
    private val adapter=object:BaseAdapter() {
        override fun getCount()=messages.length()
        override fun getItem(position:Int)=messages.getJSONObject(position)
        override fun getItemId(position:Int)=position.toLong()
        override fun hasStableIds()=true
        override fun getView(position:Int,convert:View?,parent:ViewGroup):View {
            val row=convert as? LinearLayout ?: LinearLayout(context).apply {
                orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(3),0,dp(3));isSaveEnabled=false
                val box=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(13),dp(9),dp(13),dp(8));isSaveEnabled=false}
                fun text(size:Float,color:Int)=TextView(context).apply {textSize=size;setTextColor(color);isSaveEnabled=false;setLineSpacing(dp(2).toFloat(),1f)}
                val heading=text(12f,style.accent).apply {typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)};val toggle=text(12f,style.muted).apply {setPadding(0,dp(3),0,dp(7));text=UiText.get(R.string.ui_reasoning)};val thought=text(14f,style.muted).apply {setPadding(0,dp(3),0,dp(10))};val body=text(16f,style.text);val footer=text(10.5f,style.muted).apply {gravity=Gravity.END;setPadding(dp(8),dp(5),0,0)}
                val files=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL};box.addView(heading);box.addView(toggle);box.addView(thought);box.addView(body);box.addView(files);box.addView(footer);addView(box,LinearLayout.LayoutParams(-2,-2));tag=Bubble(box,heading,toggle,thought,body,files,footer)
            }
            bind(row,position);return row
        }
    }
    init {list.adapter=adapter;addView(list,LayoutParams(-1,-1));list.setOnItemLongClickListener {_,view,position,_->action(position,view);true}}
    private fun bind(row:LinearLayout,index:Int) {
        val m=messages.getJSONObject(index);val user=m.getString("role")=="user";val v=row.tag as Bubble;v.index=index;row.gravity=if(user)Gravity.END else Gravity.START
        v.box.background=style.shape(context,if(user)style.outgoing else style.incoming,18f)
        val max=(resources.displayMetrics.widthPixels*.88).toInt()-dp(32);v.text.maxWidth=max;v.thought.maxWidth=max
        v.heading.text=if(user)UiText.get(R.string.ui_you)else UiText.get(R.string.ui_ai);v.heading.visibility=if(user)GONE else VISIBLE
        val reasoning=m.optString("reasoning_content");val content=m.optString("content");val attachments=m.optJSONArray("attachments");val display=if(content.isNotEmpty())content else if(attachments!=null && attachments.length()>0)""else when(m.optString("status")){"generating"->if(reasoning.isNotEmpty())UiText.get(R.string.ui_model_is_reasoning)else UiText.get(R.string.ui_preparing_response);"length"->UiText.get(R.string.ui_output_limit_reached_before_any_response_text);"eog"->UiText.get(R.string.ui_model_completed_without_response_text);else->UiText.get(R.string.ui_response_interrupted)}
        if(v.content!=display){v.text.text=format(display);v.content=display}
        if(v.reasoning!=reasoning){v.thought.text=reasoning;v.reasoning=reasoning}
        val exports=if(m.optString("status")=="generating")(0 until (attachments?.length()?:0)).map {attachments!!.getJSONObject(it)}else ChatFiles.exports(m)
        val signature=exports.joinToString {it.optString("name")+":"+it.optString("data").hashCode()+":"+it.optString("text").hashCode()}
        if(v.fileSignature!=signature){v.fileSignature=signature;v.files.removeAllViews();for(file in exports)v.files.addView(Button(context).apply {text="↓  ${file.getString("name")}";isAllCaps=false;textSize=14f;setTextColor(style.accent);background=style.ripple(context,style.surface,12f);backgroundTintList=null;stateListAnimator=null;elevation=0f;minimumHeight=dp(48);maxWidth=max;contentDescription=UiText.get(R.string.ui_save_file, (file.getString("name")));setOnClickListener {save(file)}},LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(6)})}
        v.thoughtToggle.visibility=if(reasoning.isEmpty())GONE else VISIBLE;v.thought.visibility=if(index in expanded && reasoning.isNotEmpty())VISIBLE else GONE
        v.thoughtToggle.text=if(index in expanded)UiText.get(R.string.ui_reasoning_9cade5)else UiText.get(R.string.ui_reasoning);v.thoughtToggle.setOnClickListener {if(index in expanded)expanded.remove(index)else expanded.add(index);bind(row,index);row.requestLayout()}
        for(view in listOf(v.box,v.text,v.thought,v.heading,v.footer)){view.setOnLongClickListener {action(index,v.box);true}}
        val time=SimpleDateFormat("HH:mm",UiText.locale()).format(Date(m.optLong("created_at")))
        val status=when(m.optString("status")){"length"->UiText.get(R.string.ui_limit);"interrupted","memory_pressure","thermal_pressure"->UiText.get(R.string.ui_interrupted);"generating"->" ···";else->""}
        v.footer.text=(if(index in pinned)UiText.get(R.string.ui_pinned_f3758c)else "")+time+status
        if(query.isNotEmpty() && content.contains(query,true))v.box.background=style.shape(context,style.selected,18f)
        row.contentDescription=null
    }
    fun render(c:JSONObject) {
        val a=c.getJSONArray("messages");val newChat=c.getString("id");val changedChat=chat!=newChat;val changedCount=messages.length()!=a.length()
        val follow=changedChat || list.count==0 || list.lastVisiblePosition>=list.count-1 && (list.getChildAt(list.childCount-1)?.bottom?:0)<=list.height+dp(60)
        if(changedChat){expanded.clear();query=""};chat=newChat;messages=a;val pins=c.optJSONArray("pinned")?:JSONArray();val nextPins=(0 until pins.length()).map {pins.getInt(it)}.toSet();val pinChanged=nextPins!=pinned;pinned=nextPins
        empty?.visibility=if(a.length()==0)VISIBLE else GONE;list.visibility=if(a.length()==0)GONE else VISIBLE
        if(changedChat || changedCount || pinChanged)adapter.notifyDataSetChanged()else for(i in 0 until list.childCount){val row=list.getChildAt(i) as? LinearLayout?:continue;val at=list.firstVisiblePosition+i;if(at<a.length())bind(row,at)}
        if(follow && a.length()>0)list.post {val last=list.getChildAt(a.length()-1-list.firstVisiblePosition);if(last!=null)list.setSelectionFromTop(a.length()-1,list.height-list.paddingBottom-last.height)else list.setSelection(a.length()-1)}
    }
    fun empty(view:View){empty?.let {removeView(it)};empty=view;addView(view,LayoutParams(-1,-1));view.visibility=if(messages.length()==0)VISIBLE else GONE}
    fun search(text:String):List<Int>{query=text;adapter.notifyDataSetChanged();return (0 until messages.length()).filter {messages.getJSONObject(it).optString("content").contains(text,true)}.takeIf {text.isNotBlank()}?:emptyList()}
    fun jump(index:Int){if(index in 0 until messages.length())list.post {list.setSelectionFromTop(index,dp(8))}}
}
