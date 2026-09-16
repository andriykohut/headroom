package dev.andrii.headroom.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.andrii.headroom.R
import dev.andrii.headroom.domain.NotificationEvent
import dev.andrii.headroom.domain.TriggerType

class AndroidNotifier(private val context: Context) : Notifier {

    private val manager = NotificationManagerCompat.from(context)

    init {
        TriggerType.entries.forEach { type ->
            manager.createNotificationChannel(
                NotificationChannel(
                    channelIdFor(type),
                    channelNameFor(type),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        manager.createNotificationChannel(
            NotificationChannel(
                RELAY_REJECTED_CHANNEL_ID,
                "Your relay refused this phone",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    // The launcher's own intent, so a tap resumes the existing task instead of
    // stacking a second copy of the screen on top of it.
    private val openApp: PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

    override fun notify(event: NotificationEvent) {
        post(
            channelId = channelIdFor(event.key.type),
            id = notificationIdFor(event.key),
            title = event.title,
            body = event.body,
        )
    }

    override fun notifyRelayRejected(message: String) {
        post(
            channelId = RELAY_REJECTED_CHANNEL_ID,
            id = RELAY_REJECTED_NOTIFICATION_ID,
            title = "Your relay refused this phone",
            body = message,
        )
    }

    private fun post(channelId: String, id: Int, title: String, body: String) {
        val notification = NotificationCompat.Builder(context, channelId)
            // The app's own mark, not a system glyph: Android flattens this to
            // a silhouette, which is what the two-paths-and-a-gap shape is for.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        // POST_NOTIFICATIONS may be denied; NotificationManagerCompat drops the
        // post rather than throwing, and the in-app UI still shows the state.
        manager.notify(id, notification)
    }

    private companion object {
        const val RELAY_REJECTED_NOTIFICATION_ID = 1
    }
}
