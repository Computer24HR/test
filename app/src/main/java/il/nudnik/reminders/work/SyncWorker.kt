package il.nudnik.reminders.work

import android.content.Context
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import il.nudnik.reminders.graph
import java.util.concurrent.TimeUnit

/**
 * Safety net: runs the engine every 15 minutes, and immediately whenever
 * the calendar provider's data changes (new/edited events or reminders).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.graph.engine.run()
        if (tags.contains(TAG_OBSERVER)) enqueueCalendarObserver(applicationContext, ExistingWorkPolicy.REPLACE)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "nudnik_periodic"
        private const val OBSERVER = "nudnik_calendar_observer"
        private const val TAG_OBSERVER = "observer"

        fun enqueue(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build(),
            )
            enqueueCalendarObserver(context, ExistingWorkPolicy.KEEP)
        }

        private fun enqueueCalendarObserver(context: Context, policy: ExistingWorkPolicy) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
                .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                OBSERVER, policy,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints)
                    .addTag(TAG_OBSERVER)
                    .build(),
            )
        }
    }
}
