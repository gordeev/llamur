package org.motoai.runtime

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.*

internal object DownloadUi {
    fun show(activity: Activity,pages:SettingsPages,style:MessengerStyle) {
        fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        fun button(title:String)=Button(activity).apply {text=title;isAllCaps=false;minimumHeight=dp(52);setTextColor(style.text);background=style.ripple(activity,style.surface,16f);layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)}}
        fun field(hint: String, initial: String = "") = EditText(activity).apply { minimumHeight=dp(52);layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8);bottomMargin=dp(8)};this.hint = hint; setText(initial); isSingleLine = true; contentDescription = hint; box.addView(this); isSaveEnabled = false; importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
        box.addView(TextView(activity).apply { text = UiText.get(R.string.ui_download_one_gguf_to_downloads_llamur_models) })
        val repo = field(UiText.get(R.string.ui_repository_author_model))
        val file = field(UiText.get(R.string.ui_path_to_a_single_gguf_file))
        val revision = field("Revision / commit", "main")
        val token = field(UiText.get(R.string.ui_hf_token_optional)).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val saved = LocalCredentials.read(activity)
        token.setText(saved)
        val remember = CheckBox(activity).apply { minimumHeight=dp(52);text=UiText.get(R.string.ui_save_token_with_android_keystore);isChecked=saved.isNotEmpty();box.addView(this) }
        box.addView(button(UiText.get(R.string.ui_delete_saved_token)).apply {setOnClickListener {LocalCredentials.forget(activity);token.setText("");remember.isChecked=false} })
        val progressText=TextView(activity).apply {setTextColor(style.muted);box.addView(this)}
        val submit=button(UiText.get(R.string.ui_check_file)).apply {box.addView(this)}
        box.addView(button(UiText.get(R.string.ui_cancel_download)).apply {setOnClickListener {ModelTransferService.cancel(activity)}})
        pages.open("Hugging Face",ScrollView(activity).apply {addView(box)})
        submit.setOnClickListener {
            try {
                val r=repo.text.toString().trim();val f=file.text.toString().trim();val v=revision.text.toString().trim();val secret=token.text.toString().trim();val store=remember.isChecked
                HfDownload.validate(r,f,v,secret)
                submit.isEnabled=false;progressText.text=UiText.get(R.string.ui_hf_checking_size_revision_sha_256_and)
                Thread {
                    val result=runCatching {HfDownload.inspect(r,f,v,secret)}
                    activity.runOnUiThread {
                        submit.isEnabled=true;progressText.text=""
                        if(activity.isFinishing || activity.isDestroyed || !box.isAttachedToWindow)return@runOnUiThread
                        result.fold({info->
                            val size=android.text.format.Formatter.formatFileSize(activity,info.bytes)
                            AlertDialog.Builder(activity).setTitle(UiText.get(R.string.ui_download, (size)))
                                .setMessage(UiText.get(R.string.ui_commit_author_s_license_gated_requires_the, (r), (f), (info.commit), (info.license), (info.gated)))
                                .setPositiveButton(UiText.get(R.string.ui_download_79f29d)) {_,_->
                                    try {if(store && secret.isNotEmpty())LocalCredentials.save(activity,secret) else LocalCredentials.forget(activity)
                                        ModelTransferService.download(activity,r,f,info.commit,secret);token.setText("");pages.back()
                                    }catch(error:Exception){AlertDialog.Builder(activity).setMessage(error.message).setPositiveButton(UiText.get(R.string.ui_ok),null).show()}
                                }.setNeutralButton(UiText.get(R.string.ui_hf_page)) {_,_->activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://huggingface.co/$r")))}
                                .setNegativeButton(UiText.get(R.string.ui_back),null).show()
                        },{error->AlertDialog.Builder(activity).setMessage(error.message).setPositiveButton(UiText.get(R.string.ui_ok),null).show()})
                    }
                }.start()
            } catch (error: Exception) { AlertDialog.Builder(activity).setMessage(error.message).setPositiveButton(UiText.get(R.string.ui_ok), null).show() }
        }
        fun colors(view:android.view.View){if(view is TextView){view.setTextColor(style.text);if(view is EditText)view.setHintTextColor(style.muted)};if(view is android.view.ViewGroup)for(i in 0 until view.childCount)colors(view.getChildAt(i))};colors(box)
    }
}
