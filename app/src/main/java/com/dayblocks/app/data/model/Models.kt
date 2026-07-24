package com.dayblocks.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

// ─── Enums ───────────────────────────────────────────────────────────────────

enum class Block(val label: String, val subtitle: String, val emoji: String, val colorHex: String) {
    SLEEP("Sleep", "Rest & Recovery", "🌙", "#8B5CF6"),
    WORK("Work", "Work & Learning", "💼", "#3B82F6"),
    PERSONAL("Personal", "Personal Life", "🧘", "#10B981");

    companion object {
        fun fromId(id: String) = values().first { it.name == id }
    }
}

// ─── Task ────────────────────────────────────────────────────────────────────

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val blockId: String = Block.WORK.name,
    val name: String = "",
    val durationMinutes: Int = 30,
    val colorHex: String = "#3B82F6",
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
) {
    val block: Block get() = Block.fromId(blockId)
    val durationMs: Long get() = durationMinutes * 60_000L
}

// ─── Timer State ─────────────────────────────────────────────────────────────

data class TimerState(
    val taskId: String,
    val blockId: String,
    val startedAt: Long,           // wall-clock ms when current session started
    val accumulatedMs: Long = 0L   // ms from previous paused sessions today
) {
    val block: Block get() = Block.fromId(blockId)

    fun elapsedMs(now: Long = System.currentTimeMillis()): Long =
        accumulatedMs + (now - startedAt)
}

// ─── History Entry ───────────────────────────────────────────────────────────

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val taskName: String,
    val blockId: String,
    val blockName: String,
    val elapsedMs: Long,
    val date: String,          // YYYY-MM-DD
    val stoppedAt: Long = System.currentTimeMillis()
) {
    val block: Block get() = Block.fromId(blockId)
}

// ─── App Settings ─────────────────────────────────────────────────────────────

data class AppSettings(
    val notificationsEnabled: Boolean = true,
    val dailyResetHour: Int = 0,
    val bubbleEnabled: Boolean = true
)

// ─── App State ────────────────────────────────────────────────────────────────

data class AppState(
    val tasks: List<Task> = emptyList(),
    val timerState: TimerState? = null,
    val taskProgress: Map<String, Long> = emptyMap(),  // taskId → accumulatedMs (paused)
    val settings: AppSettings = AppSettings(),
    val lastResetDate: String = ""
) {
    fun tasksForBlock(block: Block) = tasks.filter { it.blockId == block.name }
    fun usedMinutesForBlock(block: Block) = tasksForBlock(block).sumOf { it.durationMinutes }
    fun freeMinutesForBlock(block: Block) = 480 - usedMinutesForBlock(block)

    fun elapsedMsForTask(taskId: String, now: Long = System.currentTimeMillis()): Long {
        return when {
            timerState?.taskId == taskId -> timerState.elapsedMs(now)
            taskProgress.containsKey(taskId) -> taskProgress[taskId]!!
            else -> 0L
        }
    }
}

// ─── Task Color Palette ───────────────────────────────────────────────────────

object TaskColors {
    val palette = listOf(
        "#3B82F6",  // Blue
        "#8B5CF6",  // Purple
        "#10B981",  // Green
        "#F97316",  // Orange
        "#EF4444",  // Red
        "#EC4899",  // Pink
        "#06B6D4",  // Cyan
        "#F59E0B",  // Amber
        "#84CC16",  // Lime
        "#6366F1"   // Indigo
    )
}
