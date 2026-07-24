package com.dayblocks.app

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

    // Tab destination IDs — FAB is visible only on these screens
    private val tabDestinations = setOf(
        R.id.homeFragment,
        R.id.tasksFragment,
        R.id.statsFragment,
        R.id.settingsFragment
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        setupFab()
        setupMiniPlayer()
        startBubbleService()
    }

    private fun setupNavigation() {
        val navHost = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHost.navController

        binding.bottomNav.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, dest, _ ->
            val isTab = dest.id in tabDestinations
            val isRunning = dest.id == R.id.runningTaskFragment

            // Hide bottom nav on running task screen
            binding.bottomNav.visibility = if (isRunning) View.GONE else View.VISIBLE

            // FAB only visible on tab screens
            if (isTab) {
                binding.fabQuickMenu.show()
            } else {
                binding.fabQuickMenu.hide()
            }

            // Hide mini player on running task screen
            if (isRunning) {
                binding.miniPlayerContainer.visibility = View.GONE
            }
        }
    }

    private fun setupFab() {
        binding.fabQuickMenu.setOnClickListener {
            QuickMenuSheet().show(supportFragmentManager, "quick_menu")
        }
    }

    private fun setupMiniPlayer() {
        // Show/hide mini player based on timer state and update content
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

        // Update mini player content every tick (elapsed time, progress bar)
        lifecycleScope.launch {
            vm.nowMs.collectLatest { _ ->
                val ts = vm.timerState.value ?: return@collectLatest
                val task = vm.tasks.value.find { it.id == ts.taskId } ?: return@collectLatest
                val elapsed = vm.elapsedMsFor(ts.taskId)
                val budgetMs = task.durationMinutes * 60_000L

                val mp = binding.miniPlayer

                // Task name & block info
                mp.tvMiniTaskName.text = task.name
                mp.tvMiniBlock.text = task.block.label
                mp.tvMiniElapsed.text = fmtElapsed(elapsed)

                // Pause/resume button icon
                val isRunning = vm.isRunning(ts.taskId)
                mp.btnMiniPause.setIconResource(
                    if (isRunning) R.drawable.ic_pause else R.drawable.ic_play
                )

                // Progress bar width
                val pct = (elapsed.toFloat() / budgetMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                mp.miniPlayerProgress.post {
                    val parentW = (mp.miniPlayerProgress.parent as? ViewGroup)?.width ?: 0
                    if (parentW > 0) {
                        mp.miniPlayerProgress.layoutParams.width = (parentW * pct).toInt()
                        mp.miniPlayerProgress.requestLayout()
                    }
                }

                // Color the dot and progress with task color
                try {
                    val color = android.graphics.Color.parseColor(task.colorHex)
                    mp.miniDot.setBackgroundColor(color)
                    mp.miniPlayerProgress.setBackgroundColor(color)
                    mp.tvMiniElapsed.setTextColor(color)
                } catch (_: Exception) {}
            }
        }

        // Tap card body → navigate to running task
        binding.miniPlayer.miniPlayerCard.setOnClickListener {
            try {
                navController.navigate(R.id.action_global_runningTask)
            } catch (_: Exception) {}
        }

        // Pause / Resume button
        binding.miniPlayer.btnMiniPause.setOnClickListener {
            val ts = vm.timerState.value
            if (ts != null) {
                if (vm.isRunning(ts.taskId)) vm.pauseTask()
                else vm.resumeTask(ts.taskId)
            }
        }

        // Stop button
        binding.miniPlayer.btnMiniStop.setOnClickListener {
            vm.stopTask()
        }
    }

    private fun startBubbleService() {
        lifecycleScope.launch {
            vm.updateBubbleService()
        }
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
