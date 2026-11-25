package il.nudnik.reminders.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.nudnik.reminders.data.db.TaskEntity
import il.nudnik.reminders.ui.Format
import il.nudnik.reminders.ui.MainViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun TasksScreen(vm: MainViewModel, modifier: Modifier, onEdit: (TaskEntity) -> Unit) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var showDone by rememberSaveable { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val visible = tasks.filter { showDone || !it.done }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            SectionHeader("משימות (${tasks.count { !it.done }} פתוחות)") {
                FilterChip(selected = showDone, onClick = { showDone = !showDone }, label = { Text("הצג שבוצעו") })
            }
        }
        if (visible.isEmpty()) {
            item { EmptyHint("אין משימות. לחץ + כדי להוסיף משימה עם מועד יעד – נודניק יציק עד שתסמן שבוצעה.") }
        }
        items(visible, key = { it.id }) { t ->
            val overdue = !t.done && t.dueAt != null && t.dueAt <= now
            ListItem(
                modifier = Modifier.clickable { onEdit(t) },
                leadingContent = {
                    Checkbox(checked = t.done, onCheckedChange = { vm.setTaskDone(t, it) })
                },
                headlineContent = {
                    Text(
                        (if (t.priority == 2) "❗ " else "") + t.title,
                        textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    )
                },
                supportingContent = {
                    Column {
                        if (t.notes.isNotBlank()) Text(t.notes, maxLines = 2)
                        t.dueAt?.let {
                            Text(
                                (if (overdue) "באיחור · " else "") + Format.dateTime(it),
                                color = if (overdue) MaterialTheme.colorScheme.error else Color.Unspecified,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                },
                trailingContent = {
                    IconButton(onClick = { vm.deleteTask(t) }) { Icon(Icons.Filled.Delete, "מחק") }
                },
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorDialog(initial: TaskEntity, onDismiss: () -> Unit, onSave: (TaskEntity) -> Unit) {
    var title by remember { mutableStateOf(initial.title) }
    var notes by remember { mutableStateOf(initial.notes) }
    var dueAt by remember { mutableStateOf(initial.dueAt) }
    var priority by remember { mutableIntStateOf(initial.priority) }
    var pickDate by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "משימה חדשה" else "עריכת משימה") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("כותרת") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("הערות") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
                Text("עדיפות", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("נמוכה", "רגילה", "גבוהה").forEachIndexed { i, label ->
                        FilterChip(selected = priority == i, onClick = { priority = i }, label = { Text(label) })
                    }
                }
                Text("מועד יעד (מתי להתחיל להציק)", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate = true }) {
                        Text(dueAt?.let { Format.dateTime(it) } ?: "בחר תאריך ושעה")
                    }
                    if (dueAt != null) TextButton(onClick = { dueAt = null }) { Text("ללא") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(initial.copy(title = title.trim(), notes = notes.trim(), dueAt = dueAt, priority = priority))
                },
            ) { Text("שמור") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )

    if (pickDate) {
        val zone = ZoneId.systemDefault()
        val initialUtc = (dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: LocalDate.now())
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickedDate = state.selectedDateMillis?.let {
                        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    pickDate = false
                }) { Text("הבא") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("ביטול") } },
        ) {
            DatePicker(state = state)
        }
    }

    pickedDate?.let { date ->
        val zone = ZoneId.systemDefault()
        val init = dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
            ?: LocalTime.now().plusHours(1).withMinute(0)
        val state = rememberTimePickerState(initialHour = init.hour, initialMinute = init.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickedDate = null },
            title = { Text("שעה") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    dueAt = date.atTime(state.hour, state.minute).atZone(zone).toInstant().toEpochMilli()
                    pickedDate = null
                }) { Text("אישור") }
            },
            dismissButton = { TextButton(onClick = { pickedDate = null }) { Text("ביטול") } },
        )
    }
}
