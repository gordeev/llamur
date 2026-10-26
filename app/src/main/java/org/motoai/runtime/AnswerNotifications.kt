package org.motoai.runtime

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Completion text never contains a prompt, an answer, or a conversation title. */
internal object AnswerNotifications {
    const val CHANNEL = "answers"
    const val ID = 2
    const val EXTRA = "answer_id"
    fun create(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, UiText.get(R.string.ui_completed_responses), NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun show(context: Context, answerId: String, product: Boolean = false,title:String=UiText.get(R.string.ui_response_ready)) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(context, if (product) ChatActivity::class.java else MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("llamur://answer/$answerId")).putExtra(EXTRA, answerId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(UiText.get(R.string.ui_tap_to_return_to_llamur))
            .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_MESSAGE).build()
        try { manager.notify(answerId, ID, notification) } catch (_: SecurityException) {
            // Revocation during generation must not turn a successful answer into an error.
        }
    }
    fun read(context: Context, answerId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(answerId, ID)
    }
}
