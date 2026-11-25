package il.nudnik.reminders.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import il.nudnik.reminders.appScope
import il.nudnik.reminders.graph
import kotlinx.coroutines.launch

/** Runs [block] off the main thread while keeping the broadcast alive. */
private fun BroadcastReceiver.runAsync(context: Context, block: suspend () -> Unit) {
    val pending = goAsync()
    context.appScope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/** The single exact alarm the engine schedules. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runAsync(context) { context.graph.engine.run() }
    }

    companion object {
        const val ACTION_TICK = "il.nudnik.reminders.TICK"
    }
}

/** Boot, app update, clock/timezone change, exact-alarm permission granted. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runAsync(context) { context.graph.engine.run() }
    }
}

/** Calendar provider's own reminder broadcast – a nudge to rescan immediately. */
class CalendarReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runAsync(context) { context.graph.engine.run() }
    }
}

/** Buttons on the notification. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alertId = intent.getLongExtra(EXTRA_ALERT_ID, -1)
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0)
        val engine = context.graph.engine
        runAsync(context) {
            when (intent.action) {
                ACTION_DISMISS -> engine.dismiss(alertId)
                ACTION_SNOOZE -> engine.snooze(alertId, context.graph.settings.get().snoozeMin)
                ACTION_DONE -> engine.completeTask(taskId)
                ACTION_SWIPED -> engine.swiped(alertId)
            }
        }
    }

    companion object {
        const val ACTION_DISMISS = "il.nudnik.reminders.DISMISS"
        const val ACTION_SNOOZE = "il.nudnik.reminders.SNOOZE"
        const val ACTION_DONE = "il.nudnik.reminders.DONE"
        const val ACTION_SWIPED = "il.nudnik.reminders.SWIPED"
        const val EXTRA_ALERT_ID = "alert_id"
        const val EXTRA_TASK_ID = "task_id"

        fun code(action: String) = when (action) {
            ACTION_DISMISS -> 1
            ACTION_SNOOZE -> 2
            ACTION_DONE -> 3
            else -> 4
        }
    }
}
