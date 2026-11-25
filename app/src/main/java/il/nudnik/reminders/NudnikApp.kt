package il.nudnik.reminders

import android.app.Application
import android.content.Context
import il.nudnik.reminders.calendar.CalendarSource
import il.nudnik.reminders.data.SettingsRepository
import il.nudnik.reminders.data.db.AppDatabase
import il.nudnik.reminders.engine.AlarmScheduler
import il.nudnik.reminders.engine.NudnikEngine
import il.nudnik.reminders.notify.Notifier
import il.nudnik.reminders.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NudnikApp : Application() {
    lateinit var graph: AppGraph
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        SyncWorker.enqueue(this)
        appScope.launch { graph.engine.run() }
    }
}

/** Minimal manual dependency container. */
class AppGraph(context: Context) {
    val settings = SettingsRepository(context)
    val db = AppDatabase.build(context)
    val calendar = CalendarSource(context)
    val notifier = Notifier(context)
    val scheduler = AlarmScheduler(context)
    val engine = NudnikEngine(settings, db, calendar, notifier, scheduler)
}

val Context.graph: AppGraph
    get() = (applicationContext as NudnikApp).graph

val Context.appScope: CoroutineScope
    get() = (applicationContext as NudnikApp).appScope
