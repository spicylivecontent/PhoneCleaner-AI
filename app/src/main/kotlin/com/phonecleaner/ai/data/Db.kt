package com.phonecleaner.ai.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Cached AI verdict for one package, so it is never paid for twice. */
@Entity(tableName = "analysis")
data class AnalysisEntity(
    @PrimaryKey val pkg: String,
    val appName: String,
    val whatItDoes: String,
    /** "safe" | "caution" | "keep" */
    val verdict: String,
    val risk: String,
    val isAdOrTracker: Boolean,
    val suspectedMalware: Boolean,
    /** URLs separated by newlines. */
    val sources: String,
    val model: String,
    val analyzedAt: Long,
)

/** One change made to the phone, with what is needed to undo it. */
@Entity(tableName = "change")
data class ChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    /** UNINSTALL | DISABLE | SETTING | APPOP */
    val kind: String,
    /** Package name, or "namespace key" for a setting. */
    val target: String,
    val label: String,
    /** App-op name for APPOP, otherwise empty. */
    val detail: String,
    val originalValue: String,
    val newValue: String,
    val reverted: Boolean = false,
)

@Entity(tableName = "log")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val command: String,
    val result: String,
    val ok: Boolean,
)

@Dao
interface AppDao {
    @Query("SELECT * FROM analysis") fun analyses(): Flow<List<AnalysisEntity>>
    @Query("SELECT * FROM analysis") suspend fun analysesNow(): List<AnalysisEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAnalyses(items: List<AnalysisEntity>)
    @Query("DELETE FROM analysis") suspend fun clearAnalyses()

    @Query("SELECT * FROM change ORDER BY id DESC") fun changes(): Flow<List<ChangeEntity>>
    @Query("SELECT * FROM change WHERE reverted = 0 ORDER BY id DESC") suspend fun activeChanges(): List<ChangeEntity>
    @Insert suspend fun insertChange(c: ChangeEntity): Long
    @Query("UPDATE change SET reverted = 1 WHERE id = :id") suspend fun markReverted(id: Long)
    @Query("DELETE FROM change WHERE id = :id") suspend fun deleteChange(id: Long)
    @Query("UPDATE change SET newValue = :value WHERE id = :id") suspend fun updateNewValue(id: Long, value: String)
    @Query("SELECT * FROM change WHERE reverted = 0 AND kind = :kind AND target = :target AND detail = :detail LIMIT 1")
    suspend fun activeChangeFor(kind: String, target: String, detail: String): ChangeEntity?

    @Query("SELECT * FROM log ORDER BY id DESC LIMIT 1000") fun logs(): Flow<List<LogEntity>>
    @Insert suspend fun insertLog(l: LogEntity)
}

@Database(entities = [AnalysisEntity::class, ChangeEntity::class, LogEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var instance: AppDb? = null

        @androidx.annotation.VisibleForTesting
        fun closeForTest() {
            instance?.close()
            instance = null
        }
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "phonecleaner.db")
                .build().also { instance = it }
        }
    }
}
