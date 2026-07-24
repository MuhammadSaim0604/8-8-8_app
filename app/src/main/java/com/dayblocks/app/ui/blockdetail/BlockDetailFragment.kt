package com.dayblocks.app.ui.blockdetail

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.dayblocks.app.R
import com.dayblocks.app.data.model.Block
import com.dayblocks.app.data.model.Task
import com.dayblocks.app.databinding.FragmentBlockDetailBinding
import com.dayblocks.app.ui.sheets.AddTaskSheet
import com.dayblocks.app.ui.sheets.DeleteConfirmSheet
import com.dayblocks.app.ui.sheets.SwitchTaskSheet
import com.dayblocks.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class BlockDetailFragment : Fragment() {

    private var _binding: FragmentBlockDetailBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    private lateinit var block: Block

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentBlockDetailBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        block = Block.valueOf(arguments?.getString("blockId") ?: Block.SLEEP.name)

        // Hero header
        binding.tvHeroEmoji.text    = block.emoji
        binding.tvHeroName.text     = block.label
        binding.tvHeroSubtitle.text = block.subtitle

        // Toolbar back
        binding.toolbar.setNavigationIcon(R.drawable.ic_chevron_down)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        binding.btnAddTask.setOnClickListener { showAddTask() }
        binding.fabAdd.setOnClickListener     { showAddTask() }
        binding.btnAddFirstTask.setOnClickListener { showAddTask() }

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.tasks.collect { allTasks ->
                val tasks = allTasks.filter { it.blockId == block.name }
                val used  = tasks.sumOf { it.durationMinutes }
                val free  = 480 - used
                val prog  = (used / 480f).coerceIn(0f, 1f)

                // Hero stats
                binding.tvHeroUsed.text = fmtMin(used)
                binding.tvHeroFree.text = fmtMin(free)
                binding.heroRing.animateTo(prog)

                // Capacity fill bar (vCapacityFill is a View inside a FrameLayout)
                binding.vCapacityFill.post {
                    val parent = binding.vCapacityFill.parent as? View
                    val totalW = parent?.width ?: 0
                    val params = binding.vCapacityFill.layoutParams
                    params.width = (totalW * prog).toInt()
                    binding.vCapacityFill.layoutParams = params
                }

                if (used >= 480) {
                    binding.tvCapacityBadge.text = "Block full ⚠️"
                    binding.tvCapacityBadge.setTextColor(requireContext().getColor(R.color.danger))
                    binding.tvCapacityPercent.text = "100%"
                    binding.btnAddTask.text = "Free up time"
                } else {
                    binding.tvCapacityBadge.text = "${free}m remaining"
                    binding.tvCapacityBadge.setTextColor(requireContext().getColor(R.color.text_secondary))
                    binding.tvCapacityPercent.text = "${(prog * 100).toInt()}%"
                    binding.btnAddTask.text = "+ Add Task"
                }

                // Tasks section title
                binding.tvTasksSectionTitle.text = "Tasks (${tasks.size})"

                // Empty / list
                binding.llDetailEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
                binding.llTasksList.visibility   = if (tasks.isNotEmpty()) View.VISIBLE else View.GONE
                binding.fabAdd.visibility        = if (tasks.isNotEmpty()) View.VISIBLE else View.GONE

                // Build task cards
                binding.llTasksList.removeAllViews()
                tasks.forEach { task ->
                    binding.llTasksList.addView(buildTaskCard(task))
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.nowMs.collect { rebuildRunning() }
        }
    }

    private fun buildTaskCard(task: Task): View {
        val running  = vm.isRunning(task.id)
        val elapsed  = vm.elapsedMsFor(task.id)
        val budgetMs = task.durationMinutes * 60_000L

        val card = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_task_card, binding.llTasksList, false)

        // Accent bar color
        try { card.findViewById<View>(R.id.vAccentBar).setBackgroundColor(Color.parseColor(task.colorHex)) }
        catch (_: Exception) {}

        // Pulse dot
        card.findViewById<View>(R.id.vPulseDot).visibility = if (running) View.VISIBLE else View.GONE

        // Name
        card.findViewById<TextView>(R.id.tvTaskName).text = task.name

        // Duration
        card.findViewById<TextView>(R.id.tvDuration).text = "⏱ ${fmtMs(budgetMs)}"

        // Elapsed
        val tvEl = card.findViewById<TextView>(R.id.tvElapsed)
        if (elapsed > 0 || running) {
            tvEl.visibility = View.VISIBLE; tvEl.text = "· ${fmtElapsed(elapsed)}"
        } else {
            tvEl.visibility = View.GONE
        }

        // Progress bar (top strip) — width starts at 0dp, set to pct * parentWidth
        val vProg = card.findViewById<View>(R.id.vTaskProgress)
        if (elapsed > 0 || running) {
            val pct = (elapsed.toFloat() / budgetMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            vProg.post {
                val parentW = (vProg.parent as? ViewGroup)?.width ?: 0
                vProg.layoutParams.width = (parentW * pct).toInt()
                vProg.requestLayout()
            }
            try { vProg.setBackgroundColor(Color.parseColor(task.colorHex)) } catch (_: Exception) {}
        }

        // Buttons
        card.findViewById<View>(R.id.btnEdit).setOnClickListener {
            AddTaskSheet.newInstanceEdit(task).show(childFragmentManager, "edit")
        }
        card.findViewById<View>(R.id.btnDelete).setOnClickListener {
            DeleteConfirmSheet.newInstance(task).show(childFragmentManager, "del")
        }

        val ivIcon = card.findViewById<ImageView>(R.id.ivPlayPauseIcon)
        ivIcon.setImageResource(if (running) R.drawable.ic_pause else R.drawable.ic_play)
        card.findViewById<FrameLayout>(R.id.btnPlayPause).setOnClickListener { handlePlay(task) }

        return card
    }

    private fun rebuildRunning() {
        val tasks = vm.tasks.value.filter { it.blockId == block.name }
        if (tasks.isNotEmpty() && binding.llTasksList.childCount > 0) {
            binding.llTasksList.removeAllViews()
            tasks.forEach { binding.llTasksList.addView(buildTaskCard(it)) }
        }
    }

    private fun handlePlay(task: Task) {
        val cur = vm.timerState.value
        when {
            cur != null && cur.taskId != task.id ->
                SwitchTaskSheet.newInstance(task).show(childFragmentManager, "switch")
            vm.isRunning(task.id) -> vm.pauseTask()
            else -> vm.startTask(task)
        }
    }

    private fun showAddTask() = AddTaskSheet.newInstance(block).show(childFragmentManager, "add")

    private fun fmtMin(m: Int): String {
        val h = m / 60; val min = m % 60
        return when { h > 0 && min > 0 -> "${h}h ${min}m"; h > 0 -> "${h}h"; else -> "${min}m" }
    }
    private fun fmtMs(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60
        return when { h > 0 && m > 0 -> "${h}h ${m}m"; h > 0 -> "${h}h"; else -> "${m}m" }
    }
    private fun fmtElapsed(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
