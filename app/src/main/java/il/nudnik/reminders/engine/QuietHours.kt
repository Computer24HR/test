package il.nudnik.reminders.engine

import il.nudnik.reminders.data.NudnikSettings
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

object QuietHours {
    fun isQuiet(s: NudnikSettings, now: Long): Boolean {
        if (!s.quietEnabled || s.quietStartMin == s.quietEndMin) return false
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        val m = t.hour * 60 + t.minute
        return if (s.quietStartMin < s.quietEndMin) {
            m >= s.quietStartMin && m < s.quietEndMin
        } else {
            m >= s.quietStartMin || m < s.quietEndMin // crosses midnight
        }
    }

    fun nextEnd(s: NudnikSettings, now: Long): Long {
        val zone = ZoneId.systemDefault()
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        var end = t.toLocalDate().atTime(s.quietEndMin / 60, s.quietEndMin % 60).atZone(zone)
        if (!end.isAfter(t)) end = end.plusDays(1)
        return end.toInstant().toEpochMilli()
    }
}
