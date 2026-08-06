package com.clearpass.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val server: String,
    val sni: String,
    val protocol: String,
    val startedAt: Long,
    val endedAt: Long = 0,
    val durationSec: Long = 0,
    val endReason: String = "unknown",
    val latencyMs: Int = -1
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query(
        "UPDATE sessions SET endedAt = :endedAt, durationSec = :duration, endReason = :reason WHERE id = :id"
    )
    suspend fun finish(id: Long, endedAt: Long, duration: Long, reason: String)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<SessionEntity>>

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int

    @Query("DELETE FROM sessions WHERE startedAt < :threshold")
    suspend fun deleteOlderThan(threshold: Long)
}

@Database(entities = [SessionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "clearpass.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}
