package il.nudnik.reminders.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import il.nudnik.reminders.calendar.CalendarInfo
import il.nudnik.reminders.calendar.UpcomingEvent
import il.nudnik.reminders.data.NudnikSettings
import il.nudnik.reminders.data.db.AlertEntity
import il.nudnik.reminders.data.db.TaskEntity
import il.nudnik.reminders.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph

    val settings: StateFlow<NudnikSettings> =
        g.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, NudnikSettings())

    val alerts: StateFlow<List<AlertEntity>> =
        g.db.alertDao().observeVisible().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val tasks: StateFlow<List<TaskEntity>> =
        g.db.taskDao().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _upcoming = MutableStateFlow<List<UpcomingEvent>>(emptyList())
    val upcoming = _upcoming.asStateFlow()

    private val _calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val calendars = _calendars.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val s = g.settings.get()
            _calendars.value = g.calendar.calendars()
            _upcoming.value = g.calendar.upcoming(s, System.currentTimeMillis(), 48L * 60 * 60 * 1000)
            g.engine.run()
        }
    }

    fun updateSettings(transform: (NudnikSettings) -> NudnikSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            g.settings.update(transform)
            refresh()
        }
    }

    fun dismiss(id: Long) = launchIo { g.engine.dismiss(id) }
    fun snooze(id: Long, minutes: Int) = launchIo { g.engine.snooze(id, minutes) }
    fun dismissAll() = launchIo { g.engine.dismissAll() }
    fun testAlert() = launchIo { g.engine.testAlert() }

    fun saveTask(task: TaskEntity) = launchIo {
        val dao = g.db.taskDao()
        val id = if (task.id == 0L) dao.insert(task) else task.id.also { dao.update(task) }
        g.engine.taskChanged(id)
    }

    fun setTaskDone(task: TaskEntity, done: Boolean) = launchIo {
        if (done) {
            g.engine.completeTask(task.id)
        } else {
            g.db.taskDao().update(task.copy(done = false, completedAt = null))
            g.engine.taskChanged(task.id)
        }
    }

    fun deleteTask(task: TaskEntity) = launchIo {
        g.db.taskDao().delete(task)
        g.engine.taskChanged(task.id)
    }

    private fun launchIo(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }
}
