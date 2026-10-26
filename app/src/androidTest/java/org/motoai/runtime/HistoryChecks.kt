package org.motoai.runtime

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Unique public fixture database/alias only; never reads or resets the owner's history. */
internal class HistoryChecks(private val test:Instrumentation) {
    private var checks=0
    private fun verify(value:Boolean){check(value){"History check ${checks+1} failed"};checks++}
    private fun rejects(block:()->Unit){verify(runCatching(block).isFailure)}
    fun run():JSONObject {
        val context=test.targetContext;val suffix=UUID.randomUUID().toString();val file="fixture-history-$suffix.db";val alias="motoai-fixture-history-$suffix";var disk=HistoryDisk(context,file,alias);var clock=1700000000000L
        fun store()=ChatStore(disk,{clock})
        fun sqlite()=SQLiteDatabase.openDatabase(context.getDatabasePath(file).path,null,SQLiteDatabase.OPEN_READWRITE)
        fun body(id:String)=sqlite().use {db->db.rawQuery("SELECT payload FROM chats WHERE id=?",arrayOf(id)).use {check(it.moveToFirst());it.getBlob(0)}}
        try {
            var chats=store();val secret="temporary-fixture-never-on-disk-🦉-$suffix";val transient=chats.create("fixture").getString("id")
            chats.begin(transient,"temporary-one",secret);chats.finish(transient,JSONObject().put("content",secret),"eog",UUID.randomUUID().toString());chats.draft(transient,secret)
            verify(disk.catalog().isEmpty());rejects {chats.create("fixture",permanent=true)}
            chats.policy("keep");val remote=JSONObject().put("id",UUID.randomUUID().toString()).put("revision",UUID.randomUUID().toString()).put("name","Public fixture").put("protocol","anthropic").put("model","fixture").put("base_url","https://example.invalid/v1")
            val saved=chats.create("remote:fixture",remote,true).getString("id");val answer=UUID.randomUUID().toString();val text="Permanent fixture Привет🌍 $suffix"
            chats.begin(saved,"permanent-one","Public first question")
            chats.finish(saved,JSONObject().put("content",text).put("reasoning_content","public thought").put("remote_payload",JSONArray().put(JSONObject().put("type","thinking").put("thinking","public thought").put("signature","fixture-signature")).put(JSONObject().put("type","text").put("text",text))),"eog",answer)
            chats.pin(saved,1,true);val folders=chats.folder(null,"Fixture work").getJSONArray("folders");val folder=folders.getJSONObject(0).getString("id");chats.move(saved,folder)
            val previous=body(saved);chats.draft(saved,"Following draft ☕");verify(previous.contentEquals(body(saved))) // Draft edits do not rewrite a long body.
            disk.close();disk=HistoryDisk(context,file,alias);chats=store()
            verify(chats.list().length()==1 && chats.list().getJSONObject(0).getString("id")==saved)
            val restored=chats.get(saved);verify(restored.getJSONArray("messages").getJSONObject(1).getString("content")==text)
            verify(restored.getJSONArray("messages").getJSONObject(1).getJSONArray("remote_payload").getJSONObject(0).getString("signature")=="fixture-signature")
            verify(restored.getString("draft")=="Following draft ☕" && restored.getString("folder_id")==folder && restored.getJSONArray("pinned").getInt(0)==1)
            verify(chats.findAnswer(answer)==saved);rejects {chats.get(transient)}
            chats.begin(saved,"permanent-interrupted","Public interrupted question");chats.output(saved,JSONObject().put("content","uncommitted partial"))
            disk.close();disk=HistoryDisk(context,file,alias);chats=store();verify(!chats.list().getJSONObject(0).getBoolean("active"))
            val recovered=chats.get(saved);verify(!recovered.getBoolean("active") && recovered.getBoolean("recovered_after_restart"));verify(recovered.getJSONArray("messages").getJSONObject(3).getString("status")=="interrupted")
            verify(recovered.getJSONArray("messages").getJSONObject(2).getString("content")=="Public interrupted question")
            // An actual SQLite failure must leave a visible full RAM answer, not pretend durability.
            sqlite().use {it.execSQL("CREATE TRIGGER fixture_failure BEFORE UPDATE ON chats BEGIN SELECT RAISE(FAIL,'fixture-storage-failure'); END")}
            chats.begin(saved,"failed-save","Public storage failure");chats.finish(saved,JSONObject().put("content","RAM answer survives disk failure"),"eog",UUID.randomUUID().toString())
            verify(chats.get(saved).optString("save_error").isNotEmpty() && chats.get(saved).getJSONArray("messages").getJSONObject(5).getString("content")=="RAM answer survives disk failure")
            sqlite().use {it.execSQL("DROP TRIGGER fixture_failure")};chats.commit(saved);verify(chats.get(saved).optString("save_error").isEmpty())
            chats.folder(folder,null,true);verify(chats.get(saved).getString("folder_id").isEmpty() && chats.folders().isEmpty())
            chats.policy("auto",3600000);clock+=3600001;chats.expire();verify(chats.list().length()==0 && disk.catalog().isEmpty() && chats.findAnswer(answer)==null)
            val active=chats.create("fixture",permanent=true).getString("id");chats.begin(active,"active-expiry","Public active expiry")
            clock+=3600001;chats.expire();verify(chats.get(active).getBoolean("active"));chats.finish(active,JSONObject().put("content","done"),"eog",UUID.randomUUID().toString());verify(chats.nextExpiry()==clock+3600000)
            chats.policy("keep");chats.policy("never");verify(disk.catalog().isEmpty() && !chats.get(active).getBoolean("permanent"))
            disk.close();disk=HistoryDisk(context,file,alias);chats=store();verify(chats.list().length()==0 && chats.settings().getString("mode")=="never")
            // More permanent bodies than fit the resident budget: old bodies must reload exactly.
            chats.policy("keep");val ids=mutableListOf<String>();val payload="Public lazy-cache fixture "+"x".repeat(1024*1024)
            repeat(40){n->val id=chats.create("fixture",permanent=true).getString("id");ids.add(id);chats.begin(id,"cache-$n","Public cache question$n");chats.finish(id,JSONObject().put("content",payload+n),"eog",UUID.randomUUID().toString())}
            verify(chats.list().length()==40 && chats.settings().getInt("resident_count")<40);verify(chats.settings().getInt("ram_bytes")<=chats.settings().getInt("ram_limit_bytes"))
            verify(chats.get(ids.first()).getJSONArray("messages").getJSONObject(1).getString("content")==payload+0)
            val pollId=ids.first();chats.begin(pollId,"poll-tail","Public polling question");val version=chats.get(pollId).getLong("view_revision");chats.output(pollId,JSONObject().put("content","tiny changed tail"))
            val delta=chats.poll(pollId,version,4);verify(delta.getJSONObject("tail").getString("content")=="tiny changed tail" && delta.toString().toByteArray().size<2048)
            verify(chats.get(pollId).getJSONArray("messages").getJSONObject(1).getString("content")==payload+0);verify(chats.poll(pollId,delta.getLong("view_revision"),4).getBoolean("unchanged"));chats.finish(pollId,null,"eog",UUID.randomUUID().toString())
            // Plaintext never appears in the database or journal; both catalog and body are sealed.
            for(path in context.databaseList().filter {it.startsWith(file)}) {val bytes=context.getDatabasePath(path).readBytes();for(plain in listOf(secret,text,"Public lazy-cache fixture","Public cache question"))verify(!String(bytes,Charsets.ISO_8859_1).contains(String(plain.toByteArray(Charsets.UTF_8),Charsets.ISO_8859_1)))}
            disk.close();java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)};disk=HistoryDisk(context,file,alias);chats=store()
            verify(chats.settings().getString("storage_error").isNotEmpty());verify(sqlite().use {it.rawQuery("SELECT COUNT(*) FROM chats",null).use {c->c.moveToFirst();c.getInt(0)}}==40)
            val temporary=chats.create("fixture").getString("id");rejects {chats.policy("keep")};verify(!chats.get(temporary).getBoolean("permanent"))
            chats.resetStorage();verify(chats.settings().getString("storage_error").isEmpty() && chats.list().length()==1)
        }finally {runCatching {disk.erase()};runCatching {context.deleteDatabase(file)};runCatching {java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)}}}
        return JSONObject().put("passed",true).put("checks",checks).put("scope","Real unique SQLite/Keystore fixtures: temporary never persisted, permanent encrypted restart/opaque state/draft/pin/folder/answer index, interrupted admission recovery/no active replay, SQLite failure keeps RAM answer, explicit retry, TTL/active guard/Never transition,40MiB lazy-cache exact reload, plaintext scan and missing-key explicit recovery. Not visual UI, real cloud account or native model-quality acceptance.")
    }
}
