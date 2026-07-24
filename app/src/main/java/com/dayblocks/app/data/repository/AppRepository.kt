package com.dayblocks.app.data.repository

import android.content.Context
import com.dayblocks.app.data.db.AppDatabase
import com.dayblocks.app.data.model.*
import com.dayblocks.app.data.prefs.AppPrefs
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*

class AppRepository(context: Context) {

    private val db      = AppDatabase.getInstance(context)
    private val taskDao = db.taskDao()
    private val histDao = db.historyDao()
    private val prefs   = AppPrefs(context)

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private fun today() = dateFmt.format(Date())

    // ── Tasks ──────────────────────────────────────────────────────────────────
    val tasksFlow       = taskDao.observeAll()
    fun tasksForBlock(block: Block) = taskDao.observeByBlock(block.name)

    suspend fun addTask(task: Task)    = taskDao.insert(task)
    suspend fun updateTask(task: Task) = taskDao.update(task)
    suspend fun deleteTask(id: String) {
        taskDao.deleteById(id)
        // Clear any progress for this task
        val prog = prefs.taskProgressFlow.first().toMutableMap()
        prog.remove(id)
        prefs.saveTaskProgress(prog)
        // Stop timer if it was running this task
        val timer = prefs.timerStateFlow.first()
        if (timer?.taskId == id) prefs.saveTimerState(null)
    }
    suspend fun deleteAllTasks() = taskDao.deleteAll()

    // ── Timer State ────────────────────────────────────────────────────────────
    val timerStateFlow    = prefs.timerStateFlow
    val taskProgressFlow  = prefs.taskProgressFlow

    suspend fun startTask(task: Task) {
        val prog = prefs.taskProgressFlow.first()
        val accumulated = prog[task.id] ?: 0L
        // Remove from paused map
        val newProg = prog.toMutableMap().also { it.remove(task.id) }
        prefs.saveTaskProgress(newProg)
        prefs.saveTimerState(TimerState(
            taskId       = task.id,
            blockId      = task.blockId,
            startedAt    = System.currentTimeMillis(),
            accumulatedMs = accumulated
        ))
    }

    suspend fun pauseTask() {
        val timer = prefs.timerStateFlow.first() ?: return
        val elapsed = timer.elapsedMs()
        val prog = prefs.taskProgressFlow.first().toMutableMap()
        prog[timer.taskId] = elapsed
        prefs.saveTaskProgress(prog)
        prefs.saveTimerState(null)
    }

    suspend fun resumeTask(taskId: String) {
        val prog = prefs.taskProgressFlow.first()
        val accumulated = prog[taskId] ?: 0L
        val newProg = prog.toMutableMap().also { it.remove(taskId) }
        prefs.saveTaskProgress(newProg)
        val task = taskDao.getById(taskId) ?: return
        prefs.saveTimerState(TimerState(
            taskId        = task.id,
            blockId       = task.blockId,
            startedAt     = System.currentTimeMillis(),
            accumulatedMs = accumulated
        ))
    }

    suspend fun stopTask(): HistoryEntry? {
        val timer = prefs.timerStateFlow.first() ?: return null
        val elapsed = timer.elapsedMs()
        prefs.saveTimerState(null)
        val prog = prefs.taskProgressFlow.first().toMutableMap()
        prog.remove(timer.taskId)
        prefs.saveTaskProgress(prog)

        if (elapsed < 5_000L) return null
        val task = taskDao.getById(timer.taskId) ?: return null
        val entry = HistoryEntry(
            taskId    = task.id,
            taskName  = task.name,
            blockId   = task.blockId,
            blockName = task.block.label,
            elapsedMs = elapsed,
            date      = today()
        )
        histDao.insert(entry)
        return entry
    }

    suspend fun switchTask(newTask: Task) {
        pauseTask()
        startTask(newTask)
    }

    // ── Daily Reset ────────────────────────────────────────────────────────────
    val lastResetDateFlow = prefs.lastResetDateFlow

    suspend fun checkAndApplyDailyReset() {
        val lastReset = prefs.lastResetDateFlow.first()
        val tod = today()
        if (lastReset != tod) {
            // Stop running timer
            val timer = prefs.timerStateFlow.first()
            if (timer != null) {
                // Save history for the interrupted task
                val elapsed = timer.elapsedMs()
                if (elapsed >= 5_000L) {
                    val task = taskDao.getById(timer.taskId)
                    if (task != null) {
                        histDao.insert(HistoryEntry(
                            taskId    = task.id,
                            taskName  = task.name,
                            blockId   = task.blockId,
                            blockName = task.block.label,
                            elapsedMs = elapsed,
                            date      = lastReset.ifEmpty { tod }
                        ))
                    }
                }
                prefs.saveTimerState(null)
            }
            // Clear progress
            prefs.saveTaskProgress(emptyMap())
            prefs.saveLastResetDate(tod)
        }
    }

    // ── History ────────────────────────────────────────────────────────────────
    val historyFlow       = histDao.observeAll()
    val recentHistoryFlow = histDao.observeRecent()

    suspend fun getHistory() = histDao.getAll()
    suspend fun clearHistory() = histDao.deleteAll()
    suspend fun getHistoryByDate(date: String) = histDao.getByDate(date)
    suspend fun totalMsForTask(id: String) = histDao.totalMsForTask(id) ?: 0L
    suspend fun totalMsForBlockOnDate(blockId: String, date: String) =
        histDao.totalMsForBlockOnDate(blockId, date) ?: 0L

    // ── Settings ───────────────────────────────────────────────────────────────
    val settingsFlow = prefs.settingsFlow
    suspend fun saveSettings(s: AppSettings) = prefs.saveSettings(s)

    // ── Bubble visibility ──────────────────────────────────────────────────────
    val bubbleHiddenFlow = prefs.bubbleHiddenFlow
    suspend fun setBubbleHidden(hidden: Boolean) = prefs.saveBubbleHidden(hidden)

    // ── Reset Everything ───────────────────────────────────────────────────────
    suspend fun resetEverything() {
        taskDao.deleteAll()
        histDao.deleteAll()
        prefs.saveTimerState(null)
        prefs.saveTaskProgress(emptyMap())
        prefs.saveSettings(AppSettings())
        prefs.saveLastResetDate("")
        prefs.saveBubbleHidden(false)
    }
}
