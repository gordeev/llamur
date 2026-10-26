package org.motoai.runtime

import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.widget.*

/** Shared styling for the remaining short action menus; standard dialog dismissal/callbacks stay intact. */
internal fun AlertDialog.Builder.setMenuItems(items:Array<out CharSequence>,choose:DialogInterface.OnClickListener):AlertDialog.Builder {
    val ctx=context;val value=TypedValue();ctx.theme.resolveAttribute(android.R.attr.colorBackground,value,true)
    val style=MessengerStyle(value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT && Color.luminance(value.data)<0.5)
    fun dp(n:Int)=(n*ctx.resources.displayMetrics.density).toInt()
    return setAdapter(object:ArrayAdapter<CharSequence>(ctx,android.R.layout.simple_list_item_1,items.toList()) {
        override fun getView(position:Int,recycled:View?,parent:ViewGroup):View {
            val row=recycled as? LinearLayout?:LinearLayout(ctx).apply {orientation=LinearLayout.VERTICAL;background=style.ripple(ctx,style.surface,0f)
                addView(TextView(ctx).apply {setTextColor(style.text);textSize=16f;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(58);setPadding(dp(24),dp(14),dp(24),dp(14));maxLines=2;ellipsize=TextUtils.TruncateAt.END;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(-1,-2))
                addView(View(ctx).apply {setBackgroundColor(style.outline)},LinearLayout.LayoutParams(-1,dp(1)).apply {marginStart=dp(24);marginEnd=dp(24)})
            }
            (row.getChildAt(0) as TextView).text=getItem(position);row.contentDescription=getItem(position);row.getChildAt(1).visibility=if(position==count-1)View.GONE else View.VISIBLE
            return row
        }
    },choose)
}
