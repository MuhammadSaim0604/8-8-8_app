package com.dayblocks.app.ui.tasks

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.dayblocks.app.R
import com.dayblocks.app.data.model.Block
import com.dayblocks.app.data.model.Task
import com.dayblocks.app.databinding.FragmentTasksBinding
import com.dayblocks.app.ui.sheets.AddTaskSheet
import com.dayblocks.app.ui.sheets.DeleteConfirmSheet
import com.dayblocks.app.ui.sheets.SwitchTaskSheet
import com.dayblocks.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class TasksFragment : Fragment() {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentTasksBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.tasks.collect { tasks ->
                binding.tvTasksSubtitle.text = "${tasks.size} tasks planned"
                binding.llEmptyState.visibility  = if (tasks.isEmpty()) View.VISIBLE else View.GONE
                binding.llTasksContainer.visibility = if (tasks.isNotEmpty()) View.VISIBLE else View.GONE

                binding.llTasksContainer.removeAllViews()
                if (tasks.isNotEmpty()) buildSections(tasks)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.nowMs.collect {
                // Lightweight redraw: just update running task card text
                refreshRunningCard()
            }
        }
    }

    private fun buildSections(tasks: List<Task>) {
        Block.values().forEach { block ->
            val bt = tasks.filter { it.blockId == block.name }
            val freeMin = 480 - bt.sumOf { it.durationMinutes }

            // Build a simple block header row
            val headerRow = buildBlockHeader(block, bt.size, freeMin)
            binding.llTasksContainer.addView(headerRow)

            // Task cards
            bt.forEach { task ->
                val card = buildTaskRow(task)
                binding.llTasksContainer.addView(card)
            }

            if (bt.isEmpty()) {
                val empty = TextView(requireContext()).apply {
                    text = "Tap + to add your first task"
                    textSize = 13f
                    setTextColor(Color.parseColor("#88FFFFFF"))
                    setPadding(0, 8.dp(), 0, 8.dp())
                }
                binding.llTasksContainer.addView(empty)
            }

            // Spacer
            binding.llTasksContainer.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 16.dp())
            })
        }
    }

    private fun buildBlockHeader(block: Block, count: Int, freeMin: Int): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp(); bottomMargin = 8.dp() }
        }

        row.addView(TextView(requireContext()).apply {
            text = "${block.emoji}  ${block.label.uppercase()}"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            try { setTextColor(Color.parseColor(block.colorHex)) } catch (_: Exception) { }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        row.addView(TextView(requireContext()).apply {
            text = "$count tasks · ${freeMin}m free"
            textSize = 11f
            setTextColor(Color.parseColor("#88FFFFFF"))
        })

        val btnAdd = com.google.android.material.button.MaterialButton(requireContext(),
            null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "+"
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPaddingRelative(10.dp(), 4.dp(), 10.dp(), 4.dp())
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8.dp() }
            setOnClickListener { showAddTask(block) }
        }
        row.addView(btnAdd)
        return row
    }

    private fun buildTaskRow(task: Task): View {
        val running  = vm.isRunning(task.id)
        val paused   = vm.isPaused(task.id)
        val elapsed  = vm.elapsedMsFor(task.id)
        val budgetMs = task.durationMinutes * 60_000L

        val card = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_task_card, binding.llTasksContainer, false)

        card.tag = task.id  // for refreshRunningCard

        // Accent bar
        val vAccent = card.findViewById<View>(R.id.vAccentBar)
        try { vAccent.setBackgroundColor(Color.parseColor(task.colorHex)) } catch (_: Exception) {}

        // Pulse dot
        card.findViewById<View>(R.id.vPulseDot).visibility = if (running) View.VISIBLE else View.GONE

        // Task name
        card.findViewById<TextView>(R.id.tvTaskName).text = task.name

        // Duration
        card.findViewById<TextView>(R.id.tvDuration).text = "⏱ ${fmtMs(budgetMs)}"

        // Elapsed
        val tvElapsed = card.findViewById<TextView>(R.id.tvElapsed)
        if (elapsed > 0 || running) {
            tvElapsed.visibility = View.VISIBLE
            tvElapsed.text = "· ${fmtElapsed(elapsed)}"
        } else {
            tvElapsed.visibility = View.GONE
        }

        // Top progress bar — width starts at 0dp, set it to pct * parentWidth
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

        // Edit button
        card.findViewById<View>(R.id.btnEdit).setOnClickListener {
            showEditTask(task)
        }

        // Delete button
        card.findViewById<View>(R.id.btnDelete).setOnClickListener {
            showDeleteConfirm(task)
        }

        // Play/Pause button
        val btnPlay = card.findViewById<FrameLayout>(R.id.btnPlayPause)
        val ivIcon  = card.findViewById<ImageView>(R.id.ivPlayPauseIcon)
        ivIcon.setImageResource(if (running) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.setOnClickListener { handlePlay(task) }

        return card
    }

    private fun refreshRunningCard() {
        // Just rebuild if something is running to avoid complex incremental updates
        val tasks = vm.tasks.value
        if (tasks.isNotEmpty()) {
            binding.llTasksContainer.removeAllViews()
            buildSections(tasks)
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

    private fun showAddTask(block: Block) = AddTaskSheet.newInstance(block).show(childFragmentManager, "add")
    private fun showEditTask(task: Task)  = AddTaskSheet.newInstanceEdit(task).show(childFragmentManager, "edit")
    private fun showDeleteConfirm(t: Task) = DeleteConfirmSheet.newInstance(t).show(childFragmentManager, "del")

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60
        return when { h > 0 && m > 0 -> "${h}h ${m}m"; h > 0 -> "${h}h"; else -> "${m}m" }
    }
    private fun fmtElapsed(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
