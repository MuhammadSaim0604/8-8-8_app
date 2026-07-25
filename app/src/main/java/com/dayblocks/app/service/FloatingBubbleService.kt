package com.dayblocks.app.service

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.dayblocks.app.App
import com.dayblocks.app.MainActivity
import com.dayblocks.app.R
import com.dayblocks.app.data.db.AppDatabase
import com.dayblocks.app.data.prefs.AppPrefs
import com.dayblocks.app.ui.quickmenu.QuickMenuActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class FloatingBubbleService : LifecycleService() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var timerText: TextView? = null
    private var taskLabel: TextView? = null
    private var glowRing: View? = null

    // @Volatile so the executor thread always sees the latest values written by main thread
    @Volatile private var taskId    = ""
    @Volatile private var taskName  = ""
    @Volatile private var blockName = ""
    @Volatile private var elapsedMs = 0L
    @Volatile private var isRunning = false
    @Volatile private var isPaused  = false
    @Volatile private var bubbleHidden = false

    // ── Tick infrastructure ────────────────────────────────────────────────────
    // Use a Java ScheduledExecutorService instead of coroutines: coroutine delay()
    // on Dispatchers.Main is throttled by Android when the app is in the background,
    // causing the notification to stop updating after a few seconds.
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var scheduledFuture: ScheduledFuture<*>? = null
    private var tickStartedAt = 0L

    // For posting view updates back to the main thread from the executor thread
    private val mainHandler = Handler(Looper.getMainLooper())

    // Coroutine scope for async DataStore reads only (not for the tick)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                App.ACTION_HIDE_BUBBLE -> {
                    bubbleHidden = true
                    hideBubbleView()
                    updateNotification()
                }
                App.ACTION_SHOW_BUBBLE -> {
                    bubbleHidden = false
                    showBubbleView()
                    updateNotification()
                }
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerReceiver(actionReceiver, IntentFilter().apply {
            addAction(App.ACTION_HIDE_BUBBLE)
            addAction(App.ACTION_SHOW_BUBBLE)
        }, RECEIVER_NOT_EXPORTED)
        startForeground(App.NOTIFICATION_ID_BUBBLE, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent == null) {
            // Sticky restart after process kill — intent is null, so parseIntent would
            // leave isRunning=false and kill the tick. Restore state from DataStore instead.
            restoreStateAndResume()
            return START_STICKY
        }

        parseIntent(intent)
        applyState()
        return START_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        serviceScope.cancel()
        removeBubble()
        unregisterReceiver(actionReceiver)
        super.onDestroy()
    }

    // ── Intent parsing ─────────────────────────────────────────────────────────

    private fun parseIntent(intent: Intent) {
        taskId    = intent.getStringExtra(App.EXTRA_TASK_ID) ?: ""
        taskName  = intent.getStringExtra(App.EXTRA_TASK_NAME) ?: ""
        blockName = intent.getStringExtra(App.EXTRA_BLOCK_NAME) ?: ""
        elapsedMs = intent.getLongExtra(App.EXTRA_ELAPSED_MS, 0L)
        isRunning = intent.getBooleanExtra(App.EXTRA_IS_RUNNING, false)
        isPaused  = intent.getBooleanExtra(App.EXTRA_IS_PAUSED, false)
    }

    /** Read running task from DataStore (called on sticky restart when intent == null). */
    private fun restoreStateAndResume() {
        serviceScope.launch {
            try {
                val prefs    = AppPrefs(this@FloatingBubbleService)
                val timer    = prefs.timerStateFlow.firstOrNull()
                val progress = prefs.taskProgressFlow.firstOrNull() ?: emptyMap()
                val hidden   = prefs.bubbleHiddenFlow.firstOrNull() ?: false

                bubbleHidden = hidden

                if (timer != null) {
                    val task = AppDatabase.getInstance(this@FloatingBubbleService)
                        .taskDao().getById(timer.taskId)
                    taskId    = timer.taskId
                    taskName  = task?.name ?: ""
                    blockName = task?.block?.label ?: ""
                    elapsedMs = timer.elapsedMs()
                    isRunning = true
                    isPaused  = false
                } else {
                    isRunning = false
                    isPaused  = progress.isNotEmpty()
                }

                // Apply state on the main thread (view operations must be on main thread)
                mainHandler.post { applyState() }
            } catch (_: Exception) {
                // If restore fails, just show idle notification
                mainHandler.post { updateNotification() }
            }
        }
    }

    /** Apply current state fields: update notification, bubble, and start/stop ticking. */
    private fun applyState() {
        updateNotification()
        if (Settings.canDrawOverlays(this) && !bubbleHidden) {
            if (bubbleView == null) createBubble()
            else updateBubbleContent()
        }
        if (isRunning) startTicking() else stopTicking()
    }

    // ── Bubble View ────────────────────────────────────────────────────────────

    private fun createBubble() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.layout_bubble_overlay, null, false)

        timerText = view.findViewById(R.id.tvBubbleTimer)
        taskLabel = view.findViewById(R.id.tvBubbleTask)
        glowRing  = view.findViewById(R.id.vBubbleGlow)

        updateBubbleContent()
        setupBubbleDrag(view)

        val screenW = resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW * 0.75).toInt(); y = 200
        }

        windowManager?.addView(view, params)
        bubbleView = view
    }

    private fun updateBubbleContent() {
        val elapsedSec = elapsedMs / 1000
        val minutes = elapsedSec / 60
        val seconds = elapsedSec % 60
        val timeStr = if (minutes >= 60) {
            val h = minutes / 60; val m = minutes % 60
            "%d:%02d:%02d".format(h, m, seconds)
        } else "%02d:%02d".format(minutes, seconds)

        timerText?.text = timeStr
        taskLabel?.text = taskName.take(9)

        val alpha = if (isPaused && !isRunning) 0.55f else 1f
        bubbleView?.alpha = alpha

        if (isRunning) startGlowPulse() else glowRing?.clearAnimation()
    }

    private fun startGlowPulse() {
        val anim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse_scale)
        glowRing?.startAnimation(anim)
    }

    private fun setupBubbleDrag(view: View) {
        var initialParamX = 0; var initialParamY = 0
        var downRawX = 0f;     var downRawY = 0f
        var lastX = 0f;        var lastY = 0f
        var isDragging = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val params = v.layoutParams as? WindowManager.LayoutParams
                    initialParamX = params?.x ?: 0
                    initialParamY = params?.y ?: 0
                    downRawX = event.rawX; downRawY = event.rawY
                    lastX = event.rawX;   lastY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val moveX = Math.abs(event.rawX - lastX)
                    val moveY = Math.abs(event.rawY - lastY)
                    if (moveX > 5 || moveY > 5) isDragging = true
                    val params = v.layoutParams as? WindowManager.LayoutParams
                    params?.let {
                        it.x = (initialParamX + (event.rawX - downRawX)).toInt()
                        it.y = (initialParamY + (event.rawY - downRawY)).toInt().coerceAtLeast(0)
                        windowManager?.updateViewLayout(v, it)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        val intent = Intent(this, QuickMenuActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                        }
                        startActivity(intent)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun hideBubbleView() {
        bubbleView?.visibility = View.GONE
    }

    private fun showBubbleView() {
        if (bubbleView == null) createBubble()
        else bubbleView?.visibility = View.VISIBLE
    }

    private fun removeBubble() {
        bubbleView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        bubbleView = null
    }

    // ── Timer Tick ─────────────────────────────────────────────────────────────

    private fun startTicking() {
        // Already ticking — don't start a second executor task
        if (scheduledFuture?.isDone == false) return
        tickStartedAt = System.currentTimeMillis() - elapsedMs
        scheduledFuture = executor.scheduleAtFixedRate({
            // Running on executor thread — safe for notification, but NOT for view access
            elapsedMs = System.currentTimeMillis() - tickStartedAt
            updateNotification()
            if (!bubbleHidden) {
                mainHandler.post { updateBubbleContent() }
            }
        }, 1000L, 1000L, TimeUnit.MILLISECONDS)
    }

    private fun stopTicking() {
        scheduledFuture?.cancel(false)
        scheduledFuture = null
    }

    // ── Notification ────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, App.CHANNEL_ID_BUBBLE)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when {
            isRunning -> {
                val elapsedSec = elapsedMs / 1000
                val mm = elapsedSec / 60; val ss = elapsedSec % 60
                val timeStr = if (mm >= 60) "%d:%02d:%02d".format(mm/60, mm%60, ss)
                              else "%02d:%02d".format(mm, ss)
                builder.setContentTitle("⏱ $taskName")
                builder.setContentText("$blockName · $timeStr elapsed — tap to open")
                builder.addAction(buildAction(R.drawable.ic_pause, "Pause", App.ACTION_PAUSE))
                builder.addAction(buildAction(R.drawable.ic_stop,  "Stop",  App.ACTION_STOP))
            }
            isPaused -> {
                builder.setContentTitle("⏸ $taskName")
                builder.setContentText("$blockName · Paused — tap to open")
                builder.addAction(buildAction(R.drawable.ic_play, "Resume", App.ACTION_RESUME))
                builder.addAction(buildAction(R.drawable.ic_stop, "Stop",   App.ACTION_STOP))
            }
            else -> {
                builder.setContentTitle("DayBlocks")
                builder.setContentText("8-8-8 Dashboard · tap to open")
            }
        }

        // Hide / Show Bubble toggle — always present
        val (bubbleIcon, bubbleLabel, bubbleAction) = if (bubbleHidden)
            Triple(R.drawable.ic_bubble_show, "Show Bubble", App.ACTION_SHOW_BUBBLE)
        else
            Triple(R.drawable.ic_bubble_hide, "Hide Bubble", App.ACTION_HIDE_BUBBLE)
        builder.addAction(buildAction(bubbleIcon, bubbleLabel, bubbleAction))

        return builder.build()
    }

    private fun buildAction(iconRes: Int, title: String, action: String): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            this, action.hashCode(),
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action(iconRes, title, pi)
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(App.NOTIFICATION_ID_BUBBLE, buildNotification())
    }
}
