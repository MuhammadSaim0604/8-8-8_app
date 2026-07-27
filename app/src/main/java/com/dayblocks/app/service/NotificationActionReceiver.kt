package com.dayblocks.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dayblocks.app.App
import com.dayblocks.app.data.repository.AppRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val repo = AppRepository(context)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val pendingResult = goAsync()

        scope.launch {
            try {
                when (intent.action) {
                    App.ACTION_PAUSE -> {
                        repo.pauseTask()
                        updateService(context, repo, false)
                    }
                    App.ACTION_RESUME -> {
                        val taskId = intent.getStringExtra(App.EXTRA_TASK_ID)
                        if (taskId != null) {
                            repo.resumeTask(taskId)
                            updateService(context, repo, true)
                        } else {
                            val taskProgress = repo.taskProgressFlow.firstOrNull()
                            val fallbackTaskId = taskProgress?.keys?.firstOrNull()
                            if (fallbackTaskId != null) {
                                repo.resumeTask(fallbackTaskId)
                                updateService(context, repo, true)
                            }
                        }
                    }
                    App.ACTION_STOP -> {
                        repo.stopTask()
                        updateServiceIdle(context)
                    }
                    App.ACTION_HIDE_BUBBLE -> {
                        repo.setBubbleHidden(true)
                        updateBubbleVisibilityService(context, App.ACTION_HIDE_BUBBLE)
                    }
                    App.ACTION_SHOW_BUBBLE -> {
                        repo.setBubbleHidden(false)
                        updateBubbleVisibilityService(context, App.ACTION_SHOW_BUBBLE)
                    }
                    App.ACTION_PREV_TASK, App.ACTION_NEXT_TASK -> {
                        val blockId = intent.getStringExtra(App.EXTRA_BLOCK_ID) ?: return@launch
                        val currentTaskId = intent.getStringExtra(App.EXTRA_TASK_ID) ?: ""
                        val allTasks = repo.tasksFlow.firstOrNull() ?: emptyList()
                        val blockTasks = allTasks.filter { it.blockId == blockId }
                        if (blockTasks.isNotEmpty()) {
                            val currentIndex = blockTasks.indexOfFirst { it.id == currentTaskId }
                            val nextIndex = if (intent.action == App.ACTION_PREV_TASK) {
                                if (currentIndex <= 0) blockTasks.lastIndex else currentIndex - 1
                            } else {
                                if (currentIndex == -1 || currentIndex >= blockTasks.lastIndex) 0 else currentIndex + 1
                            }
                            val newTask = blockTasks[nextIndex]
                            
                            val timer = repo.timerStateFlow.firstOrNull()
                            val wasRunning = timer != null && timer.taskId == currentTaskId
                            
                            if (wasRunning) {
                                repo.switchTask(newTask)
                                updateService(context, repo, true)
                            } else {
                                repo.saveSelectedTaskId(blockId, newTask.id)
                                updateService(context, repo, false)
                            }
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun updateService(context: Context, repo: AppRepository, isRunning: Boolean) {
        val tasks = repo.tasksFlow.firstOrNull() ?: return
        val timer = repo.timerStateFlow.firstOrNull()
        val task  = tasks.find { it.id == (timer?.taskId ?: "") }
        val elapsed = timer?.elapsedMs() ?: 0L

        val intent = Intent(context, FloatingBubbleService::class.java).apply {
            putExtra(App.EXTRA_TASK_ID,    task?.id ?: "")
            putExtra(App.EXTRA_TASK_NAME,  task?.name ?: "")
            putExtra(App.EXTRA_BLOCK_NAME, task?.block?.label ?: "")
            putExtra(App.EXTRA_ELAPSED_MS, elapsed)
            putExtra(App.EXTRA_IS_RUNNING, isRunning)
            putExtra(App.EXTRA_IS_PAUSED,  !isRunning && task != null)
        }
        context.startForegroundService(intent)
    }

    private fun updateServiceIdle(context: Context) {
        val intent = Intent(context, FloatingBubbleService::class.java).apply {
            putExtra(App.EXTRA_IS_RUNNING, false)
            putExtra(App.EXTRA_IS_PAUSED,  false)
        }
        context.startForegroundService(intent)
    }

    private fun updateBubbleVisibilityService(context: Context, action: String) {
        context.startForegroundService(
            Intent(context, FloatingBubbleService::class.java).apply {
                this.action = action
            }
        )
    }
}
