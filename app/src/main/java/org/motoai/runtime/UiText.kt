package org.motoai.runtime

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList

/** Android resources in both processes; model IDs, chat contents and protocol data are never translated. */
internal object UiText {
    private lateinit var app:Context
    @Volatile private var legacy:Resources?=null
    fun initialize(context:Context){app=context.applicationContext;refresh()}
    fun refresh(){if(Build.VERSION.SDK_INT<33)applyLegacy(language())}
    fun language():String=if(Build.VERSION.SDK_INT>=33)app.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language.orEmpty()else app.getSharedPreferences("ui-language",Context.MODE_PRIVATE).getString("tag","").orEmpty()
    fun choose(tag:String){require(tag in listOf("","ru","en"));if(Build.VERSION.SDK_INT>=33)app.getSystemService(LocaleManager::class.java).applicationLocales=LocaleList.forLanguageTags(tag)else {app.getSharedPreferences("ui-language",Context.MODE_PRIVATE).edit().putString("tag",tag).apply();applyLegacy(tag)}}
    fun applyLegacy(tag:String){require(tag in listOf("","ru","en"));val config=Configuration(app.resources.configuration);if(tag.isNotEmpty())config.setLocales(LocaleList.forLanguageTags(tag));legacy=app.createConfigurationContext(config).resources}
    private fun resources()=if(Build.VERSION.SDK_INT>=33)app.resources else legacy?:app.resources
    fun locale()=resources().configuration.locales.get(0)?:java.util.Locale.ENGLISH
    fun get(id:Int,vararg args:Any?):String {val r=resources();return if(args.isEmpty())r.getString(id)else r.getString(id,*args)}
}
class LlamurApplication:Application() {
    override fun onCreate(){super.onCreate();UiText.initialize(this)}
    override fun onConfigurationChanged(config:Configuration){super.onConfigurationChanged(config);UiText.refresh()}
}
