package il.nudnik.reminders.ui.screens

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.nudnik.reminders.data.NudnikSettings
import il.nudnik.reminders.data.ShakeAction
import il.nudnik.reminders.graph
import il.nudnik.reminders.ui.Format
import il.nudnik.reminders.ui.MainViewModel

private val LED_COLORS = listOf(
    Color(0xFF2196F3), Color(0xFFF44336), Color(0xFF4CAF50),
    Color(0xFFFFEB3B), Color(0xFF9C27B0), Color(0xFFFFFFFF),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val update = vm::updateSettings
    var timeDialog by remember { mutableStateOf<Int?>(null) } // 0 = quiet start, 1 = quiet end

    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            val uri = IntentCompat.getParcelableExtra(r.data!!, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            update { it.copy(soundUri = uri?.toString(), soundEnabled = uri != null) }
        }
    }
    val soundName = remember(s.soundUri, s.soundEnabled) {
        if (!s.soundEnabled) "שקט"
        else s.soundUri?.let { runCatching { RingtoneManager.getRingtone(ctx, it.toUri())?.getTitle(ctx) }.getOrNull() }
            ?: "צליל ברירת המחדל"
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        Group("הצקות")
        Stepper("כל כמה דקות להזכיר שוב", s.repeatIntervalMin, 1..120, if (s.repeatIntervalMin < 10) 1 else 5) { v ->
            update { it.copy(repeatIntervalMin = v) }
        }
        Stepper(
            "מספר חזרות מרבי", s.maxRepetitions, 0..100, 1,
            format = { if (it == 0) "ללא הגבלה" else "$it" },
        ) { v -> update { it.copy(maxRepetitions = v) } }
        Stepper("משך נודניק (דקות)", s.snoozeMin, 1..240, if (s.snoozeMin < 15) 1 else 5) { v ->
            update { it.copy(snoozeMin = v) }
        }

        Group("איך להציק")
        ListItem(
            headlineContent = { Text("צליל") },
            supportingContent = { Text(soundName) },
            modifier = Modifier.clickable {
                soundPicker.launch(
                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, s.soundUri?.toUri())
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "צליל נודניק")
                )
            },
        )
        Toggle("רטט", s.vibrate) { v -> update { it.copy(vibrate = v) } }
        if (s.vibrate) {
            Chips(NudnikSettings.VIBRATION_NAMES, s.vibrationPattern) { i -> update { it.copy(vibrationPattern = i) } }
        }
        Toggle("נורית התראה (במכשירים שיש להם)", s.led) { v -> update { it.copy(led = v) } }
        if (s.led) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LED_COLORS.forEach { c ->
                    val selected = c.toArgb() == s.ledColor
                    Row(
                        Modifier.size(32.dp).clip(CircleShape).background(c)
                            .border(if (selected) 3.dp else 1.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { update { it.copy(ledColor = c.toArgb()) } }
                    ) {}
                }
            }
        }
        Toggle("חלון קופץ (גם על מסך הנעילה)", s.popup) { v -> update { it.copy(popup = v) } }
        Text("ניעור המכשיר כשהחלון פתוח:", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Chips(listOf("לא כלום", "סגור", "נודניק"), s.shakeAction.ordinal) { i ->
            update { it.copy(shakeAction = ShakeAction.entries[i]) }
        }
        Toggle("החלקת ההתראה סוגרת אותה", s.swipeDismisses) { v -> update { it.copy(swipeDismisses = v) } }
        ListItem(
            headlineContent = { Text("הגדרות התראה מתקדמות של המערכת") },
            supportingContent = { Text("עקיפת 'נא לא להפריע', צליל ותצוגה במסך הנעילה") },
            modifier = Modifier.clickable {
                ctx.startActivity(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, ctx.graph.notifier.nagChannelId(s))
                )
            },
        )

        Group("שעות שקטות")
        Toggle("לא להציק בשעות מסוימות", s.quietEnabled) { v -> update { it.copy(quietEnabled = v) } }
        if (s.quietEnabled) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("מ-")
                OutlinedButton(onClick = { timeDialog = 0 }) { Text(Format.minutesOfDay(s.quietStartMin)) }
                Text("עד")
                OutlinedButton(onClick = { timeDialog = 1 }) { Text(Format.minutesOfDay(s.quietEndMin)) }
            }
            Text(
                "תזכורות שיגיעו בזמן הזה יוצגו בשקט ויתחילו להציק בסוף השעות השקטות.",
                Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall,
            )
        }

        Group("אירועי יומן")
        Toggle("להציק גם על אירועים בלי תזכורת", s.notifyWithoutReminders) { v ->
            update { it.copy(notifyWithoutReminders = v) }
        }
        Stepper("תזכורת ברירת מחדל (דקות לפני)", s.defaultReminderMin, 0..1440, if (s.defaultReminderMin < 30) 5 else 15) { v ->
            update { it.copy(defaultReminderMin = v) }
        }
        Toggle("להתעלם מאירועים של כל היום", s.ignoreAllDay) { v -> update { it.copy(ignoreAllDay = v) } }
        Toggle("להתעלם מאירועים שדחיתי", s.ignoreDeclined) { v -> update { it.copy(ignoreDeclined = v) } }
        Toggle("להפסיק להציק כשהאירוע מסתיים", s.stopAtEventEnd) { v -> update { it.copy(stopAtEventEnd = v) } }

        if (calendars.isNotEmpty()) {
            Text("יומנים לניטור:", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            calendars.forEach { c ->
                val included = c.id.toString() !in s.excludedCalendars
                ListItem(
                    leadingContent = {
                        Checkbox(checked = included, onCheckedChange = { on ->
                            update {
                                it.copy(
                                    excludedCalendars = if (on) it.excludedCalendars - c.id.toString()
                                    else it.excludedCalendars + c.id.toString()
                                )
                            }
                        })
                    },
                    headlineContent = { Text(c.name) },
                    supportingContent = { Text(c.account) },
                    trailingContent = { Row(Modifier.size(16.dp).clip(CircleShape).background(Color(c.color))) {} },
                )
            }
        }

        Group("משימות")
        Toggle("להציק על משימות שהגיע מועדן", s.taskNagging) { v -> update { it.copy(taskNagging = v) } }

        Text(
            "נודניק · גרסה 5.0 · עובד עם כל אפליקציית יומן שמסונכרנת ליומן של אנדרואיד (Google Calendar, Business Calendar, Jorte ועוד).",
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    timeDialog?.let { which ->
        val initial = if (which == 0) s.quietStartMin else s.quietEndMin
        val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timeDialog = null },
            title = { Text(if (which == 0) "תחילת שעות שקטות" else "סוף שעות שקטות") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val m = state.hour * 60 + state.minute
                    update { if (which == 0) it.copy(quietStartMin = m) else it.copy(quietEndMin = m) }
                    timeDialog = null
                }) { Text("אישור") }
            },
            dismissButton = { TextButton(onClick = { timeDialog = null }) { Text("ביטול") } },
        )
    }
}

@Composable
private fun Group(title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
    Text(
        title,
        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun Chips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l ->
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(l) })
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    format: (Int) -> String = { "$it" },
    onChange: (Int) -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) {
                    Text("−", style = MaterialTheme.typography.titleLarge)
                }
                Text(format(value), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) {
                    Text("+", style = MaterialTheme.typography.titleLarge)
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
