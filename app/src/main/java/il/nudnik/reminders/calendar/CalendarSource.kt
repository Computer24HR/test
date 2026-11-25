package il.nudnik.reminders.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import il.nudnik.reminders.data.NudnikSettings
import java.util.TimeZone

data class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int)

data class CalInstance(
    val eventId: Long,
    /** Raw provider begin (UTC midnight for all-day events). Used as the identity key. */
    val rawBegin: Long,
    /** Begin/end in local wall-clock millis, for display and trigger math. */
    val begin: Long,
    val end: Long,
    val title: String,
    val location: String,
    val allDay: Boolean,
    val calendarId: Long,
    val selfStatus: Int,
)

data class Trigger(val instance: CalInstance, val at: Long)

data class UpcomingEvent(val instance: CalInstance, val nextTrigger: Long?, val hasReminder: Boolean)

class CalendarSource(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun calendars(): List<CalendarInfo> {
        if (!hasPermission()) return emptyList()
        val out = mutableListOf<CalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null,
            "${CalendarContract.Calendars.ACCOUNT_NAME}, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME}"
        )?.use { c ->
            while (c.moveToNext()) {
                out += CalendarInfo(
                    id = c.getLong(0),
                    name = c.getString(1) ?: "",
                    account = c.getString(2) ?: "",
                    color = c.getInt(3),
                )
            }
        }
        return out
    }

    fun instances(from: Long, to: Long): List<CalInstance> {
        if (!hasPermission()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val selection = "(${CalendarContract.Instances.STATUS} IS NULL OR " +
            "${CalendarContract.Instances.STATUS} != ${CalendarContract.Events.STATUS_CANCELED})"
        val out = mutableListOf<CalInstance>()
        val tz = TimeZone.getDefault()
        try {
            context.contentResolver.query(uri, projection, selection, null,
                "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val rawBegin = c.getLong(1)
                    val rawEnd = c.getLong(2)
                    val allDay = c.getInt(5) == 1
                    // All-day events are stored as UTC midnight; shift to local midnight.
                    val begin = if (allDay) rawBegin - tz.getOffset(rawBegin) else rawBegin
                    val end = if (allDay) rawEnd - tz.getOffset(rawEnd) else rawEnd
                    out += CalInstance(
                        eventId = c.getLong(0),
                        rawBegin = rawBegin,
                        begin = begin,
                        end = end,
                        title = c.getString(3) ?: "",
                        location = c.getString(4) ?: "",
                        allDay = allDay,
                        calendarId = c.getLong(6),
                        selfStatus = c.getInt(7),
                    )
                }
            }
        } catch (_: SecurityException) {
        }
        return out
    }

    /** eventId -> reminder minutes (only on-device alert style reminders). */
    fun reminders(eventIds: Collection<Long>): Map<Long, List<Int>> {
        if (eventIds.isEmpty() || !hasPermission()) return emptyMap()
        val out = HashMap<Long, MutableList<Int>>()
        val methods = listOf(
            CalendarContract.Reminders.METHOD_DEFAULT,
            CalendarContract.Reminders.METHOD_ALERT,
            CalendarContract.Reminders.METHOD_ALARM,
        ).joinToString(",")
        eventIds.distinct().chunked(200).forEach { chunk ->
            val selection = "${CalendarContract.Reminders.EVENT_ID} IN (${chunk.joinToString(",")}) AND " +
                "${CalendarContract.Reminders.METHOD} IN ($methods)"
            try {
                context.contentResolver.query(
                    CalendarContract.Reminders.CONTENT_URI,
                    arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES),
                    selection, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        out.getOrPut(c.getLong(0)) { mutableListOf() } += c.getInt(1)
                    }
                }
            } catch (_: SecurityException) {
            }
        }
        return out
    }

    private fun filtered(list: List<CalInstance>, s: NudnikSettings) = list.filter { i ->
        (!s.ignoreAllDay || !i.allDay) &&
            (!s.ignoreDeclined || i.selfStatus != CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) &&
            i.calendarId.toString() !in s.excludedCalendars
    }

    private fun minutesFor(i: CalInstance, rem: Map<Long, List<Int>>, s: NudnikSettings): List<Int> {
        val list = rem[i.eventId].orEmpty().map {
            if (it == CalendarContract.Reminders.MINUTES_DEFAULT) s.defaultReminderMin else it
        }
        return if (list.isEmpty() && s.notifyWithoutReminders) listOf(s.defaultReminderMin) else list
    }

    /** All reminder trigger moments falling in [from, to]. */
    fun triggers(from: Long, to: Long, s: NudnikSettings): List<Trigger> {
        val inst = filtered(instances(from, to + MAX_LEAD_MS), s)
        val rem = reminders(inst.map { it.eventId })
        val out = mutableListOf<Trigger>()
        for (i in inst) {
            for (m in minutesFor(i, rem, s)) {
                val at = i.begin - m * 60_000L
                if (at in from..to) out += Trigger(i, at)
            }
        }
        return out.sortedBy { it.at }
    }

    fun upcoming(s: NudnikSettings, now: Long, windowMs: Long): List<UpcomingEvent> {
        val inst = filtered(instances(now, now + windowMs), s).filter { it.end > now }
        val rem = reminders(inst.map { it.eventId })
        return inst.map { i ->
            val mins = minutesFor(i, rem, s)
            UpcomingEvent(
                instance = i,
                nextTrigger = mins.map { i.begin - it * 60_000L }.filter { it > now }.minOrNull(),
                hasReminder = mins.isNotEmpty(),
            )
        }
    }

    companion object {
        /** Reminders set further ahead than this are ignored. */
        const val MAX_LEAD_MS = 8L * 24 * 60 * 60 * 1000
    }
}
