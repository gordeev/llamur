package org.motoai.runtime

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground storage/network operation, separate from inference. Token never leaves RAM. */
class ModelTransferService : Service() {
    companion object {
        private data class Work(val uri: Uri? = null, val reference: Boolean = false, val repo: String = "", val file: String = "", val revision: String = "", val token: String = "")
        private val pending = mutableMapOf<String, Work>()
        private var occupied = false
        private val networkActive = AtomicBoolean(false)
        private fun start(context: Context, work: Work) {
            synchronized(pending) {
                if(occupied && pending.isEmpty() && !networkActive.get() && !ModelStore.transferState().second)occupied=false
                check(!occupied && !ModelStore.transferState().second) { UiText.get(R.string.ui_an_import_or_download_is_already_running) }; occupied = true
                val id = UUID.randomUUID().toString(); pending[id] = work
                try { context.startForegroundService(Intent(context, ModelTransferService::class.java).putExtra("work", id)) }
                catch (failure: Throwable) { pending.remove(id); occupied = false; throw failure }
            }
        }
        fun import(context: Context, uri: Uri) = start(context, Work(uri = uri))
        fun attach(context:Context,uri:Uri) = start(context,Work(uri=uri,reference=true))
        fun download(context: Context, repo: String, file: String, revision: String, token: String) {
            HfDownload.validate(repo, file, revision, token)
            start(context, Work(repo = repo, file = file, revision = revision, token = token))
        }
        fun cancel(context: Context) { context.startService(Intent(context, ModelTransferService::class.java).setAction("cancel")) }
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private var running = false
    private var destroyed = false
    private var ownedStartId = 0
    private var external = false
    private var poll: Runnable? = null
    private var line = UiText.get(R.string.ui_preparing)
    override fun onBind(intent: Intent) = null
    private fun notification(text: String): Notification {
        val cancel = PendingIntent.getService(this, 21, Intent(this, ModelTransferService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 22, Intent(this, ChatActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "model_transfer").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(UiText.get(R.string.ui_llamur_model)).setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, UiText.get(R.string.ui_cancel_d1c0c3), cancel).build()).build()
    }
    override fun onCreate() { super.onCreate(); getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("model_transfer", UiText.get(R.string.ui_model_downloads), NotificationManager.IMPORTANCE_LOW)) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ownedStartId=startId
        if (intent?.action == "cancel") { cancelled.set(true); ModelStore.cancel(); if (!running) stopSelf(); return START_NOT_STICKY }
        val work = synchronized(pending) { pending.remove(intent?.getStringExtra("work")) }
        if (work == null) { if (!running) stopSelf(); return START_NOT_STICKY }
        running = true; line=UiText.get(R.string.ui_preparing);cancelled.set(false)
        if (Build.VERSION.SDK_INT >= 29) startForeground(21, notification(line), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(21, notification(line))
        if (work.uri != null) {
            if(work.reference)ModelStore.attachReference(this,work.uri)else ModelStore.import(this, work.uri)
            val check = object : Runnable { override fun run() {
                val state = ModelStore.transferState()
                update(state.first)
                if (state.second) main.postDelayed(this, 500) else finishWork()
            } }; poll = check; main.post(check)
        } else {
            try { ModelStore.startExternal(); external = true } catch (failure: Exception) { update(failure.message ?: UiText.get(R.string.ui_operation_busy)); finishWork(); return START_NOT_STICKY }
            networkActive.set(true)
            worker.execute {
                val message = try {
                    HfDownload.run(this,work.repo,work.file,work.revision,work.token,cancelled) {text->main.post {update(text)}}
                    UiText.get(R.string.ui_model_ready, (if(Build.VERSION.SDK_INT>=29)"Downloads/llamur/Models" else UiText.get(R.string.ui_internal_storage_9591ff)))
                }catch(failure:Exception){failure.message?:UiText.get(R.string.ui_download_failed)}
                finally{networkActive.set(false)}
                main.post {update(message);finishWork()}
        }
        }
        return START_NOT_STICKY
    }
    private fun update(text: String) { if(destroyed)return; line = text; getSystemService(NotificationManager::class.java).notify(21, notification(text)); ModelStore.externalStatus(text) }
    private fun finishWork() {
        if (external && !networkActive.get()) { ModelStore.finishExternal(); external = false }
        running = false; synchronized(pending) { if(!networkActive.get() && !ModelStore.transferState().second)occupied=pending.isNotEmpty() }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(ownedStartId)
    }
    override fun onTimeout(startId: Int, fgsType: Int) { cancelled.set(true); ModelStore.cancel(); finishWork() }
    override fun onDestroy() {
        destroyed=true;poll?.let {main.removeCallbacks(it)};cancelled.set(true)
        if(ModelStore.transferState().second)ModelStore.cancel()
        worker.shutdown()
        synchronized(pending) {if(!networkActive.get() && !ModelStore.transferState().second)occupied=pending.isNotEmpty()}
        super.onDestroy()
    }
}
