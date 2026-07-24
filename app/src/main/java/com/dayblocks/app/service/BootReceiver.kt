package com.dayblocks.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dayblocks.app.data.repository.AppRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            val repo = AppRepository(context)
            val timer = repo.timerStateFlow.first()
            // Re-start service so notification reappears after reboot
            val serviceIntent = Intent(context, FloatingBubbleService::class.java).apply {
                putExtra("is_running", timer != null)
                putExtra("is_paused", timer == null)
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
