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

        when (intent.action) {
            App.ACTION_PAUSE -> scope.launch {
                repo.pauseTask()
                updateService(context, repo, false)
            }
            App.ACTION_RESUME -> scope.launch {
                val timerState = repo.timerStateFlow.firstOrNull()
                val taskProgress = repo.taskProgressFlow.firstOrNull()
                val taskId = taskProgress?.keys?.firstOrNull() ?: return@launch
                repo.resumeTask(taskId)
                updateService(context, repo, true)
            }
            App.ACTION_STOP -> scope.launch {
                repo.stopTask()
                updateServiceIdle(context)
            }
            App.ACTION_HIDE_BUBBLE -> scope.launch {
                repo.setBubbleHidden(true)
                // Bubble service handles this via its own receiver
            }
            App.ACTION_SHOW_BUBBLE -> scope.launch {
                repo.setBubbleHidden(false)
                // Bubble service handles this via its own receiver
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
}
