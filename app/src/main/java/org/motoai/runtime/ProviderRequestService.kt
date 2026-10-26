package org.motoai.runtime

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** Started dataSync FGS in the owning process. Work/credentials are RAM-only and never replayed. */
class ProviderRequestService:Service() {
    internal data class Work(val profile:JSONObject,val model:String,val history:JSONArray,val network:RemoteNetwork,
        val changed:(JSONObject)->Unit,val completed:(Result<JSONObject>)->Unit,
        val sequence:(((JSONObject)->Unit)->JSONObject)?=null)
    companion object {
        private val pending=mutableMapOf<String,Work>()
        internal fun enqueue(context:Context,work:Work) {
            check(pending.isEmpty());val id=UUID.randomUUID().toString();pending[id]=work
            try {context.startForegroundService(Intent(context,ProviderRequestService::class.java).putExtra("work",id))}
            catch(f:Exception){pending.remove(id);throw f}
        }
    }
    private val main=Handler(Looper.getMainLooper());private val executor=Executors.newSingleThreadExecutor()
    private var work:Work?=null;private var bound=false;private var destroyed=false
    private var wake:PowerManager.WakeLock?=null
    private var ownedStart=0
    private var latest:JSONObject?=null;private var scheduled=false
    private val connection=object:ServiceConnection {override fun onServiceConnected(n:ComponentName,b:IBinder){};override fun onServiceDisconnected(n:ComponentName){work?.network?.cancel()}}
    private val renewal=object:Runnable {override fun run(){wake?.let {if(work!=null && !destroyed){it.acquire(60000);main.postDelayed(this,30000)}}}}
    private val progress=Runnable {scheduled=false;val value=synchronized(this){latest.also {latest=null}};if(!destroyed && value!=null)work?.changed?.invoke(value)}
    override fun onBind(intent:Intent):IBinder?=null
    override fun onCreate(){super.onCreate();getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("provider",UiText.get(R.string.ui_api_requests),NotificationManager.IMPORTANCE_LOW))}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        ownedStart=startId
        if(intent?.action=="cancel") {work?.network?.cancel();if(work==null)stopSelf();return START_NOT_STICKY}
        val next=pending.remove(intent?.getStringExtra("work"))
        if(next==null){if(work==null)stopSelf();return START_NOT_STICKY}
        if(work!=null){next.completed(Result.failure(RemoteFailure(UiText.get(R.string.ui_an_api_request_is_already_running))));return START_NOT_STICKY}
        work=next
        try {
            val open=PendingIntent.getActivity(this,31,Intent(this,ChatActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
            val stop=PendingIntent.getService(this,32,Intent(this,ProviderRequestService::class.java).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE)
            val notice=Notification.Builder(this,"provider").setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(UiText.get(R.string.ui_llamur_api_request))
                .setContentText(UiText.get(R.string.ui_waiting_for_provider_response)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null,UiText.get(R.string.ui_stop),stop).build()).build()
            if(Build.VERSION.SDK_INT>=29)startForeground(31,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else startForeground(31,notice)
            bound=bindService(Intent(this,RuntimeService::class.java),connection,BIND_AUTO_CREATE);check(bound)
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"llamur:provider").apply {setReferenceCounted(false);acquire(60000)}
            main.postDelayed(renewal,30000)
            executor.execute {
                fun changed(message:JSONObject) {
                    synchronized(this){if(!message.has("attachments"))latest?.optJSONArray("attachments")?.let {message.put("attachments",it)};latest=message;if(!scheduled){scheduled=true;main.postDelayed(progress,150)}}
                }
                val result=runCatching {next.sequence?.invoke {changed(it)}?:next.network.generate(next.profile,next.model,next.history){changed(it)}}
                main.post {if(!destroyed && work===next){main.removeCallbacks(progress);scheduled=false;synchronized(this){latest.also {latest=null}}?.let {next.changed(it)}
                    work=null;next.completed(result);finishWork()}}
            }
        }catch(f:Exception){work=null;next.completed(Result.failure(RemoteFailure(UiText.get(R.string.ui_could_not_start_background_api_request))));finishWork()}
        return START_NOT_STICKY
    }
    private fun finishWork(){main.removeCallbacks(renewal);wake?.let {if(it.isHeld)it.release()};wake=null;stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(ownedStart)}
    override fun onTimeout(startId:Int,fgsType:Int) {val w=work;work=null;w?.network?.cancel();w?.completed?.invoke(Result.failure(RemoteFailure(UiText.get(R.string.ui_android_stopped_a_long_network_request))));finishWork()}
    override fun onDestroy(){destroyed=true;main.removeCallbacks(progress);main.removeCallbacks(renewal);val interrupted=work;work=null;interrupted?.network?.cancel();interrupted?.completed?.invoke(Result.failure(RemoteFailure(UiText.get(R.string.ui_android_stopped_the_background_request))));wake?.let {if(it.isHeld)it.release()};wake=null
        if(bound)unbindService(connection);bound=false;executor.shutdown();super.onDestroy()}
}
