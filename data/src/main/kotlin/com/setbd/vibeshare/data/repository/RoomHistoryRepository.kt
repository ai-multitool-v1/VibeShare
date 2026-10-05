package com.setbd.vibeshare.data.repository

import com.setbd.vibeshare.data.db.HistoryDao
import com.setbd.vibeshare.data.db.TransferHistoryEntity
import com.setbd.vibeshare.data.db.toDomain
import com.setbd.vibeshare.domain.model.HistoryEntry
import com.setbd.vibeshare.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed transfer history. Local-only by design (spec section 12/14). */
class RoomHistoryRepository(private val dao: HistoryDao) : HistoryRepository {

    override val entries: Flow<List<HistoryEntry>> = dao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    override suspend fun add(entry: HistoryEntry) {
        dao.insert(
            TransferHistoryEntity(
                direction = entry.direction.name,
                peerName = entry.peerName,
                fileNames = entry.fileNames.take(500),
                fileCount = entry.fileCount,
                totalBytes = entry.totalBytes,
                success = entry.success,
                durationMs = entry.durationMs,
                averageSpeedBps = entry.averageSpeedBps,
                timestampMs = entry.timestampMs,
            )
        )
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun clearAll() = dao.clearAll()
}
