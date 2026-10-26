package org.motoai.runtime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import java.util.Locale

internal data class MessengerStyle(val dark:Boolean) {
    val background=if(dark)0xff17212b.toInt()else 0xfff7f9fc.toInt()
    val surface=if(dark)0xff202e3b.toInt()else Color.WHITE
    val text=if(dark)0xffedf3f8.toInt()else 0xff182c3e.toInt()
    val muted=if(dark)0xff91a5b6.toInt()else 0xff6a7e90.toInt()
    val accent=if(dark)0xff43b3ed.toInt()else 0xff168acb.toInt()
    val outline=if(dark)0xff304252.toInt()else 0xffe0e7ef.toInt()
    val incoming=if(dark)0xff233342.toInt()else Color.WHITE
    val outgoing=if(dark)0xff2b5278.toInt()else 0xffd9efff.toInt()
    val selected=if(dark)0xff2a4256.toInt()else 0xffe1f2fc.toInt()
    fun shape(context:Context,color:Int=surface,radius:Float=18f)=GradientDrawable().apply {setColor(color);cornerRadius=radius*context.resources.displayMetrics.density}
    fun ripple(context:Context,color:Int=surface,radius:Float=24f)=RippleDrawable(ColorStateList.valueOf(if(dark)0x33ffffff else 0x22000000),shape(context,color,radius),null)
}
internal enum class AppIcon { PLUS,SEND,STOP,BACK,SEARCH,CLOSE,MORE,CHATS,SETTINGS,SERVER,PIN,FOLDER,CHEVRON,INFO,TRASH }

/** Geometric24dp icons. No text-glyph baseline or asymmetric font padding. */
internal class IconButton(context:Context,var icon:AppIcon,description:String,private val style:MessengerStyle,fill:Int=Color.TRANSPARENT,var ink:Int=style.text):Button(context) {
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply {strokeWidth=2f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    init {contentDescription=description;text="";minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setPadding(0,0,0,0);backgroundTintList=null;background=style.ripple(context,fill,32f);stateListAnimator=null;isAllCaps=false;isSaveEnabled=false}
    override fun onDraw(canvas:Canvas) {
        val save=canvas.save();canvas.translate(width/2f,height/2f);val density=resources.displayMetrics.density;canvas.scale(density,density);pen.color=ink;pen.style=Paint.Style.STROKE
        fun line(x:Float,y:Float,xx:Float,yy:Float)=canvas.drawLine(x,y,xx,yy,pen)
        fun path(vararg points:Float){val p=Path();p.moveTo(points[0],points[1]);var n=2;while(n<points.size){p.lineTo(points[n],points[n+1]);n+=2};canvas.drawPath(p,pen)}
        when(if(icon==AppIcon.SEND && text.toString()=="■")AppIcon.STOP else icon) {
            AppIcon.PLUS->{line(-8f,0f,8f,0f);line(0f,-8f,0f,8f)}
            AppIcon.SEND->{path(-9f,-8f,10f,0f,-9f,8f,-5f,0f,-9f,-8f);line(-5f,0f,10f,0f)}
            AppIcon.STOP->{pen.style=Paint.Style.FILL;canvas.drawRoundRect(-7f,-7f,7f,7f,2f,2f,pen)}
            AppIcon.BACK->{path(0f,-8f,-8f,0f,0f,8f);line(-8f,0f,9f,0f)}
            AppIcon.SEARCH->{canvas.drawCircle(-2f,-2f,7f,pen);line(3f,3f,9f,9f)}
            AppIcon.CLOSE->{line(-6f,-6f,6f,6f);line(-6f,6f,6f,-6f)}
            AppIcon.MORE->{pen.style=Paint.Style.FILL;for(y in listOf(-7f,0f,7f))canvas.drawCircle(0f,y,1.7f,pen)}
            AppIcon.CHATS->{val p=Path();p.moveTo(-5f,7f);p.lineTo(-9f,10f);p.lineTo(-8f,4f);p.cubicTo(-13f,-9f,10f,-13f,10f,-1f);p.cubicTo(10f,5f,4f,8f,-5f,7f);canvas.drawPath(p,pen)}
            AppIcon.SERVER->{for(y in listOf(-7f,1f)){canvas.drawRoundRect(-9f,y,9f,y+6f,2f,2f,pen);canvas.drawCircle(-5f,y+3f,.6f,pen)};line(3f,-4f,6f,-4f);line(3f,4f,6f,4f)}
            AppIcon.PIN->{path(-5f,-9f,5f,-9f,4f,-2f,8f,2f,-8f,2f,-4f,-2f,-5f,-9f);line(0f,2f,0f,10f)}
            AppIcon.FOLDER->{path(-10f,-5f,-10f,-8f,-3f,-8f,0f,-5f,10f,-5f,10f,8f,-10f,8f,-10f,-5f);line(-10f,-3f,10f,-3f)}
            AppIcon.CHEVRON->path(-3f,-6f,3f,0f,-3f,6f)
            AppIcon.INFO->{canvas.drawCircle(0f,0f,10f,pen);line(0f,-1f,0f,5f);pen.style=Paint.Style.FILL;canvas.drawCircle(0f,-5f,1f,pen)}
            AppIcon.TRASH->{path(-6f,-5f,-5f,9f,5f,9f,6f,-5f);line(-8f,-5f,8f,-5f);path(-3f,-5f,-3f,-9f,3f,-9f,3f,-5f);line(-2f,-1f,-2f,5f);line(2f,-1f,2f,5f)}
            AppIcon.SETTINGS->{canvas.drawCircle(0f,0f,4f,pen);canvas.drawCircle(0f,0f,8f,pen);for(n in 0..7){val a=n*Math.PI/4;line((8*kotlin.math.cos(a)).toFloat(),(8*kotlin.math.sin(a)).toFloat(),(11*kotlin.math.cos(a)).toFloat(),(11*kotlin.math.sin(a)).toFloat())}}
        }
        canvas.restoreToCount(save)
    }
}
internal class AiAvatar(context:Context,label:String,private val style:MessengerStyle,seed:String=label):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var letter=label.trim().takeIf {it.isNotEmpty()}?.let {it.substring(0,it.offsetByCodePoints(0,1)).uppercase(Locale.ROOT)}?:"AI"
    private var color=intArrayOf(0xff328cc5.toInt(),0xff6875ce.toInt(),0xff498f87.toInt(),0xff8c6db7.toInt())[Math.floorMod(seed.hashCode(),4)]
    init {isSaveEnabled=false;contentDescription=UiText.get(R.string.ui_ai_80d4ea, (label))}
    fun bind(label:String,seed:String=label){letter=label.trim().takeIf {it.isNotEmpty()}?.let {it.substring(0,it.offsetByCodePoints(0,1)).uppercase(Locale.ROOT)}?:"AI";color=intArrayOf(0xff328cc5.toInt(),0xff6875ce.toInt(),0xff498f87.toInt(),0xff8c6db7.toInt())[Math.floorMod(seed.hashCode(),4)];contentDescription=UiText.get(R.string.ui_ai_80d4ea, (label));invalidate()}
    override fun onDraw(canvas:Canvas){val cx=width/2f;val cy=height/2f;paint.color=color;canvas.drawCircle(cx,cy,minOf(cx,cy),paint);paint.color=Color.WHITE;paint.textSize=minOf(width,height)*.4f;paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);paint.textAlign=Paint.Align.CENTER;val f=paint.fontMetrics;canvas.drawText(letter,cx,cy-(f.ascent+f.descent)/2,paint)}
}
