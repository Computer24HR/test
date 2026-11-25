package il.nudnik.reminders.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import il.nudnik.reminders.R
import il.nudnik.reminders.data.NudnikSettings
import il.nudnik.reminders.data.db.AlertEntity
import il.nudnik.reminders.receivers.ActionReceiver
import il.nudnik.reminders.ui.Format
import il.nudnik.reminders.ui.popup.PopupActivity

class Notifier(private val context: Context) {
    private val nm: NotificationManager = requireNotNull(context.getSystemService(NotificationManager::class.java))

    fun nagChannelId(s: NudnikSettings) = "$NAG_PREFIX${s.channelVersion}"

    /** Notification channels are immutable, so a new one is created when the alert style changes. */
    fun ensureChannels(s: NudnikSettings) {
        val id = nagChannelId(s)
        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, "תזכורות מציקות", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "תזכורות חוזרות לאירועי יומן ומשימות"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                if (s.soundEnabled) {
                    val uri = s.soundUri?.toUri() ?: Settings.System.DEFAULT_NOTIFICATION_URI
                    setSound(
                        uri,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    setSound(null, null)
                }
                enableVibration(s.vibrate)
                if (s.vibrate) {
                    vibrationPattern = NudnikSettings.VIBRATION_PATTERNS
                        .getOrElse(s.vibrationPattern) { NudnikSettings.VIBRATION_PATTERNS[0] }
                }
                enableLights(s.led)
                lightColor = s.ledColor
            }
            nm.createNotificationChannel(ch)
        }
        nm.notificationChannels
            .filter { it.id.startsWith(NAG_PREFIX) && it.id != id }
            .forEach { nm.deleteNotificationChannel(it.id) }

        if (nm.getNotificationChannel(CHANNEL_SILENT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SILENT, "תזכורות שקטות", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "תזכורות בנודניק, בשעות שקטות או אחרי סיום החזרות"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    fun canFullScreen(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || nm.canUseFullScreenIntent()

    fun show(a: AlertEntity, s: NudnikSettings, alerting: Boolean, fullScreen: Boolean) {
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return

        val channel = if (alerting) nagChannelId(s) else CHANNEL_SILENT
        val text = buildString {
            append(Format.alertWhen(a))
            if (a.location.isNotBlank()) append(" · ").append(a.location)
        }
        val status = when (a.state) {
            AlertEntity.STATE_SNOOZED -> "בנודניק עד ${Format.time(a.nextAt)}"
            AlertEntity.STATE_EXHAUSTED -> "הגעתי למספר החזרות המרבי"
            else -> if (a.count > 0) "תזכורת מס' ${a.count}" else ""
        }

        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_nudnik)
            .setContentTitle(a.title)
            .setContentText(text)
            .setSubText(status.ifEmpty { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(
                if (a.type == AlertEntity.TYPE_TASK) NotificationCompat.CATEGORY_REMINDER
                else NotificationCompat.CATEGORY_EVENT
            )
            .setPriority(if (alerting) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(!alerting)
            .setSilent(!alerting)
            .setShowWhen(true)
            .setWhen(a.begin)
            .setGroup(GROUP)
            .setContentIntent(popupIntent(a.id))
            .setDeleteIntent(actionIntent(ActionReceiver.ACTION_SWIPED, a.id))
            .addAction(0, "סגור", actionIntent(ActionReceiver.ACTION_DISMISS, a.id))
            .addAction(0, "נודניק ${s.snoozeMin} דק'", actionIntent(ActionReceiver.ACTION_SNOOZE, a.id))

        if (a.type == AlertEntity.TYPE_TASK) {
            b.addAction(0, "בוצע ✓", actionIntent(ActionReceiver.ACTION_DONE, a.id, a.refId))
        }
        if (fullScreen && canFullScreen()) {
            b.setFullScreenIntent(popupIntent(a.id), true)
        }
        @Suppress("DEPRECATION")
        if (s.led) b.setLights(s.ledColor, 1000, 2000)

        try {
            compat.notify(notificationId(a.id), b.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked
        }
    }

    fun cancel(alertId: Long) = nm.cancel(notificationId(alertId))

    fun cancelAll() = nm.cancelAll()

    private fun notificationId(alertId: Long) = (1000 + alertId % 1_000_000).toInt()

    private fun popupIntent(alertId: Long): PendingIntent = PendingIntent.getActivity(
        context, notificationId(alertId),
        Intent(context, PopupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .putExtra(PopupActivity.EXTRA_ALERT_ID, alertId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(action: String, alertId: Long, taskId: Long = 0): PendingIntent {
        val code = notificationId(alertId) * 8 + ActionReceiver.code(action)
        return PendingIntent.getBroadcast(
            context, code,
            Intent(context, ActionReceiver::class.java)
                .setAction(action)
                .putExtra(ActionReceiver.EXTRA_ALERT_ID, alertId)
                .putExtra(ActionReceiver.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val NAG_PREFIX = "nag_"
        const val CHANNEL_SILENT = "silent"
        private const val GROUP = "nudnik_alerts"
    }
}
