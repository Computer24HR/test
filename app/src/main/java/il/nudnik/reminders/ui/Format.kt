package il.nudnik.reminders.ui

import il.nudnik.reminders.data.db.AlertEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val he: Locale = Locale.forLanguageTag("he")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", he)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d/M", he)

    private fun zdt(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

    fun time(ms: Long): String = timeFmt.format(zdt(ms))

    fun minutesOfDay(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    fun day(ms: Long): String {
        val d = zdt(ms).toLocalDate()
        val today = LocalDate.now()
        return when (d) {
            today -> "היום"
            today.plusDays(1) -> "מחר"
            today.minusDays(1) -> "אתמול"
            else -> dayFmt.format(zdt(ms))
        }
    }

    fun dateTime(ms: Long): String = "${day(ms)} ${time(ms)}"

    fun alertWhen(a: AlertEntity): String = when (a.type) {
        AlertEntity.TYPE_TASK -> "יעד: ${dateTime(a.begin)}"
        AlertEntity.TYPE_TEST -> "עכשיו"
        else -> if (a.allDay) "${day(a.begin)} · כל היום" else dateTime(a.begin)
    }

    fun relative(target: Long, now: Long = System.currentTimeMillis()): String {
        val diffMin = (target - now) / 60_000
        return when {
            diffMin in -1..1 -> "עכשיו"
            diffMin > 0 && diffMin < 60 -> "בעוד $diffMin דק'"
            diffMin < 0 && diffMin > -60 -> "לפני ${-diffMin} דק'"
            diffMin > 0 && diffMin < 24 * 60 -> "בעוד ${diffMin / 60} שע'"
            else -> dateTime(target)
        }
    }
}
