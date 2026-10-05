package com.setbd.vibeshare.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.setbd.vibeshare.domain.model.HistoryEntry
import com.setbd.vibeshare.domain.model.TransferDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "transfer_history")
data class TransferHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val direction: String,
    val peerName: String,
    val fileNames: String,
    val fileCount: Int,
    val totalBytes: Long,
    val success: Boolean,
    val durationMs: Long,
    val averageSpeedBps: Long,
    val timestampMs: Long,
)

fun TransferHistoryEntity.toDomain(): HistoryEntry = HistoryEntry(
    id = id,
    direction = TransferDirection.valueOf(direction),
    peerName = peerName,
    fileNames = fileNames,
    fileCount = fileCount,
    totalBytes = totalBytes,
    success = success,
    durationMs = durationMs,
    averageSpeedBps = averageSpeedBps,
    timestampMs = timestampMs,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM transfer_history ORDER BY timestampMs DESC LIMIT 500")
    fun observeAll(): Flow<List<TransferHistoryEntity>>

    @Insert
    suspend fun insert(entry: TransferHistoryEntity): Long

    @Query("DELETE FROM transfer_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM transfer_history")
    suspend fun clearAll()
}

@Database(entities = [TransferHistoryEntity::class], version = 1, exportSchema = false)
abstract class VibeDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
}
