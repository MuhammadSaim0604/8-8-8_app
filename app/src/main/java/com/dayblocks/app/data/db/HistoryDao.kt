package com.dayblocks.app.data.db

import androidx.room.*
import com.dayblocks.app.data.model.HistoryEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY stoppedAt DESC")
    fun observeAll(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history ORDER BY stoppedAt DESC LIMIT 10")
    fun observeRecent(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history ORDER BY stoppedAt DESC")
    suspend fun getAll(): List<HistoryEntry>

    @Query("SELECT * FROM history WHERE date = :date ORDER BY stoppedAt DESC")
    suspend fun getByDate(date: String): List<HistoryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry)

    @Query("DELETE FROM history")
    suspend fun deleteAll()

    @Query("SELECT SUM(elapsedMs) FROM history WHERE taskId = :taskId")
    suspend fun totalMsForTask(taskId: String): Long?

    @Query("SELECT SUM(elapsedMs) FROM history WHERE blockId = :blockId AND date = :date")
    suspend fun totalMsForBlockOnDate(blockId: String, date: String): Long?
}
