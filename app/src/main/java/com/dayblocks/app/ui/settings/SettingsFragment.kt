package com.dayblocks.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.dayblocks.app.R
import com.dayblocks.app.databinding.FragmentSettingsBinding
import com.dayblocks.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Data overview labels
        binding.cardDataTasks.tvLabel.text    = "Tasks"
        binding.cardDataSessions.tvLabel.text = "Sessions"
        binding.cardDataBlocks.tvLabel.text   = "Blocks"
        binding.cardDataBlocks.tvValue.text   = "3"

        setupClicks()
        observe()
        updatePermissions()
    }

    override fun onResume() {
        super.onResume()
        updatePermissions()
    }

    private fun updatePermissions() {
        val notifOk = NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
        binding.tvNotifStatus.text = if (notifOk) "Enabled" else "Disabled"
        binding.tvNotifStatus.setTextColor(requireContext().getColor(if (notifOk) R.color.success else R.color.danger))

        if (notifOk) {
            binding.btnNotifAction.visibility = View.GONE
        } else {
            binding.btnNotifAction.visibility = View.VISIBLE
            binding.btnNotifAction.text = "Settings"
            binding.btnNotifAction.setOnClickListener { openNotifSettings() }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val overlayOk = Settings.canDrawOverlays(requireContext())
            binding.tvOverlayStatus.text = if (overlayOk) "Enabled" else "Disabled"
            binding.tvOverlayStatus.setTextColor(requireContext().getColor(if (overlayOk) R.color.success else R.color.danger))
            binding.btnOverlayAction.visibility = if (overlayOk) View.GONE else View.VISIBLE
            binding.btnOverlayAction.setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${requireContext().packageName}")))
            }
        }
    }

    private fun setupClicks() {
        binding.btnClearHistory.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Clear History")
                .setMessage("All session history and statistics will be permanently deleted. Tasks are preserved.")
                .setPositiveButton("Clear") { _, _ -> vm.clearHistory() }
                .setNegativeButton("Cancel", null).show()
        }

        binding.btnResetAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Reset Everything")
                .setMessage("This will delete ALL tasks, history, and settings. Cannot be undone.")
                .setPositiveButton("Reset") { _, _ -> vm.resetEverything() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.tasks.collect { tasks ->
                binding.cardDataTasks.tvValue.text = tasks.size.toString()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.history.collect { hist ->
                binding.cardDataSessions.tvValue.text = hist.size.toString()
            }
        }

        val ver = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (_: Exception) { "1.0.0" }
        binding.tvVersion.text = "v$ver"
    }

    private fun openNotifSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${requireContext().packageName}"))
        }
        startActivity(intent)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
