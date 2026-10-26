package org.motoai.runtime

import android.content.Context
import android.graphics.Typeface
import android.view.*
import android.widget.*

/** In-activity navigation: full-screen pages with a real back stack, no floating windows. */
internal class SettingsPages(private val context:Context,private val host:FrameLayout,private val style:MessengerStyle,private val changed:()->Unit) {
    private val stack=mutableListOf<View>()
    private val backdrop=mutableMapOf<View,Int>()
    private val resume=mutableMapOf<View,()->Unit>()
    var revision=0L;private set
    val showing get()=stack.isNotEmpty()
    private fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
    fun open(title:String,content:View,replace:Boolean=false,onResume:(()->Unit)?=null) {
        revision++
        if(stack.isEmpty()){for(i in 0 until host.childCount){val v=host.getChildAt(i);backdrop[v]=v.visibility;v.visibility=View.GONE}}
        if(replace && stack.isNotEmpty()){val old=stack.removeAt(stack.lastIndex);resume.remove(old);host.removeView(old)}
        stack.lastOrNull()?.visibility=View.GONE
        val page=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(style.background);isClickable=true;isFocusable=true}
        val bar=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(6),dp(4),dp(16),dp(4))}
        bar.addView(IconButton(context,AppIcon.BACK,UiText.get(R.string.ui_back),style).apply {setOnClickListener {back()}},LinearLayout.LayoutParams(dp(48),dp(52)))
        bar.addView(TextView(context).apply {text=title;textSize=20f;setTextColor(style.text);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);maxLines=2},LinearLayout.LayoutParams(0,-2,1f))
        page.addView(bar);page.addView(View(context).apply {setBackgroundColor(style.outline)},LinearLayout.LayoutParams(-1,dp(1)));page.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        stack.add(page);if(onResume!=null)resume[page]=onResume;host.addView(page,FrameLayout.LayoutParams(-1,-1));changed()
    }
    data class Option(val title:String,val description:String="",val icon:AppIcon=AppIcon.SETTINGS)
    fun choices(title:String,description:String,options:List<Option>,selected:Int=-1,replace:Boolean=false,choose:(Int)->Unit) {
        val density=context.resources.displayMetrics.density
        val body=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(24))}
        if(description.isNotEmpty())body.addView(TextView(context).apply {text=description;textSize=14f;setTextColor(style.muted);setLineSpacing(dp(3).toFloat(),1f);setPadding(dp(2),dp(4),dp(2),dp(22))})
        options.forEachIndexed {index,item->
            val chosen=index==selected
            val background=style.shape(context,if(chosen)style.selected else style.surface,18f).apply {setStroke(dp(1),if(chosen)style.accent else style.outline)}
            val row=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(16),dp(14),dp(16));minimumHeight=dp(80);this.background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(style.outline),background,null);isFocusable=true;isClickable=true;isScreenReaderFocusable=true;isSelected=chosen;setOnClickListener {choose(index)}}
            row.addView(IconButton(context,item.icon,"",style,ink=if(chosen)style.accent else style.muted).apply {isClickable=false;isFocusable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(30),dp(32)).apply {marginEnd=dp(14)})
            val labels=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
            labels.addView(TextView(context).apply {text=item.title;textSize=16f;setTextColor(style.text);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)})
            if(item.description.isNotEmpty())labels.addView(TextView(context).apply {text=item.description;textSize=13f;setTextColor(style.muted);setLineSpacing(2*density,1f);setPadding(0,dp(6),0,0)})
            row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(RadioButton(context).apply {isChecked=chosen;isClickable=false;isFocusable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;buttonTintList=android.content.res.ColorStateList.valueOf(if(chosen)style.accent else style.muted);setPadding(dp(8),0,0,0)},LinearLayout.LayoutParams(dp(40),dp(40)))
            body.addView(row,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(12)})
        }
        open(title,ScrollView(context).apply {isFillViewport=true;clipToPadding=false;addView(body)},replace)
    }
    private fun restore(){if(stack.isEmpty()){backdrop.forEach {(view,visibility)->view.visibility=visibility};backdrop.clear()}}
    fun back():Boolean {if(stack.isEmpty())return false;revision++;val old=stack.removeAt(stack.lastIndex);resume.remove(old);host.removeView(old);stack.lastOrNull()?.let {it.visibility=View.VISIBLE;resume[it]?.invoke()};restore();changed();return true}
    fun clear(){revision++;while(stack.isNotEmpty())host.removeView(stack.removeAt(stack.lastIndex));resume.clear();restore();changed()}
}
