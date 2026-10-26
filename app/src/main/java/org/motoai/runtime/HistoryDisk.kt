package org.motoai.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/** Only explicit permanent chats enter this database. Bodies AND catalog text are encrypted.
 * Random IDs, byte lengths and expiry timestamps are the only plaintext row metadata.
 * One owner in :inference; no UI process database access or token-by-token writes. */
internal class HistoryDisk(context:Context,private val databaseName:String="chat-history-v1.db",private val keyAlias:String="motoai-chat-history-v1") {
    private val app=context.applicationContext
    private val helper=object:SQLiteOpenHelper(app,databaseName,null,1) {
        override fun onConfigure(db:SQLiteDatabase){db.setForeignKeyConstraintsEnabled(true);db.rawQuery("PRAGMA secure_delete=ON",null).use {it.moveToFirst();check(it.getInt(0)==1)}}
        override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE key_material(id INTEGER PRIMARY KEY CHECK(id=1),payload BLOB NOT NULL)");db.execSQL("CREATE TABLE settings(id INTEGER PRIMARY KEY CHECK(id=1), payload BLOB NOT NULL)");db.execSQL("CREATE TABLE chats(id TEXT PRIMARY KEY,catalog BLOB NOT NULL,payload BLOB NOT NULL,draft BLOB,expires INTEGER NOT NULL,size INTEGER NOT NULL)");db.execSQL("CREATE TABLE answers(id TEXT PRIMARY KEY,chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE)")}
        override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int){error(UiText.get(R.string.ui_unsupported_storage_version_data_was_not_deleted))}
    }
    private val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
    private val wrappingKey:SecretKey by lazy {
        (keys.getKey(keyAlias,null) as? SecretKey)?:run {
            val existing=helper.readableDatabase.rawQuery("SELECT (SELECT COUNT(*) FROM settings)+(SELECT COUNT(*) FROM chats)+(SELECT COUNT(*) FROM key_material)",null).use {it.moveToFirst();it.getInt(0)>0}
            check(!existing){UiText.get(R.string.ui_saved_chat_key_unavailable_data_was_not)}
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {init(KeyGenParameterSpec.Builder(keyAlias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()
        }
    }
    // Unwrap once per process. Bulk history crypto uses a RAM data key instead of
    // one hardware/Keystore IPC per catalog row; the raw key is never written.
    private val key:SecretKey by lazy {
        val wrapped=helper.readableDatabase.rawQuery("SELECT payload FROM key_material WHERE id=1",null).use {if(it.moveToFirst())it.getBlob(0)else null}
        if(wrapped!=null) {
            require(wrapped.size==61 && wrapped[0].toInt()==1)
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,wrappingKey,GCMParameterSpec(128,wrapped.copyOfRange(1,13)))
            val raw=c.doFinal(wrapped.copyOfRange(13,wrapped.size));require(raw.size==32);SecretKeySpec(raw,"AES")
        } else {
            val existing=helper.readableDatabase.rawQuery("SELECT (SELECT COUNT(*) FROM settings)+(SELECT COUNT(*) FROM chats)",null).use {it.moveToFirst();it.getInt(0)>0}
            check(!existing){UiText.get(R.string.ui_chat_data_key_unavailable_data_was_not)}
            val raw=ByteArray(32).also {SecureRandom().nextBytes(it)};val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,wrappingKey)
            check(c.iv.size==12);val payload=byteArrayOf(1)+c.iv+c.doFinal(raw)
            check(helper.writableDatabase.insertOrThrow("key_material",null,ContentValues().apply {put("id",1);put("payload",payload)})!=-1L)
            SecretKeySpec(raw,"AES")
        }
    }
    private fun seal(j:JSONObject):ByteArray {val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);check(c.iv.size==12);return byteArrayOf(1)+c.iv+c.doFinal(j.toString().toByteArray(Charsets.UTF_8))}
    private fun open(bytes:ByteArray):JSONObject {require(bytes.size in 29..(13*1024*1024) && bytes[0].toInt()==1);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(1,13)));return JSONObject(Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(c.doFinal(bytes.copyOfRange(13,bytes.size)))).toString())}
    fun settings():JSONObject=helper.readableDatabase.rawQuery("SELECT payload FROM settings WHERE id=1",null).use {if(it.moveToFirst())open(it.getBlob(0))else JSONObject().put("mode","never").put("ttl_ms",7L*86400000).put("folders",org.json.JSONArray())}
    fun catalog():List<JSONObject> = helper.readableDatabase.rawQuery("SELECT catalog FROM chats ORDER BY rowid",null).use {c->buildList {while(c.moveToNext())add(open(c.getBlob(0)))}}
    fun load(id:String):JSONObject {
        val db=helper.readableDatabase
        // Avoid CursorWindow's typical2MiB limit for bodies containing files.
        val size=db.rawQuery("SELECT LENGTH(payload) FROM chats WHERE id=?",arrayOf(id)).use {check(it.moveToFirst()){UiText.get(R.string.ui_saved_chat_already_deleted)};it.getInt(0)}
        require(size in 29..13*1024*1024);val bytes=ByteArray(size);var offset=0
        while(offset<size){val chunk=db.rawQuery("SELECT SUBSTR(payload,?,?) FROM chats WHERE id=?",arrayOf((offset+1).toString(),minOf(512*1024,size-offset).toString(),id)).use {check(it.moveToFirst());it.getBlob(0)};check(chunk.isNotEmpty());chunk.copyInto(bytes,offset);offset+=chunk.size}
        val chat=open(bytes)
        db.rawQuery("SELECT draft FROM chats WHERE id=?",arrayOf(id)).use {check(it.moveToFirst());if(!it.isNull(0)){val draft=open(it.getBlob(0));if(draft.optString("base_request_id")==chat.optString("request_id"))chat.put("draft",draft.getString("text"))}}
        return chat
    }
    fun save(chat:JSONObject,metadata:JSONObject) {
        val body=seal(chat);val meta=seal(metadata);require(body.size<=12*1024*1024+1024){UiText.get(R.string.ui_chat_exceeds_storage_budget)}
        val db=helper.writableDatabase;db.beginTransaction()
        try {
            db.rawQuery("SELECT COUNT(*),COALESCE(SUM(size),0) FROM chats WHERE id!=?",arrayOf(chat.getString("id"))).use {it.moveToFirst();require(it.getInt(0)<1000 && it.getLong(1)+body.size+meta.size<=256L*1024*1024){UiText.get(R.string.ui_storage_full_limit_of_1000_chats_256)}}
            val row=ContentValues().apply {put("id",chat.getString("id"));put("catalog",meta);put("payload",body);putNull("draft");put("expires",metadata.optLong("expires_at"));put("size",body.size+meta.size)}
            if(db.update("chats",row,"id=?",arrayOf(chat.getString("id")))==0)db.insertOrThrow("chats",null,row)
            chat.optString("answer_id").takeIf {it.isNotEmpty()}?.let {answer->db.insertWithOnConflict("answers",null,ContentValues().apply {put("id",answer);put("chat_id",chat.getString("id"))},SQLiteDatabase.CONFLICT_IGNORE)}
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun saveDraft(chat:JSONObject,metadata:JSONObject) {
        val draft=seal(JSONObject().put("text",chat.optString("draft")).put("base_request_id",chat.optString("request_id")));val meta=seal(metadata);val db=helper.writableDatabase;db.beginTransaction()
        try {
            val bodySize=db.rawQuery("SELECT LENGTH(payload) FROM chats WHERE id=?",arrayOf(chat.getString("id"))).use {check(it.moveToFirst());it.getLong(0)}
            val total=db.rawQuery("SELECT COALESCE(SUM(size),0) FROM chats WHERE id!=?",arrayOf(chat.getString("id"))).use {it.moveToFirst();it.getLong(0)}
            require(total+bodySize+draft.size+meta.size<=256L*1024*1024){UiText.get(R.string.ui_storage_full_draft_is_only_in_ram)}
            check(db.update("chats",ContentValues().apply {put("draft",draft);put("catalog",meta);put("expires",metadata.optLong("expires_at"));put("size",bodySize+draft.size+meta.size)},"id=?",arrayOf(chat.getString("id")))==1);db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    private fun replaceCatalog(db:SQLiteDatabase,id:String,data:ByteArray,expires:Long) {
        val delta=db.rawQuery("SELECT LENGTH(catalog) FROM chats WHERE id=?",arrayOf(id)).use {check(it.moveToFirst());data.size-it.getInt(0)}
        val total=db.rawQuery("SELECT COALESCE(SUM(size),0) FROM chats",null).use {it.moveToFirst();it.getLong(0)};require(total+delta<=256L*1024*1024){UiText.get(R.string.ui_storage_full)}
        db.compileStatement("UPDATE chats SET size=size+?,catalog=?,expires=? WHERE id=?").use {it.bindLong(1,delta.toLong());it.bindBlob(2,data);it.bindLong(3,expires);it.bindString(4,id);check(it.executeUpdateDelete()==1)}
    }
    fun updateCatalog(metadata:JSONObject) {val data=seal(metadata);val db=helper.writableDatabase;db.beginTransaction();try{replaceCatalog(db,metadata.getString("id"),data,metadata.optLong("expires_at"));db.setTransactionSuccessful()}finally{db.endTransaction()}}
    fun delete(id:String){helper.writableDatabase.delete("chats","id=?",arrayOf(id))}
    fun configure(settings:JSONObject,metadata:List<JSONObject> = emptyList(),clear:Boolean=false) {
        val payload=seal(settings);val rows=metadata.map {Triple(it.getString("id"),seal(it),it.optLong("expires_at"))};val db=helper.writableDatabase;db.beginTransaction()
        try {if(clear)db.delete("chats",null,null)else for((id,data,expires)in rows)replaceCatalog(db,id,data,expires)
            check(db.insertWithOnConflict("settings",null,ContentValues().apply {put("id",1);put("payload",payload)},SQLiteDatabase.CONFLICT_REPLACE)!=-1L);db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun findAnswer(id:String):String?=helper.readableDatabase.rawQuery("SELECT chat_id FROM answers WHERE id=?",arrayOf(id)).use {if(it.moveToFirst())it.getString(0)else null}
    fun close(){helper.close()}
    fun erasedReplacement():HistoryDisk {erase();return HistoryDisk(app,databaseName,keyAlias)}
    fun erase(){helper.close();check(app.deleteDatabase(databaseName) || !app.getDatabasePath(databaseName).exists());if(keys.containsAlias(keyAlias))keys.deleteEntry(keyAlias)}
}

/** Shared with the non-exported expiry receiver; all entry points run on this process main thread. */
internal object HistoryOwner {
    private var owner:ChatStore?=null
    private var listener:(()->Unit)?=null
    private var scheduledAt:Long?=null
    fun observe(callback:(()->Unit)?){listener=callback}
    fun get(context:Context):ChatStore {
        check(Looper.myLooper()==Looper.getMainLooper())
        owner?.let {return it}
        val app=context.applicationContext
        return ChatStore(HistoryDisk(app),changed={schedule(app);listener?.invoke()}).also {owner=it;it.expire();schedule(app)}
    }
    private fun schedule(context:Context) {
        val next=owner?.nextExpiry();if(next==scheduledAt)return;scheduledAt=next
        val alarm=context.getSystemService(AlarmManager::class.java)
        val intent=PendingIntent.getBroadcast(context,70,Intent(context,HistoryExpiryReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarm.cancel(intent)
        next?.let {at->alarm.setWindow(AlarmManager.RTC_WAKEUP,maxOf(at,System.currentTimeMillis()+60000),15*60*1000L,intent)}
    }
}
class HistoryExpiryReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent){runCatching {HistoryOwner.get(context).expire()}}
}
