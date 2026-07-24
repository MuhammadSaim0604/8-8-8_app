package com.dayblocks.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.*
import com.dayblocks.app.App
import com.dayblocks.app.data.model.*
import com.dayblocks.app.data.model.Block
import com.dayblocks.app.data.repository.AppRepository
import com.dayblocks.app.service.FloatingBubbleService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppRepository(application)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private fun today() = dateFmt.format(Date())

    // ── State Flows ────────────────────────────────────────────────────────────
    val tasks         = repo.tasksFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val timerState    = repo.timerStateFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val taskProgress  = repo.taskProgressFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val history       = repo.historyFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val recentHistory = repo.recentHistoryFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings      = repo.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val bubbleHidden  = repo.bubbleHiddenFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── Live tick for UI ──────────────────────────────────────────────────────
    private val _nowMs = MutableStateFlow(System.currentTimeMillis())
    val nowMs: StateFlow<Long> = _nowMs.asStateFlow()

    private var tickJob: Job? = null

    init {
        viewModelScope.launch {
            repo.checkAndApplyDailyReset()
        }
        startTicking()
        viewModelScope.launch {
            timerState.collect { ts ->
                if (ts != null) startTicking() else stopTicking()
            }
        }
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = viewModelScope.launch {
            while (isActive) {
                _nowMs.value = System.currentTimeMillis()
                delay(250L)
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
        _nowMs.value = System.currentTimeMillis()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    fun elapsedMsFor(taskId: String): Long {
        val now = _nowMs.value
        val ts = timerState.value
        return when {
            ts?.taskId == taskId -> ts.elapsedMs(now)
            taskProgress.value.containsKey(taskId) -> taskProgress.value[taskId]!!
            else -> 0L
        }
    }

    fun isRunning(taskId: String) = timerState.value?.taskId == taskId
    fun isPaused(taskId: String)  = taskProgress.value.containsKey(taskId) && timerState.value?.taskId != taskId

    fun tasksForBlock(block: Block) = tasks.value.filter { it.blockId == block.name }
    fun usedMinutesForBlock(block: Block) = tasksForBlock(block).sumOf { it.durationMinutes }
    fun freeMinutesForBlock(block: Block) = 480 - usedMinutesForBlock(block)

    // ── Task CRUD ──────────────────────────────────────────────────────────────
    fun addTask(task: Task) = viewModelScope.launch { repo.addTask(task) }
    fun updateTask(task: Task) = viewModelScope.launch { repo.updateTask(task) }
    fun deleteTask(taskId: String) = viewModelScope.launch {
        repo.deleteTask(taskId)
        updateBubbleService()
    }

    // ── Timer Controls ─────────────────────────────────────────────────────────
    fun startTask(task: Task) = viewModelScope.launch {
        repo.startTask(task)
        updateBubbleService()
    }

    fun pauseTask() = viewModelScope.launch {
        repo.pauseTask()
        updateBubbleService()
    }

    fun resumeTask(taskId: String) = viewModelScope.launch {
        repo.resumeTask(taskId)
        updateBubbleService()
    }

    fun stopTask() = viewModelScope.launch {
        repo.stopTask()
        updateBubbleService()
    }

    fun switchTask(newTask: Task) = viewModelScope.launch {
        repo.switchTask(newTask)
        updateBubbleService()
    }

    // ── Bubble Controls ─────────────────────────────────────────────────────────
    fun toggleBubble(context: Context) = viewModelScope.launch {
        val hidden = !bubbleHidden.value
        repo.setBubbleHidden(hidden)
        // Broadcast to service
        val action = if (hidden) App.ACTION_HIDE_BUBBLE else App.ACTION_SHOW_BUBBLE
        context.sendBroadcast(Intent(action).setPackage(context.packageName))
    }

    fun hideBubble(context: Context) = viewModelScope.launch {
        repo.setBubbleHidden(true)
        context.sendBroadcast(Intent(App.ACTION_HIDE_BUBBLE).setPackage(context.packageName))
    }

    fun showBubble(context: Context) = viewModelScope.launch {
        repo.setBubbleHidden(false)
        context.sendBroadcast(Intent(App.ACTION_SHOW_BUBBLE).setPackage(context.packageName))
    }

    fun hasOverlayPermission(context: Context) = Settings.canDrawOverlays(context)

    // ── Bubble Service ─────────────────────────────────────────────────────────
    fun updateBubbleService() {
        val context = getApplication<Application>()
        val ts = timerState.value
        val taskList = tasks.value
        val task = ts?.let { t -> taskList.find { it.id == t.taskId } }

        val intent = Intent(context, FloatingBubbleService::class.java).apply {
            putExtra(App.EXTRA_TASK_ID,   task?.id ?: "")
            putExtra(App.EXTRA_TASK_NAME, task?.name ?: "")
            putExtra(App.EXTRA_BLOCK_NAME, task?.block?.label ?: "")
            putExtra(App.EXTRA_ELAPSED_MS, ts?.elapsedMs() ?: 0L)
            putExtra(App.EXTRA_IS_PAUSED, ts == null && taskProgress.value.isNotEmpty())
            putExtra(App.EXTRA_IS_RUNNING, ts != null)
        }
        context.startForegroundService(intent)
    }

    // ── Settings ────────────────────────────────────────────────────────────────
    fun saveSettings(s: AppSettings) = viewModelScope.launch { repo.saveSettings(s) }

    // ── Data management ─────────────────────────────────────────────────────────
    fun clearHistory() = viewModelScope.launch { repo.clearHistory() }
    fun resetEverything() = viewModelScope.launch {
        repo.resetEverything()
        updateBubbleService()
    }

    // ── Stats helpers ────────────────────────────────────────────────────────────
    fun todayTrackedMsForBlock(block: Block): Long {
        val tod = today()
        return history.value.filter { it.blockId == block.name && it.date == tod }
            .sumOf { it.elapsedMs }
    }

    fun weeklyData(): List<Pair<String, Long>> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance()
        return (6 downTo 0).map { daysAgo ->
            cal.time = Date()
            cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
            val dateStr = fmt.format(cal.time)
            val label = SimpleDateFormat("EEE", Locale.getDefault()).format(cal.time).take(1)
            val ms = history.value.filter { it.date == dateStr }.sumOf { it.elapsedMs }
            label to ms
        }
    }

    fun dayStreak(): Int {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance()
        val dates = history.value.map { it.date }.toSet()
        var streak = 0
        while (true) {
            val d = fmt.format(cal.time)
            if (d in dates) { streak++; cal.add(Calendar.DAY_OF_YEAR, -1) }
            else break
        }
        return streak
    }

    fun topTasks(limit: Int = 5): List<Pair<Task, Long>> {
        val taskMs = mutableMapOf<String, Long>()
        history.value.forEach { h -> taskMs[h.taskId] = (taskMs[h.taskId] ?: 0L) + h.elapsedMs }
        return taskMs.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { (id, ms) -> tasks.value.find { it.id == id }?.let { it to ms } }
    }
}
