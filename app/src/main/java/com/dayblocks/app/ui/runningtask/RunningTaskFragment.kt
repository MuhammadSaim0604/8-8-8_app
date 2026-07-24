package com.dayblocks.app.ui.runningtask

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.dayblocks.app.R
import com.dayblocks.app.databinding.FragmentRunningTaskBinding
import com.dayblocks.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class RunningTaskFragment : Fragment() {

    private var _binding: FragmentRunningTaskBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentRunningTaskBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener  { findNavController().popBackStack() }
        binding.btnGoBack.setOnClickListener { findNavController().popBackStack() }

        binding.btnPauseResume.setOnClickListener {
            val ts = vm.timerState.value ?: return@setOnClickListener
            val task = vm.tasks.value.find { it.id == ts.taskId } ?: return@setOnClickListener
            if (vm.isRunning(task.id)) vm.pauseTask() else vm.startTask(task)
        }

        binding.btnStop.setOnClickListener {
            vm.stopTask()
            findNavController().popBackStack()
        }

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.nowMs.collect { _ ->
                val ts = vm.timerState.value
                if (ts == null) {
                    showEmpty(); return@collect
                }
                val task = vm.tasks.value.find { it.id == ts.taskId }
                if (task == null) { showEmpty(); return@collect }

                binding.llNoTask.visibility = View.GONE
                binding.llContent.visibility = View.VISIBLE

                val running  = vm.isRunning(task.id)
                val elapsed  = vm.elapsedMsFor(task.id)
                val budgetMs = task.durationMinutes * 60_000L
                val remain   = budgetMs - elapsed
                val prog     = (elapsed.toFloat() / budgetMs).coerceIn(0f, 1f)
                val overtime = elapsed > budgetMs

                // Header
                binding.tvHeaderBlock.text = task.block.label
                try { binding.tvHeaderBlock.setTextColor(Color.parseColor(task.block.colorHex)) } catch (_: Exception) {}

                // Task name & budget
                binding.tvRunningTaskName.text = task.name
                try { binding.tvRunningTaskName.setTextColor(Color.parseColor(task.colorHex)) } catch (_: Exception) {}
                binding.tvBudgeted.text = "Budgeted ${fmtMs(budgetMs)}"

                // Ring color
                val ringColor = if (overtime) Color.parseColor("#FF4757")
                    else try { Color.parseColor(task.colorHex) } catch (_: Exception) { Color.parseColor("#3D9EF3") }
                binding.bigRing.progressColor = ringColor
                binding.bigRing.progress = prog   // Direct set for live updates (no animation)

                // Timer
                binding.tvBigTimer.text = fmtElapsed(elapsed)

                // Live dot
                binding.vLiveDot.visibility = if (running) View.VISIBLE else View.INVISIBLE

                // Stats row
                binding.tvStatElapsed.text = fmtElapsed(elapsed)
                if (overtime) {
                    binding.tvStatRemaining.text = "+${fmtElapsed(elapsed - budgetMs)}"
                    binding.tvStatRemaining.setTextColor(Color.parseColor("#FF4757"))
                    binding.tvRemainingLabel.text = "Overtime"
                } else {
                    binding.tvStatRemaining.text = fmtElapsed(remain)
                    binding.tvStatRemaining.setTextColor(Color.parseColor("#AAAAAA"))
                    binding.tvRemainingLabel.text = "Remaining"
                }
                binding.tvStatComplete.text = "${((elapsed.toFloat() / budgetMs) * 100).toInt()}%"

                // Overtime badge
                binding.cardOvertime.visibility = if (overtime) View.VISIBLE else View.GONE

                // Pause/Resume icon
                binding.ivPauseResumeIcon.setImageResource(
                    if (running) R.drawable.ic_pause else R.drawable.ic_play
                )
                binding.btnPauseResume.alpha = if (running) 1f else 0.65f
                binding.tvHint.text = if (running)
                    "Pause to save progress · Stop to end session"
                else "Tap play to resume"
            }
        }
    }

    private fun showEmpty() {
        binding.llNoTask.visibility  = View.VISIBLE
        binding.llContent.visibility = View.GONE
    }

    private fun fmtElapsed(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }
    private fun fmtMs(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60
        return when { h > 0 && m > 0 -> "${h}h ${m}m"; h > 0 -> "${h}h"; else -> "${m}m" }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
