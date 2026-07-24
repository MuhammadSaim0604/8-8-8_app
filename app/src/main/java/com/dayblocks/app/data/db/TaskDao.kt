package com.dayblocks.app.data.db

import androidx.room.*
import com.dayblocks.app.data.model.Task
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE blockId = :blockId ORDER BY sortOrder ASC, createdAt ASC")
    fun observeByBlock(blockId: String): Flow<List<Task>>

    @Query("SELECT * FROM tasks ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<Task>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Task?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: Task)

    @Update
    suspend fun update(task: Task)

    @Delete
    suspend fun delete(task: Task)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}
