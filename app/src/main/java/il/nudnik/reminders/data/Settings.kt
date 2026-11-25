package il.nudnik.reminders.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ShakeAction { NONE, DISMISS, SNOOZE }

data class NudnikSettings(
    val enabled: Boolean = true,
    /** Minutes between repeated nags. */
    val repeatIntervalMin: Int = 5,
    /** 0 = repeat until handled. */
    val maxRepetitions: Int = 0,
    val snoozeMin: Int = 10,
    val soundEnabled: Boolean = true,
    /** null = system default notification sound. */
    val soundUri: String? = null,
    val vibrate: Boolean = true,
    val vibrationPattern: Int = 0,
    val led: Boolean = true,
    val ledColor: Int = 0xFF2196F3.toInt(),
    val popup: Boolean = true,
    val shakeAction: ShakeAction = ShakeAction.SNOOZE,
    val quietEnabled: Boolean = false,
    val quietStartMin: Int = 22 * 60,
    val quietEndMin: Int = 7 * 60,
    val notifyWithoutReminders: Boolean = false,
    val defaultReminderMin: Int = 10,
    val ignoreAllDay: Boolean = true,
    val ignoreDeclined: Boolean = true,
    val stopAtEventEnd: Boolean = false,
    val swipeDismisses: Boolean = true,
    val excludedCalendars: Set<String> = emptySet(),
    val taskNagging: Boolean = true,
    /** Bumped whenever sound/vibration/light change, because channels are immutable. */
    val channelVersion: Int = 1,
) {
    fun alertStyleDiffers(o: NudnikSettings) =
        soundEnabled != o.soundEnabled || soundUri != o.soundUri || vibrate != o.vibrate ||
            vibrationPattern != o.vibrationPattern || led != o.led || ledColor != o.ledColor

    companion object {
        val VIBRATION_PATTERNS: List<LongArray> = listOf(
            longArrayOf(0, 300, 200, 300),
            longArrayOf(0, 1000, 500, 1000, 500, 1000),
            longArrayOf(0, 200, 100, 200, 100, 200, 300, 600, 100, 600, 100, 600, 300, 200, 100, 200, 100, 200),
        )
        val VIBRATION_NAMES = listOf("קצר", "ארוך", "SOS")
    }
}

private val Context.dataStore by preferencesDataStore(name = "nudnik_settings")

private object K {
    val enabled = booleanPreferencesKey("enabled")
    val interval = intPreferencesKey("repeat_interval")
    val maxReps = intPreferencesKey("max_reps")
    val snooze = intPreferencesKey("snooze")
    val soundEnabled = booleanPreferencesKey("sound_enabled")
    val soundUri = stringPreferencesKey("sound_uri")
    val vibrate = booleanPreferencesKey("vibrate")
    val vibPattern = intPreferencesKey("vib_pattern")
    val led = booleanPreferencesKey("led")
    val ledColor = intPreferencesKey("led_color")
    val popup = booleanPreferencesKey("popup")
    val shake = stringPreferencesKey("shake")
    val quiet = booleanPreferencesKey("quiet")
    val quietStart = intPreferencesKey("quiet_start")
    val quietEnd = intPreferencesKey("quiet_end")
    val noReminders = booleanPreferencesKey("notify_without_reminders")
    val defaultReminder = intPreferencesKey("default_reminder")
    val ignoreAllDay = booleanPreferencesKey("ignore_all_day")
    val ignoreDeclined = booleanPreferencesKey("ignore_declined")
    val stopAtEnd = booleanPreferencesKey("stop_at_end")
    val swipe = booleanPreferencesKey("swipe_dismisses")
    val excluded = stringSetPreferencesKey("excluded_calendars")
    val tasks = booleanPreferencesKey("task_nagging")
    val channel = intPreferencesKey("channel_version")
    val lastScan = longPreferencesKey("last_scan")
}

private fun Preferences.toSettings(): NudnikSettings {
    val d = NudnikSettings()
    return NudnikSettings(
        enabled = this[K.enabled] ?: d.enabled,
        repeatIntervalMin = this[K.interval] ?: d.repeatIntervalMin,
        maxRepetitions = this[K.maxReps] ?: d.maxRepetitions,
        snoozeMin = this[K.snooze] ?: d.snoozeMin,
        soundEnabled = this[K.soundEnabled] ?: d.soundEnabled,
        soundUri = this[K.soundUri]?.takeIf { it.isNotEmpty() },
        vibrate = this[K.vibrate] ?: d.vibrate,
        vibrationPattern = this[K.vibPattern] ?: d.vibrationPattern,
        led = this[K.led] ?: d.led,
        ledColor = this[K.ledColor] ?: d.ledColor,
        popup = this[K.popup] ?: d.popup,
        shakeAction = this[K.shake]?.let { runCatching { ShakeAction.valueOf(it) }.getOrNull() } ?: d.shakeAction,
        quietEnabled = this[K.quiet] ?: d.quietEnabled,
        quietStartMin = this[K.quietStart] ?: d.quietStartMin,
        quietEndMin = this[K.quietEnd] ?: d.quietEndMin,
        notifyWithoutReminders = this[K.noReminders] ?: d.notifyWithoutReminders,
        defaultReminderMin = this[K.defaultReminder] ?: d.defaultReminderMin,
        ignoreAllDay = this[K.ignoreAllDay] ?: d.ignoreAllDay,
        ignoreDeclined = this[K.ignoreDeclined] ?: d.ignoreDeclined,
        stopAtEventEnd = this[K.stopAtEnd] ?: d.stopAtEventEnd,
        swipeDismisses = this[K.swipe] ?: d.swipeDismisses,
        excludedCalendars = this[K.excluded] ?: d.excludedCalendars,
        taskNagging = this[K.tasks] ?: d.taskNagging,
        channelVersion = this[K.channel] ?: d.channelVersion,
    )
}

private fun MutablePreferences.write(s: NudnikSettings) {
    this[K.enabled] = s.enabled
    this[K.interval] = s.repeatIntervalMin
    this[K.maxReps] = s.maxRepetitions
    this[K.snooze] = s.snoozeMin
    this[K.soundEnabled] = s.soundEnabled
    this[K.soundUri] = s.soundUri ?: ""
    this[K.vibrate] = s.vibrate
    this[K.vibPattern] = s.vibrationPattern
    this[K.led] = s.led
    this[K.ledColor] = s.ledColor
    this[K.popup] = s.popup
    this[K.shake] = s.shakeAction.name
    this[K.quiet] = s.quietEnabled
    this[K.quietStart] = s.quietStartMin
    this[K.quietEnd] = s.quietEndMin
    this[K.noReminders] = s.notifyWithoutReminders
    this[K.defaultReminder] = s.defaultReminderMin
    this[K.ignoreAllDay] = s.ignoreAllDay
    this[K.ignoreDeclined] = s.ignoreDeclined
    this[K.stopAtEnd] = s.stopAtEventEnd
    this[K.swipe] = s.swipeDismisses
    this[K.excluded] = s.excludedCalendars
    this[K.tasks] = s.taskNagging
    this[K.channel] = s.channelVersion
}

class SettingsRepository(private val context: Context) {
    val flow: Flow<NudnikSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun get(): NudnikSettings = flow.first()

    suspend fun update(transform: (NudnikSettings) -> NudnikSettings) {
        context.dataStore.edit { prefs ->
            val old = prefs.toSettings()
            var new = transform(old)
            if (new.alertStyleDiffers(old)) new = new.copy(channelVersion = old.channelVersion + 1)
            prefs.write(new)
        }
    }

    suspend fun lastScan(): Long = context.dataStore.data.first()[K.lastScan] ?: 0L

    suspend fun setLastScan(value: Long) {
        context.dataStore.edit { it[K.lastScan] = value }
    }
}
