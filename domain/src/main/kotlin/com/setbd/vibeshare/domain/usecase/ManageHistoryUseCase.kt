package com.setbd.vibeshare.domain.usecase

import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.repository.HistoryRepository
import com.setbd.vibeshare.domain.model.HistoryEntry
import kotlinx.coroutines.flow.Flow

/** Reads and maintains local transfer history. Data never leaves the device. */
class ManageHistoryUseCase(private val history: HistoryRepository) {

    val entries: Flow<List<HistoryEntry>> = history.entries

    suspend fun record(entry: HistoryEntry) = history.add(entry)

    suspend fun delete(id: Long) = history.delete(id)

    suspend fun clearAll() = history.clearAll()
}
