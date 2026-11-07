package dev.redflag.app.context

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.redflag.app.BuildConfig
import dev.redflag.app.graph
import dev.redflag.core.followup.ContextSources

/**
 * Context analyzer, automatic context (B): keeps recent messenger/SMS/mail notifications in [NotificationBuffer]
 * (memory only). Nothing is read from the buffer unless the user allows it for one analysis.
 */
class ContextNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!applicationContext.graph.settings.contextEnabled) return
        // DEBUG demo only: `adb shell cmd notification post` notifications stand in for an SMS (Messages app)
        val pkg = if (BuildConfig.DEBUG && sbn.packageName == "com.android.shell") DEMO_SMS_PACKAGE else sbn.packageName
        val cat = ContextSources.classify(pkg).category
        if (cat !in CHAT_CATEGORIES) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val thread = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: title.ifEmpty { sbn.key }
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        if (style != null && style.messages.isNotEmpty()) {
            for (m in style.messages) {
                val text = m.text?.toString() ?: continue
                NotificationBuffer.add(NotificationBuffer.Entry(pkg, thread, m.person?.name?.toString() ?: "Me", text, m.timestamp.takeIf { it > 0 } ?: sbn.postTime))
            }
        } else {
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return
            NotificationBuffer.add(NotificationBuffer.Entry(pkg, thread, title, text, sbn.postTime))
        }
    }

    companion object {
        val CHAT_CATEGORIES = setOf("messenger", "sms", "mail")
        const val DEMO_SMS_PACKAGE = "com.google.android.apps.messaging"

        fun hasAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun settingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, ContextNotificationListener::class.java).flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
