package il.nudnik.reminders.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import il.nudnik.reminders.receivers.AlarmReceiver

class AlarmScheduler(private val context: Context) {
    private val am: AlarmManager = requireNotNull(context.getSystemService(AlarmManager::class.java))

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun canExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    fun schedule(at: Long) {
        val pi = pendingIntent()
        try {
            if (canExact()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel() = am.cancel(pendingIntent())
}
