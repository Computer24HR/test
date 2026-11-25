package il.nudnik.reminders.ui.screens

import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.nudnik.reminders.calendar.UpcomingEvent
import il.nudnik.reminders.data.db.AlertEntity
import il.nudnik.reminders.ui.Format
import il.nudnik.reminders.ui.MainViewModel

@Composable
fun AlertsScreen(vm: MainViewModel, modifier: Modifier, header: @Composable () -> Unit) {
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { header() }

        item {
            SectionHeader("מציקים עכשיו") {
                if (alerts.isNotEmpty()) TextButton(onClick = vm::dismissAll) { Text("סגור הכל") }
                TextButton(onClick = vm::testAlert) { Text("בדיקה") }
            }
        }
        if (alerts.isEmpty()) {
            item { EmptyHint("אין תזכורות פעילות. כשתגיע תזכורת מהיומן או משימה תגיע ליעד – נודניק יתחיל להציק.") }
        }
        items(alerts, key = { "a${it.id}" }) { a ->
            ActiveAlertCard(
                a = a,
                snoozeMin = settings.snoozeMin,
                onDismiss = { vm.dismiss(a.id) },
                onSnooze = { m -> vm.snooze(a.id, m) },
            )
        }

        item { SectionHeader("אירועים ב-48 השעות הקרובות") {} }
        if (upcoming.isEmpty()) {
            item { EmptyHint("אין אירועים קרובים ביומנים שנבחרו (או שלא ניתנה גישה ליומן).") }
        }
        items(upcoming, key = { "u${it.instance.eventId}_${it.instance.rawBegin}" }) { u ->
            UpcomingRow(u)
            HorizontalDivider()
        }
    }
}

@Composable
fun SectionHeader(title: String, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun EmptyHint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun ActiveAlertCard(
    a: AlertEntity,
    snoozeMin: Int,
    onDismiss: () -> Unit,
    onSnooze: (Int) -> Unit,
    big: Boolean = false,
) {
    val ctx = LocalContext.current
    val container = when (a.state) {
        AlertEntity.STATE_SNOOZED -> MaterialTheme.colorScheme.surfaceVariant
        AlertEntity.STATE_EXHAUSTED -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                a.title,
                style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            )
            Text(Format.alertWhen(a), style = MaterialTheme.typography.bodyMedium)
            if (a.location.isNotBlank()) Text(a.location, style = MaterialTheme.typography.bodySmall)
            val status = when (a.state) {
                AlertEntity.STATE_SNOOZED -> "בנודניק עד ${Format.time(a.nextAt)}"
                AlertEntity.STATE_EXHAUSTED -> "הסתיימו החזרות (${a.count})"
                else -> if (a.count == 0) "ממתין להצקה" else "הזכרתי ${a.count} פעמים · הבא ב-${Format.time(a.nextAt)}"
            }
            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onDismiss) { Text("סגור") }
                OutlinedButton(onClick = { onSnooze(snoozeMin) }) { Text("נודניק $snoozeMin דק'") }
                if (a.type == AlertEntity.TYPE_EVENT) {
                    TextButton(onClick = {
                        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, a.refId)
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, uri)
                                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, a.begin)
                                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, a.end)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("פתח ביומן") }
                }
            }
            if (big) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(5, 15, 30, 60).filter { it != snoozeMin }.forEach { m ->
                        AssistChip(onClick = { onSnooze(m) }, label = { Text(if (m < 60) "$m דק'" else "שעה") })
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingRow(u: UpcomingEvent) {
    val i = u.instance
    ListItem(
        headlineContent = { Text(i.title.ifBlank { "(ללא כותרת)" }) },
        supportingContent = {
            Text(
                buildString {
                    append(if (i.allDay) "${Format.day(i.begin)} · כל היום" else Format.dateTime(i.begin))
                    if (i.location.isNotBlank()) append(" · ").append(i.location)
                }
            )
        },
        leadingContent = { Icon(Icons.Filled.DateRange, null) },
        trailingContent = {
            when {
                u.nextTrigger != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Notifications, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(Format.relative(u.nextTrigger), style = MaterialTheme.typography.labelMedium)
                }
                !u.hasReminder -> Text("ללא תזכורת", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                else -> Text("")
            }
        },
    )
}
