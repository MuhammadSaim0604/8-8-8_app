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
import androidx.lifecycle.lifecycleScope
import com.dayblocks.app.App
import com.dayblocks.app.MainActivity
import com.dayblocks.app.R
import com.dayblocks.app.data.db.AppDatabase
import com.dayblocks.app.data.prefs.AppPrefs
import com.dayblocks.app.ui.quickmenu.QuickMenuActivity
import com.dayblocks.app.data.repository.AppRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
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

    // App state lists kept in memory for custom RemoteViews building
    @Volatile private var listTasks: List<com.dayblocks.app.data.model.Task> = emptyList()
    @Volatile private var timerState: com.dayblocks.app.data.model.TimerState? = null
    @Volatile private var taskProgress: Map<String, Long> = emptyMap()
    @Volatile private var selectedTasks: Map<String, String> = emptyMap()

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

    private lateinit var repo: AppRepository

    override fun onCreate() {
        super.onCreate()
        repo = AppRepository(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerReceiver(actionReceiver, IntentFilter().apply {
            addAction(App.ACTION_HIDE_BUBBLE)
            addAction(App.ACTION_SHOW_BUBBLE)
        }, RECEIVER_NOT_EXPORTED)

        // Observe repository state flows reactively
        lifecycleScope.launch {
            combine(
                repo.tasksFlow,
                repo.timerStateFlow,
                repo.taskProgressFlow,
                repo.selectedTasksFlow
            ) { tasks, timer, progress, selected ->
                listTasks = tasks
                timerState = timer
                taskProgress = progress
                selectedTasks = selected
            }.collect {
                val currentTimer = timerState
                if (currentTimer != null) {
                    taskId = currentTimer.taskId
                    val activeTask = listTasks.find { it.id == taskId }
                    taskName = activeTask?.name ?: ""
                    blockName = activeTask?.block?.label ?: ""
                    isRunning = true
                    isPaused = false
                } else {
                    isRunning = false
                    isPaused = taskProgress.isNotEmpty()
                }
                mainHandler.post { applyState() }
            }
        }

        startForeground(App.NOTIFICATION_ID_BUBBLE, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent == null) {
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
                    isPaused = progress.isNotEmpty()
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
        if (scheduledFuture?.isDone == false) return
        val currentTimer = timerState
        if (currentTimer != null) {
            tickStartedAt = currentTimer.startedAt - currentTimer.accumulatedMs
        } else {
            tickStartedAt = System.currentTimeMillis() - elapsedMs
        }
        scheduledFuture = executor.scheduleAtFixedRate({
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

        val collapsedViews = RemoteViews(packageName, R.layout.layout_notification_collapsed)
        val expandedViews = RemoteViews(packageName, R.layout.layout_notification_expanded)

        // 1. Populate Collapsed View
        val activeTimer = timerState
        val runningTask = listTasks.find { it.id == activeTimer?.taskId }
        if (runningTask != null) {
            collapsedViews.setTextViewText(R.id.tvCollapsedEmoji, runningTask.block.emoji)
            collapsedViews.setTextViewText(R.id.tvCollapsedTaskName, runningTask.name)
            
            val statusStr = if (isRunning) {
                "${runningTask.block.label} · ${fmtHoursMin(elapsedMs)} elapsed"
            } else {
                "${runningTask.block.label} · Paused"
            }
            collapsedViews.setTextViewText(R.id.tvCollapsedStatus, statusStr)

            collapsedViews.setViewVisibility(R.id.btnCollapsedPlayPause, View.VISIBLE)
            collapsedViews.setViewVisibility(R.id.btnCollapsedStop, View.VISIBLE)

            val playPauseIcon = if (isRunning) R.drawable.ic_pause else R.drawable.ic_play
            collapsedViews.setImageViewResource(R.id.btnCollapsedPlayPause, playPauseIcon)

            val playPauseAction = if (isRunning) App.ACTION_PAUSE else App.ACTION_RESUME
            val playPauseIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                action = playPauseAction
                if (playPauseAction == App.ACTION_RESUME) {
                    putExtra(App.EXTRA_TASK_ID, runningTask.id)
                }
            }
            val playPausePI = PendingIntent.getBroadcast(
                this,
                runningTask.id.hashCode() + playPauseAction.hashCode(),
                playPauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            collapsedViews.setOnClickPendingIntent(R.id.btnCollapsedPlayPause, playPausePI)

            val stopIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                action = App.ACTION_STOP
            }
            val stopPI = PendingIntent.getBroadcast(
                this,
                App.ACTION_STOP.hashCode(),
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            collapsedViews.setOnClickPendingIntent(R.id.btnCollapsedStop, stopPI)

            collapsedViews.setViewVisibility(R.id.pbCollapsed, View.VISIBLE)
            val pct = (elapsedMs.toFloat() / runningTask.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            collapsedViews.setProgressBar(R.id.pbCollapsed, 100, (pct * 100).toInt(), false)
        } else {
            val pausedTaskId = taskProgress.keys.firstOrNull()
            val pausedTask = listTasks.find { it.id == pausedTaskId }
            if (pausedTask != null) {
                val accumulated = taskProgress[pausedTask.id] ?: 0L
                collapsedViews.setTextViewText(R.id.tvCollapsedEmoji, pausedTask.block.emoji)
                collapsedViews.setTextViewText(R.id.tvCollapsedTaskName, pausedTask.name)
                collapsedViews.setTextViewText(R.id.tvCollapsedStatus, "${pausedTask.block.label} · Paused")

                collapsedViews.setViewVisibility(R.id.btnCollapsedPlayPause, View.VISIBLE)
                collapsedViews.setViewVisibility(R.id.btnCollapsedStop, View.VISIBLE)
                collapsedViews.setImageViewResource(R.id.btnCollapsedPlayPause, R.drawable.ic_play)

                val playPauseIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                    action = App.ACTION_RESUME
                    putExtra(App.EXTRA_TASK_ID, pausedTask.id)
                }
                val playPausePI = PendingIntent.getBroadcast(
                    this,
                    pausedTask.id.hashCode() + App.ACTION_RESUME.hashCode(),
                    playPauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                collapsedViews.setOnClickPendingIntent(R.id.btnCollapsedPlayPause, playPausePI)

                val stopIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                    action = App.ACTION_STOP
                }
                val stopPI = PendingIntent.getBroadcast(
                    this,
                    App.ACTION_STOP.hashCode(),
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                collapsedViews.setOnClickPendingIntent(R.id.btnCollapsedStop, stopPI)

                collapsedViews.setViewVisibility(R.id.pbCollapsed, View.VISIBLE)
                val pct = (accumulated.toFloat() / pausedTask.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                collapsedViews.setProgressBar(R.id.pbCollapsed, 100, (pct * 100).toInt(), false)
            } else {
                collapsedViews.setTextViewText(R.id.tvCollapsedEmoji, "⏱")
                collapsedViews.setTextViewText(R.id.tvCollapsedTaskName, "DayBlocks")
                collapsedViews.setTextViewText(R.id.tvCollapsedStatus, "8-8-8 Dashboard · Tap to open")
                collapsedViews.setViewVisibility(R.id.btnCollapsedPlayPause, View.GONE)
                collapsedViews.setViewVisibility(R.id.btnCollapsedStop, View.GONE)
                collapsedViews.setViewVisibility(R.id.pbCollapsed, View.GONE)
            }
        }

        // 2. Populate Expanded View (Sleep, Work, Personal blocks)
        populateBlockRow(expandedViews, com.dayblocks.app.data.model.Block.SLEEP,
            R.id.tvSleepTaskName, R.id.pbSleep, R.id.tvSleepTime,
            R.id.btnSleepPlayPause, R.id.btnSleepStop, R.id.btnSleepPrev, R.id.btnSleepNext)

        populateBlockRow(expandedViews, com.dayblocks.app.data.model.Block.WORK,
            R.id.tvWorkTaskName, R.id.pbWork, R.id.tvWorkTime,
            R.id.btnWorkPlayPause, R.id.btnWorkStop, R.id.btnWorkPrev, R.id.btnWorkNext)

        populateBlockRow(expandedViews, com.dayblocks.app.data.model.Block.PERSONAL,
            R.id.tvPersonalTaskName, R.id.pbPersonal, R.id.tvPersonalTime,
            R.id.btnPersonalPlayPause, R.id.btnPersonalStop, R.id.btnPersonalPrev, R.id.btnPersonalNext)

        val builder = NotificationCompat.Builder(this, App.CHANNEL_ID_BUBBLE)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openIntent)
            .setCustomContentView(collapsedViews)
            .setCustomBigContentView(expandedViews)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        return builder.build()
    }

    private fun populateBlockRow(
        rv: RemoteViews,
        block: com.dayblocks.app.data.model.Block,
        tvTaskNameId: Int,
        pbId: Int,
        tvTimeId: Int,
        btnPlayPauseId: Int,
        btnStopId: Int,
        btnPrevId: Int,
        btnNextId: Int
    ) {
        val blockTasks = listTasks.filter { it.blockId == block.name }
        val activeTimer = timerState
        val isCurrentBlockRunning = activeTimer != null && activeTimer.blockId == block.name
        
        val currentTask = when {
            isCurrentBlockRunning -> listTasks.find { it.id == activeTimer?.taskId }
            else -> {
                val selectedId = selectedTasks[block.name]
                listTasks.find { it.id == selectedId } ?: blockTasks.firstOrNull()
            }
        }

        if (currentTask != null) {
            rv.setTextViewText(tvTaskNameId, currentTask.name)
            rv.setViewVisibility(btnPrevId, View.VISIBLE)
            rv.setViewVisibility(btnNextId, View.VISIBLE)

            val isThisTaskRunning = activeTimer != null && activeTimer.taskId == currentTask.id
            val taskElapsed = when {
                isThisTaskRunning -> elapsedMs
                else -> taskProgress[currentTask.id] ?: 0L
            }

            val pct = (taskElapsed.toFloat() / currentTask.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            rv.setProgressBar(pbId, 100, (pct * 100).toInt(), false)
            rv.setTextViewText(tvTimeId, "${fmtHoursMin(taskElapsed)} / ${fmtHoursMin(currentTask.durationMs)}")

            val playPauseIcon = if (isThisTaskRunning && isRunning) R.drawable.ic_pause else R.drawable.ic_play
            rv.setImageViewResource(btnPlayPauseId, playPauseIcon)
            rv.setViewVisibility(btnPlayPauseId, View.VISIBLE)

            val playPauseAction = if (isThisTaskRunning && isRunning) App.ACTION_PAUSE else App.ACTION_RESUME
            val playPauseIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                action = playPauseAction
                if (playPauseAction == App.ACTION_RESUME) {
                    putExtra(App.EXTRA_TASK_ID, currentTask.id)
                }
            }
            val playPausePI = PendingIntent.getBroadcast(
                this,
                currentTask.id.hashCode() + playPauseAction.hashCode(),
                playPauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(btnPlayPauseId, playPausePI)

            val hasProgress = taskProgress.containsKey(currentTask.id) || isThisTaskRunning
            if (hasProgress) {
                rv.setViewVisibility(btnStopId, View.VISIBLE)
                val stopIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                    action = App.ACTION_STOP
                }
                val stopPI = PendingIntent.getBroadcast(
                    this,
                    currentTask.id.hashCode() + App.ACTION_STOP.hashCode(),
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                rv.setOnClickPendingIntent(btnStopId, stopPI)
            } else {
                rv.setViewVisibility(btnStopId, View.INVISIBLE)
            }

            val prevIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                action = App.ACTION_PREV_TASK
                putExtra(App.EXTRA_BLOCK_ID, block.name)
                putExtra(App.EXTRA_TASK_ID, currentTask.id)
            }
            val prevPI = PendingIntent.getBroadcast(
                this,
                block.name.hashCode() + App.ACTION_PREV_TASK.hashCode(),
                prevIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(btnPrevId, prevPI)

            val nextIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                action = App.ACTION_NEXT_TASK
                putExtra(App.EXTRA_BLOCK_ID, block.name)
                putExtra(App.EXTRA_TASK_ID, currentTask.id)
            }
            val nextPI = PendingIntent.getBroadcast(
                this,
                block.name.hashCode() + App.ACTION_NEXT_TASK.hashCode(),
                nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(btnNextId, nextPI)

        } else {
            rv.setTextViewText(tvTaskNameId, "No tasks planned")
            rv.setProgressBar(pbId, 100, 0, false)
            rv.setTextViewText(tvTimeId, "--:-- / --:--")
            rv.setViewVisibility(btnPrevId, View.INVISIBLE)
            rv.setViewVisibility(btnNextId, View.INVISIBLE)
            rv.setViewVisibility(btnPlayPauseId, View.INVISIBLE)
            rv.setViewVisibility(btnStopId, View.INVISIBLE)
        }
    }

    private fun fmtHoursMin(ms: Long): String {
        val totalSec = ms / 1000
        val hr = totalSec / 3600
        val min = (totalSec % 3600) / 60
        return "%02d:%02d".format(hr, min)
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(App.NOTIFICATION_ID_BUBBLE, buildNotification())
    }
}
