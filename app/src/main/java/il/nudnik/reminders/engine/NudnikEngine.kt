package il.nudnik.reminders.engine

import il.nudnik.reminders.calendar.CalendarSource
import il.nudnik.reminders.calendar.Trigger
import il.nudnik.reminders.data.NudnikSettings
import il.nudnik.reminders.data.SettingsRepository
import il.nudnik.reminders.data.db.AlertEntity
import il.nudnik.reminders.data.db.AlertEntity.Companion.STATE_ACTIVE
import il.nudnik.reminders.data.db.AlertEntity.Companion.STATE_DISMISSED
import il.nudnik.reminders.data.db.AlertEntity.Companion.STATE_EXHAUSTED
import il.nudnik.reminders.data.db.AlertEntity.Companion.STATE_SNOOZED
import il.nudnik.reminders.data.db.AlertEntity.Companion.TYPE_EVENT
import il.nudnik.reminders.data.db.AlertEntity.Companion.TYPE_TASK
import il.nudnik.reminders.data.db.AlertEntity.Companion.TYPE_TEST
import il.nudnik.reminders.data.db.AppDatabase
import il.nudnik.reminders.data.db.TaskEntity
import il.nudnik.reminders.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The heart of the app. Every entry point (alarm, boot, worker, UI, notification action)
 * funnels into here. It:
 *  1. scans the calendar for reminder moments since the last scan and arms nag alerts,
 *  2. arms alerts for overdue tasks,
 *  3. fires (repeats) every alert whose time has come, honoring quiet hours and max repeats,
 *  4. schedules a single exact alarm for the next moment anything needs to happen.
 */
class NudnikEngine(
    private val settings: SettingsRepository,
    db: AppDatabase,
    private val calendar: CalendarSource,
    private val notifier: Notifier,
    private val scheduler: AlarmScheduler,
) {
    private val mutex = Mutex()
    private val alerts = db.alertDao()
    private val tasks = db.taskDao()

    suspend fun run() = locked { }

    suspend fun dismiss(alertId: Long) = locked {
        alerts.byId(alertId)?.let { dismissInternal(it) }
    }

    suspend fun snooze(alertId: Long, minutes: Int) = locked {
        alerts.byId(alertId)?.let { snoozeInternal(it, minutes, settings.get()) }
    }

    suspend fun dismissAll() = locked {
        alerts.pending().forEach { dismissInternal(it) }
    }

    suspend fun snoozeAll(minutes: Int) = locked {
        val s = settings.get()
        alerts.pending().forEach { snoozeInternal(it, minutes, s) }
    }

    /** Notification swiped away by the user. */
    suspend fun swiped(alertId: Long) = locked {
        if (settings.get().swipeDismisses) alerts.byId(alertId)?.let { dismissInternal(it) }
    }

    suspend fun completeTask(taskId: Long) = locked {
        tasks.byId(taskId)?.let {
            tasks.update(it.copy(done = true, completedAt = System.currentTimeMillis()))
        }
        alerts.openForTask(taskId).forEach { dismissInternal(it) }
    }

    /** Called after a task is created/edited/deleted from the UI. */
    suspend fun taskChanged(taskId: Long) = locked {
        val t = tasks.byId(taskId)
        val currentKey = t?.let { taskKey(it) }
        alerts.openForTask(taskId).forEach {
            if (t == null || t.done || it.key != currentKey) dismissInternal(it)
        }
    }

    suspend fun testAlert() = locked {
        val now = System.currentTimeMillis()
        alerts.insert(
            AlertEntity(
                key = "X:$now", type = TYPE_TEST, refId = 0, title = "בדיקת נודניק",
                location = "ככה תיראה תזכורת", begin = now, state = STATE_ACTIVE, nextAt = now,
                updatedAt = now,
            )
        )
    }

    // ------------------------------------------------------------------------

    private suspend fun locked(action: suspend () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            action()
            process()
        }
    }

    private suspend fun process() {
        val s = settings.get()
        notifier.ensureChannels(s)
        val now = System.currentTimeMillis()

        if (!s.enabled) {
            notifier.cancelAll()
            scheduler.cancel()
            settings.setLastScan(now)
            return
        }

        // 1. Calendar
        var nextCalendarTrigger: Long? = null
        if (calendar.hasPermission()) {
            val last = settings.lastScan()
            val from = if (last <= 0) now - FIRST_RUN_LOOKBACK else maxOf(last, now - MAX_LOOKBACK)
            val triggers = calendar.triggers(from, now + LOOKAHEAD, s)
            for (t in triggers) if (t.at in (from + 1)..now) armEvent(t, now)
            nextCalendarTrigger = triggers.asSequence().map { it.at }.filter { it > now }.minOrNull()
            settings.setLastScan(now)
        }

        // 2. Tasks
        if (s.taskNagging) tasks.dueUndone(now).forEach { armTask(it, now) }

        // 3. Auto-stop when the event is over
        if (s.stopAtEventEnd) {
            alerts.pending()
                .filter { it.type == TYPE_EVENT && it.end in 1..now }
                .forEach { dismissInternal(it) }
        }

        // 4. Fire what is due
        val due = alerts.due(now)
        if (due.isNotEmpty()) {
            if (QuietHours.isQuiet(s, now)) {
                val quietEnd = QuietHours.nextEnd(s, now)
                due.forEach {
                    val u = it.copy(nextAt = quietEnd, updatedAt = now)
                    alerts.update(u)
                    notifier.show(u, s, alerting = false, fullScreen = false)
                }
            } else {
                fire(due, s, now)
            }
        }

        // 5. Schedule next wake-up
        val next = listOfNotNull(
            nextCalendarTrigger,
            alerts.nextWake(),
            if (s.taskNagging) tasks.nextDue(now) else null,
        ).minOrNull()
        if (next != null) scheduler.schedule(maxOf(next, now + 1_000)) else scheduler.cancel()

        alerts.purgeDismissed(now - 7L * DAY)
    }

    private suspend fun fire(due: List<AlertEntity>, s: NudnikSettings, now: Long) {
        val interval = s.repeatIntervalMin.coerceAtLeast(1) * 60_000L
        due.forEachIndexed { index, a ->
            val count = a.count + 1
            val exhausted = s.maxRepetitions in 1..count
            val u = a.copy(
                count = count,
                state = if (exhausted) STATE_EXHAUSTED else STATE_ACTIVE,
                nextAt = if (exhausted) Long.MAX_VALUE else now + interval,
                updatedAt = now,
            )
            alerts.update(u)
            // Only the first one makes noise / pops up; the popup lists all of them.
            notifier.show(u, s, alerting = index == 0, fullScreen = s.popup && index == 0)
        }
    }

    private suspend fun armEvent(t: Trigger, now: Long) {
        val i = t.instance
        val key = "E:${i.eventId}:${i.rawBegin}"
        val title = i.title.ifBlank { "(ללא כותרת)" }
        val existing = alerts.byKey(key)
        if (existing == null) {
            alerts.insert(
                AlertEntity(
                    key = key, type = TYPE_EVENT, refId = i.eventId, title = title,
                    location = i.location, begin = i.begin, end = i.end, allDay = i.allDay,
                    state = STATE_ACTIVE, nextAt = now, lastSourceTrigger = t.at, updatedAt = now,
                )
            )
        } else if (existing.lastSourceTrigger < t.at) {
            // A further reminder of the same event (e.g. 2h and again 1h before) re-arms it.
            alerts.update(
                existing.copy(
                    title = title, location = i.location, begin = i.begin, end = i.end,
                    state = STATE_ACTIVE, count = 0, nextAt = now, lastSourceTrigger = t.at,
                    updatedAt = now,
                )
            )
        }
    }

    private fun taskKey(t: TaskEntity) = "T:${t.id}:${t.dueAt}"

    private suspend fun armTask(t: TaskEntity, now: Long) {
        val key = taskKey(t)
        if (alerts.byKey(key) != null) return
        alerts.insert(
            AlertEntity(
                key = key, type = TYPE_TASK, refId = t.id, title = t.title,
                location = t.notes, begin = t.dueAt ?: now, state = STATE_ACTIVE,
                nextAt = now, lastSourceTrigger = t.dueAt ?: now, updatedAt = now,
            )
        )
    }

    private suspend fun dismissInternal(a: AlertEntity) {
        alerts.update(a.copy(state = STATE_DISMISSED, updatedAt = System.currentTimeMillis()))
        notifier.cancel(a.id)
    }

    private suspend fun snoozeInternal(a: AlertEntity, minutes: Int, s: NudnikSettings) {
        val now = System.currentTimeMillis()
        val u = a.copy(
            state = STATE_SNOOZED, count = 0,
            nextAt = now + minutes.coerceAtLeast(1) * 60_000L, updatedAt = now,
        )
        alerts.update(u)
        notifier.show(u, s, alerting = false, fullScreen = false)
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val DAY = 24 * 60 * MINUTE
        /** How far back we look for missed reminders (device off, app killed...). */
        private const val MAX_LOOKBACK = 12 * 60 * MINUTE
        private const val FIRST_RUN_LOOKBACK = 2 * MINUTE
        private const val LOOKAHEAD = 2 * DAY
    }
}
