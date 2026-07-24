package com.dayblocks.app

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.dayblocks.app.databinding.ActivityMainBinding
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
    }

    private fun setupNavigation() {
        val navHost = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHost.navController

        binding.bottomNav.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, dest, _ ->
            val hideNav = dest.id == R.id.runningTaskFragment
            binding.bottomNav.visibility = if (hideNav) View.GONE else View.VISIBLE
        }
    }

    private fun setupMiniPlayer() {
        lifecycleScope.launch {
            vm.timerState.collectLatest { ts ->
                if (ts != null) {
                    binding.miniPlayerContainer.visibility = View.VISIBLE
                    binding.miniPlayerContainer.animate()
                        .translationY(0f).alpha(1f).setDuration(300).start()
                } else {
                    binding.miniPlayerContainer.animate()
                        .translationY(200f).alpha(0f).setDuration(250)
                        .withEndAction {
                            binding.miniPlayerContainer.visibility = View.GONE
                        }.start()
                }
            }
        }
    }

    private fun startBubbleService() {
        lifecycleScope.launch {
            vm.updateBubbleService()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-post notification when app comes to foreground
        lifecycleScope.launch { vm.updateBubbleService() }
    }
}
