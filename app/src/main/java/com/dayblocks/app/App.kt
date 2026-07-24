package com.dayblocks.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.getSystemService

class App : Application() {

    companion object {
        const val CHANNEL_ID_PERSISTENT   = "dayblocks_persistent"
        const val CHANNEL_ID_BUBBLE        = "dayblocks_bubble"
        const val NOTIFICATION_ID_PERSISTENT = 1001
        const val NOTIFICATION_ID_BUBBLE     = 1002

        // Broadcast actions
        const val ACTION_PAUSE       = "com.dayblocks.app.ACTION_PAUSE"
        const val ACTION_RESUME      = "com.dayblocks.app.ACTION_RESUME"
        const val ACTION_STOP        = "com.dayblocks.app.ACTION_STOP"
        const val ACTION_HIDE_BUBBLE = "com.dayblocks.app.ACTION_HIDE_BUBBLE"
        const val ACTION_SHOW_BUBBLE = "com.dayblocks.app.ACTION_SHOW_BUBBLE"
        const val ACTION_OPEN_APP    = "com.dayblocks.app.OPEN_APP"

        // Service broadcast extras
        const val EXTRA_TASK_ID      = "task_id"
        const val EXTRA_TASK_NAME    = "task_name"
        const val EXTRA_BLOCK_NAME   = "block_name"
        const val EXTRA_ELAPSED_MS   = "elapsed_ms"
        const val EXTRA_IS_PAUSED    = "is_paused"
        const val EXTRA_IS_RUNNING   = "is_running"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService<NotificationManager>()!!

            // Persistent 24/7 channel — LOW importance: no sound, no popup
            val persistentChannel = NotificationChannel(
                CHANNEL_ID_PERSISTENT,
                "DayBlocks Timer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live timer and 8-8-8 dashboard — always visible"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }

            // Bubble foreground service channel
            val bubbleChannel = NotificationChannel(
                CHANNEL_ID_BUBBLE,
                "Floating Bubble",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating timer bubble service notification"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }

            nm.createNotificationChannel(persistentChannel)
            nm.createNotificationChannel(bubbleChannel)
        }
    }
}
