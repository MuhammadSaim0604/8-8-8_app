package com.dayblocks.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.dayblocks.app.databinding.ActivityMainBinding
import com.dayblocks.app.ui.quickmenu.QuickMenuSheet
import com.dayblocks.app.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        setupMiniPlayer()
        startBubbleService()

        // Bubble tap → open QuickMenuSheet
        if (intent?.getBooleanExtra(App.EXTRA_OPEN_QUICK_MENU, false) == true) {
            openQuickMenu()
        }
    }

    // Called when the app is already running and receives a new intent (FLAG_ACTIVITY_SINGLE_TOP)
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(App.EXTRA_OPEN_QUICK_MENU, false) == true) {
            openQuickMenu()
        }
    }

    private fun openQuickMenu() {
        // Post to avoid race with fragment manager transactions on cold start
        binding.root.post {
            if (!supportFragmentManager.isStateSaved) {
                QuickMenuSheet().show(supportFragmentManager, "quick_menu")
            }
        }
    }

    private fun setupNavigation() {
        val navHost = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHost.navController

        binding.bottomNav.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, dest, _ ->
            val isRunning = dest.id == R.id.runningTaskFragment
            binding.bottomNav.visibility = if (isRunning) View.GONE else View.VISIBLE

            // Hide mini player on running task screen
            if (isRunning) {
                binding.miniPlayerContainer.visibility = View.GONE
            }
        }
    }

    private fun setupMiniPlayer() {
        // Show/hide mini player based on timer state
        lifecycleScope.launch {
            vm.timerState.collectLatest { ts ->
                val isRunningTaskScreen =
                    navController.currentDestination?.id == R.id.runningTaskFragment
                if (ts != null && !isRunningTaskScreen) {
                    binding.miniPlayerContainer.visibility = View.VISIBLE
                    binding.miniPlayerContainer.animate()
                        .translationY(0f).alpha(1f).setDuration(300).start()
                } else if (ts == null) {
                    binding.miniPlayerContainer.animate()
                        .translationY(200f).alpha(0f).setDuration(250)
                        .withEndAction {
                            binding.miniPlayerContainer.visibility = View.GONE
                        }.start()
                }
            }
        }

        // Update mini player content every tick
        lifecycleScope.launch {
            vm.nowMs.collectLatest { _ ->
                val ts = vm.timerState.value ?: return@collectLatest
                val task = vm.tasks.value.find { it.id == ts.taskId } ?: return@collectLatest
                val elapsed = vm.elapsedMsFor(ts.taskId)
                val budgetMs = task.durationMinutes * 60_000L
                val mp = binding.miniPlayer

                mp.tvMiniTaskName.text = task.name
                mp.tvMiniBlock.text = task.block.label
                mp.tvMiniElapsed.text = fmtElapsed(elapsed)

                val isRunning = vm.isRunning(ts.taskId)
                mp.btnMiniPause.setIconResource(
                    if (isRunning) R.drawable.ic_pause else R.drawable.ic_play
                )

                val pct = (elapsed.toFloat() / budgetMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                mp.miniPlayerProgress.post {
                    val parentW = (mp.miniPlayerProgress.parent as? ViewGroup)?.width ?: 0
                    if (parentW > 0) {
                        mp.miniPlayerProgress.layoutParams.width = (parentW * pct).toInt()
                        mp.miniPlayerProgress.requestLayout()
                    }
                }

                try {
                    val color = android.graphics.Color.parseColor(task.colorHex)
                    mp.miniDot.setBackgroundColor(color)
                    mp.miniPlayerProgress.setBackgroundColor(color)
                    mp.tvMiniElapsed.setTextColor(color)
                } catch (_: Exception) {}
            }
        }

        // Tap card body → navigate to Running Task screen
        binding.miniPlayer.miniPlayerCard.setOnClickListener {
            try { navController.navigate(R.id.action_global_runningTask) } catch (_: Exception) {}
        }

        // Pause / Resume
        binding.miniPlayer.btnMiniPause.setOnClickListener {
            val ts = vm.timerState.value
            if (ts != null) {
                if (vm.isRunning(ts.taskId)) vm.pauseTask()
                else vm.resumeTask(ts.taskId)
            }
        }

        // Stop
        binding.miniPlayer.btnMiniStop.setOnClickListener {
            vm.stopTask()
        }
    }

    private fun startBubbleService() {
        lifecycleScope.launch { vm.updateBubbleService() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { vm.updateBubbleService() }
    }

    private fun fmtElapsed(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
               else String.format("%02d:%02d", m, sec)
    }
}
