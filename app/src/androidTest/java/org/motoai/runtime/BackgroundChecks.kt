package org.motoai.runtime

import android.Manifest
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.*
import android.os.*
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real service + activity lifecycle; no fake completion notification. Tiny fixture only. */
internal class BackgroundChecks(private val test: Instrumentation) {
    val progress = JSONObject()
    private val context get() = test.targetContext
    private val events = LinkedBlockingQueue<JSONObject>()
    private val chunks = LinkedBlockingQueue<Bundle>()
    private val connected = CountDownLatch(1)
    private lateinit var remote: Messenger
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        message.data.getString("product_id")?.let { id ->
            message.data.getParcelable<ParcelFileDescriptor>("payload")?.let { fd -> ProductPipe.read(fd,34*1024*1024) { result ->
                runCatching { JSONObject(String(result.getOrThrow(),Charsets.UTF_8)) }.fold({ productReplies.remove(id)?.complete(it) }, { productReplies.remove(id)?.completeExceptionally(it) })
            } }
        }
        message.data.getString("snapshot")?.let { events.offer(JSONObject(it)) }
        if (message.data.containsKey("output_chunk")) chunks.offer(Bundle(message.data))
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            remote = Messenger(binder); send(RuntimeService.REGISTER); connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) {}
    }
    private val productReplies = java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.CompletableFuture<JSONObject>>()
    private fun product(body:JSONObject):JSONObject {
        val id=java.util.UUID.randomUUID().toString();val future=java.util.concurrent.CompletableFuture<JSONObject>();productReplies[id]=future
        val fd=ProductPipe.write(body.toString().toByteArray(Charsets.UTF_8))
        try {send(RuntimeService.PRODUCT,Bundle().apply {putString("id",id);putParcelable("payload",fd)})}finally{fd.close()}
        val result=future.get(20,TimeUnit.SECONDS);check(result.getBoolean("ok")) {result.toString()};return result
    }
    fun runProduct():JSONObject {
        val notifications=context.getSystemService(NotificationManager::class.java)
        val granted=notifications.areNotificationsEnabled()
        val activity=test.startActivitySync(Intent(context,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        check(context.bindService(Intent(context,RuntimeService::class.java),connection,Context.BIND_AUTO_CREATE))
        check(connected.await(15,TimeUnit.SECONDS))
        try {
            context.startForegroundService(Intent(context,RuntimeService::class.java).setAction(RuntimeService.ACTION_SESSION))
            send(RuntimeService.LOAD,Bundle().apply {putString("model","stories260K.gguf");putString("config",JSONObject().put("context",2048).put("threads",2).put("batch",128).put("micro_batch",64).put("load_mode","read").toString())})
            await {it.optString("state")=="ready"}
            val chat=product(JSONObject().put("action","new")).getJSONObject("chat").getString("id")
            events.clear()
            product(JSONObject().put("action","send").put("chat_id",chat).put("request_id",java.util.UUID.randomUUID().toString()).put("text","Once upon a time ".repeat(128)).put("max_tokens",1024))
            test.runOnMainSync { check(activity.moveTaskToBack(true)) }
            ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")).use {it.readBytes()}
            ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP")).use {it.readBytes()}
            val completed=await {it.optString("state")=="ready" && it.has("result")}
            progress.put("product_completed",completed)
            val answerId=completed.getString("output_id")
            val saved=product(JSONObject().put("action","get").put("chat_id",chat)).getJSONObject("chat")
            val answer=saved.getJSONArray("messages").getJSONObject(1).getString("content")
            check(answer.isNotEmpty() && !saved.getBoolean("active"))
            check(!completed.getBoolean("completed_interactive")) {"Did not actually finish screen-off"}
            ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP")).use {it.readBytes()}
            ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("wm dismiss-keyguard")).use {it.readBytes()}
            awaitOwnerUnlock()
            if(granted) {
                until {notifications.activeNotifications.any {it.tag==answerId}}
                val notification=notifications.activeNotifications.single {it.tag==answerId}.notification
                tapSystemNotification("Ответ готов")
                until { var shown=false;test.runOnMainSync {shown=views(activity.window.decorView).filterIsInstance<android.widget.TextView>().any {it.text.toString()==answer}};shown }
                until {notifications.activeNotifications.none {it.tag==answerId}}
                product(JSONObject().put("action","delete").put("chat_id",chat))
                notification.contentIntent.send()
                until { val root=test.uiAutomation.rootInActiveWindow ?: return@until false
                    fun texts(n:android.view.accessibility.AccessibilityNodeInfo):List<String> = listOf(n.text?.toString()?:"")+(0 until n.childCount).flatMap {n.getChild(it)?.let {texts(it)}?:emptyList()}
                    texts(root).any {it.contains("Этот ответ уже недоступен")}
                }
            } else { Thread.sleep(800);check(notifications.activeNotifications.none {it.tag==answerId});product(JSONObject().put("action","delete").put("chat_id",chat)) }
            send(RuntimeService.UNLOAD);await {it.optString("state")=="unloaded"}
            return JSONObject().put("passed",true).put("permission",if(granted)"granted" else "denied").put("screen_off_completed",true)
                .put("checks","Actual product multi-turn store, screen-off generation, RAM-only answer, notification tap to correct dialog/ack, deleted-dialog stale link or denied-notification completion")
        }finally{
            runCatching {ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP")).use {it.readBytes()}}
            runCatching {send(RuntimeService.UNLOAD);send(RuntimeService.UNREGISTER)};context.unbindService(connection);test.runOnMainSync {activity.finish()}
            notifications.activeNotifications.filter {it.id==AnswerNotifications.ID}.forEach {notifications.cancel(it.tag,it.id)}
        }
    }
    private fun awaitOwnerUnlock() {
        val keyguard=context.getSystemService(android.app.KeyguardManager::class.java)
        val signal=java.io.File(context.filesDir,"background-owner-unlock.json")
        val end=SystemClock.elapsedRealtime()+180000
        try {
            if(keyguard.isDeviceLocked || keyguard.isKeyguardLocked)
                signal.writeText(JSONObject().put("waiting_for_owner_unlock",true).put("scope","Real screen-off test completed; owner must unlock normally before UI notification interaction").toString())
            while(keyguard.isDeviceLocked || keyguard.isKeyguardLocked) {
                check(SystemClock.elapsedRealtime()<end) {"Owner unlock required after screen-off; no security settings changed"}
                Thread.sleep(100)
            }
            Thread.sleep(400) // Allow the ordinary keyguard exit transition to finish.
        } finally { signal.delete() }
    }
    private fun send(command: Int, body: Bundle = Bundle()) {
        remote.send(Message.obtain(null, command).apply { data = body; replyTo = incoming })
    }
    private fun await(condition: (JSONObject) -> Boolean): JSONObject {
        val end = SystemClock.elapsedRealtime() + 30000
        var last: JSONObject? = null
        while (SystemClock.elapsedRealtime() < end) {
            val item = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
            last = item
            if (condition(item)) return item
        }
        error("Background service timed out: $last")
    }
    private fun until(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10000
        while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Background UI/notification timed out: $progress" }; Thread.sleep(50) }
    }
    private fun views(view: android.view.View): List<android.view.View> = listOf(view) +
        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun tapSystemNotification(title: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("cmd statusbar expand-notifications"))
            .use { it.readBytes() }
        try { until {
            val root = test.uiAutomation.rootInActiveWindow ?: return@until false
            root.refresh()
            fun descendants(node: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> =
                listOf(node) + (0 until node.childCount).flatMap { index -> node.getChild(index)?.let { descendants(it) } ?: emptyList() }
            val all = descendants(root)
            val candidates = all.filter { it.text?.toString() == title }
            // Only structure and matches for our known public title/app label;
            // never collect other notification text, screenshots or account names.
            progress.put("notification_root", root.packageName).put("matching_notification_titles", candidates.size)
                .put("root_node_count",all.size)
                .put("title_text_contains_count",all.count {it.text?.toString()?.contains(title)==true})
                .put("title_description_contains_count",all.count {it.contentDescription?.toString()?.contains(title)==true})
                .put("own_app_label_count",all.count {it.text?.toString()?.contains("MotoAI")==true})
                .put("systemui_resource_ids",org.json.JSONArray(all.mapNotNull {it.viewIdResourceName?.takeIf {id->id.startsWith("com.android.systemui:")}}.distinct()))
                .put("keyguard_locked",context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
                .put("device_locked",context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
            for (candidate in candidates) {
                var node: android.view.accessibility.AccessibilityNodeInfo? = candidate
                var clickable: android.view.accessibility.AccessibilityNodeInfo? = null
                repeat(20) {
                    val current = node ?: return@repeat
                    if (current.isClickable && clickable == null) clickable = current
                    if (clickable != null && descendants(current).any { it.text?.toString()?.contains("MotoAI") == true })
                        return@until clickable!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    node = current.parent
                }
            }
            false
        } } catch (error: Throwable) {
            // Diagnostic pixels are only allowed on the headless public-fixture
            // emulator, never on a person's phone or another application.
            if (Build.HARDWARE == "ranchu") {
                test.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.filesDir, "background-failure.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                fun describe(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int): String {
                    if (depth > 12) return ""
                    return " ".repeat(depth)+"${node.className} text=${node.text} description=${node.contentDescription} clickable=${node.isClickable}\n" +
                        (0 until node.childCount).joinToString("") { index -> node.getChild(index)?.let { describe(it, depth+1) } ?: "" }
                }
                test.uiAutomation.rootInActiveWindow?.let {
                    java.io.File(context.filesDir, "background-failure-ui.txt").writeText(describe(it, 0))
                }
            }
            throw error
        }
    }
    fun run(): JSONObject {
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notifications = context.getSystemService(NotificationManager::class.java)
        var activity: android.app.Activity? = null
        check(context.bindService(Intent(context, RuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        check(connected.await(15, TimeUnit.SECONDS))
        try {
            await { it.optString("state") == "unloaded" }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("cmd statusbar collapse"))
                .use { it.readBytes() }
            activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            test.waitForIdleSync()
            until {
                var focused = false
                test.runOnMainSync { focused = activity!!.hasWindowFocus() }
                focused
            }
            progress.put("notifications_enabled", notifications.areNotificationsEnabled())
            context.startForegroundService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.ACTION_SESSION))
            send(RuntimeService.LOAD, Bundle().apply {
                putString("model", "stories260K.gguf")
                putString("config", JSONObject().put("context", 2048).put("threads", 2).put("batch", 128).put("micro_batch", 64).put("load_mode", "read").toString())
            })
            await { it.optString("state") == "ready" }
            var previousOutput = ""
            fun question(): JSONObject {
                events.clear()
                send(RuntimeService.GENERATE, Bundle().apply {
                    putString("prompt", "Once upon a time ".repeat(128)); putBoolean("raw", true); putInt("tokens", 256)
                    putBoolean("_benchmark_output", false)
                })
                test.runOnMainSync { check(activity!!.moveTaskToBack(true)) { "Activity task could not be minimized" } }
                test.waitForIdleSync()
                android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME"))
                    .use { it.readBytes() }
                return await { it.optString("state") == "ready" && it.has("result") && it.optString("output_id") != previousOutput }.also {
                    previousOutput = it.getString("output_id")
                    check(it.optString("output_transport") == "memory" && !it.has("error_kind"))
                    check(it.getJSONObject("result").getInt("generated_tokens") > 0)
                    progress.put("completed", it)
                    test.runOnMainSync { progress.put("window_focused_at_completion", activity!!.hasWindowFocus()) }
                }
            }
            val first = question(); val firstId = first.getString("output_id")
            if (!granted) {
                Thread.sleep(900)
                check(notifications.activeNotifications.none { it.tag == firstId })
                send(RuntimeService.UNLOAD); await { it.optString("state") == "unloaded" }
                return JSONObject().put("passed", true).put("notification_permission", "denied")
                    .put("checks", "Question completes after minimizing with notifications denied; RAM output remains available; no completion notice")
            }
            until { notifications.activeNotifications.any { it.tag == firstId && it.id == AnswerNotifications.ID } }
            val notice = notifications.activeNotifications.single { it.tag == firstId && it.id == AnswerNotifications.ID }
            check(notice.notification.extras.getCharSequence("android.title").toString() == "Ответ готов")
            check(!notice.notification.extras.toString().contains("Once upon"))
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() < first.getInt("output_bytes")) {
                send(RuntimeService.READ_OUTPUT, Bundle().apply { putString("output_id", firstId); putInt("offset", bytes.size()) })
                val chunk = checkNotNull(chunks.poll(5, TimeUnit.SECONDS)); check(chunk.getInt("output_offset") == bytes.size())
                bytes.write(checkNotNull(chunk.getByteArray("output_chunk")))
            }
            check(bytes.size() > 0)
            // A raw PendingIntent.send from a background test UID can be denied
            // by Android BAL. Exercise the actual user/SystemUI notification tap.
            progress.put("active_notices_before_tap", notifications.activeNotifications.joinToString { "${it.tag}/${it.id}" })
            tapSystemNotification("Ответ готов")
            until {
                var shown = false
                test.runOnMainSync { shown = views(activity!!.window.decorView).filterIsInstance<android.widget.TextView>()
                    .any { it.text.toString() == bytes.toString("UTF-8") } }
                shown
            }
            until { notifications.activeNotifications.none { it.tag == firstId } }
            // Replace the temporary answer, then use the old immutable deep link.
            val replacement = question()
            check(replacement.getString("output_id") != firstId)
            // An old PendingIntent must never show the replacement answer.
            tapSystemNotification("MotoAI — локальная модель")
            until {
                var focused = false
                test.runOnMainSync { focused = activity!!.hasWindowFocus() }
                focused
            }
            notice.notification.contentIntent.send()
            until {
                var unavailable = false
                test.runOnMainSync { unavailable = views(activity!!.window.decorView).filterIsInstance<android.widget.TextView>()
                    .any { it.text.toString().contains("Этот временный ответ уже недоступен") } }
                unavailable
            }
            send(RuntimeService.UNLOAD); await { it.optString("state") == "unloaded" }
            return JSONObject().put("passed", true).put("transport", "RAM only")
                .put("checks", "Minimize after question; real completion notification; exact answer reopened and acknowledged; expired answer ID cannot show a replacement")
        } finally {
            runCatching { android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("cmd statusbar collapse"))
                .use { it.readBytes() } }
            runCatching { send(RuntimeService.STOP); send(RuntimeService.UNLOAD); send(RuntimeService.UNREGISTER) }
            context.unbindService(connection)
            test.runOnMainSync { activity?.finish() }
            notifications.activeNotifications.filter { it.id == AnswerNotifications.ID }.forEach { notifications.cancel(it.tag, it.id) }
        }
    }
}
