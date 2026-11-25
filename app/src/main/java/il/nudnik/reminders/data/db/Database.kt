package il.nudnik.reminders.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val dueAt: Long? = null,
    val done: Boolean = false,
    /** 0 = low, 1 = normal, 2 = high */
    val priority: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)

/**
 * One nagging reminder. key is unique per source:
 *  E:<eventId>:<instanceBegin>  — calendar event instance
 *  T:<taskId>:<dueAt>           — task
 *  X:<time>                     — test alert
 */
@Entity(tableName = "alerts", indices = [Index(value = ["key"], unique = true)])
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val type: Int,
    val refId: Long,
    val title: String,
    val location: String = "",
    val begin: Long,
    val end: Long = 0,
    val allDay: Boolean = false,
    val state: Int,
    val count: Int = 0,
    val nextAt: Long,
    val lastSourceTrigger: Long = 0,
    val firstAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val TYPE_EVENT = 0
        const val TYPE_TASK = 1
        const val TYPE_TEST = 2

        const val STATE_ACTIVE = 0
        const val STATE_SNOOZED = 1
        const val STATE_EXHAUSTED = 2
        const val STATE_DISMISSED = 3
    }
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY done, CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt, priority DESC, createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE done = 0 AND dueAt IS NOT NULL AND dueAt <= :now")
    suspend fun dueUndone(now: Long): List<TaskEntity>

    @Query("SELECT MIN(dueAt) FROM tasks WHERE done = 0 AND dueAt > :now")
    suspend fun nextDue(now: Long): Long?

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: Long): TaskEntity?

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts WHERE state IN (0,1,2) ORDER BY state, begin")
    fun observeVisible(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE `key` = :key LIMIT 1")
    suspend fun byKey(key: String): AlertEntity?

    @Query("SELECT * FROM alerts WHERE id = :id")
    suspend fun byId(id: Long): AlertEntity?

    @Query("SELECT * FROM alerts WHERE state IN (0,1) AND nextAt <= :now ORDER BY begin")
    suspend fun due(now: Long): List<AlertEntity>

    @Query("SELECT * FROM alerts WHERE state IN (0,1,2)")
    suspend fun pending(): List<AlertEntity>

    @Query("SELECT MIN(nextAt) FROM alerts WHERE state IN (0,1)")
    suspend fun nextWake(): Long?

    @Query("SELECT * FROM alerts WHERE type = 1 AND refId = :taskId AND state IN (0,1,2)")
    suspend fun openForTask(taskId: Long): List<AlertEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(alert: AlertEntity): Long

    @Update
    suspend fun update(alert: AlertEntity)

    @Query("DELETE FROM alerts WHERE state = 3 AND updatedAt < :before")
    suspend fun purgeDismissed(before: Long)
}

@Database(entities = [TaskEntity::class, AlertEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun alertDao(): AlertDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "nudnik.db")
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
