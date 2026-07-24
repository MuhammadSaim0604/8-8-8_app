package com.dayblocks.app.ui.quickmenu

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.dayblocks.app.R
import com.dayblocks.app.data.model.Block
import com.dayblocks.app.data.model.Task
import com.dayblocks.app.databinding.SheetQuickMenuBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.dayblocks.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class QuickMenuSheet : BottomSheetDialogFragment() {

    private var _binding: SheetQuickMenuBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = SheetQuickMenuBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            peekHeight = (resources.displayMetrics.heightPixels * 0.86).toInt()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnClose.setOnClickListener { dismiss() }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { render(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.nowMs.collect { render(binding.etSearch.text?.toString() ?: "") }
        }
    }

    private fun render(query: String) {
        val all = vm.tasks.value
        val filtered = if (query.isBlank()) all
                       else all.filter { it.name.contains(query, ignoreCase = true) }

        var anyVisible = false
        for (block in Block.values()) {
            val bt = filtered.filter { it.blockId == block.name }
            when (block) {
                Block.SLEEP -> {
                    binding.sectionSleep.visibility = if (bt.isNotEmpty()) View.VISIBLE else View.GONE
                    if (bt.isNotEmpty()) { binding.tvSleepCount.text = "${bt.size} tasks"; fillContainer(binding.containerSleep, bt); anyVisible = true }
                }
                Block.WORK -> {
                    binding.sectionWork.visibility = if (bt.isNotEmpty()) View.VISIBLE else View.GONE
                    if (bt.isNotEmpty()) { binding.tvWorkCount.text = "${bt.size} tasks"; fillContainer(binding.containerWork, bt); anyVisible = true }
                }
                Block.PERSONAL -> {
                    binding.sectionPersonal.visibility = if (bt.isNotEmpty()) View.VISIBLE else View.GONE
                    if (bt.isNotEmpty()) { binding.tvPersonalCount.text = "${bt.size} tasks"; fillContainer(binding.containerPersonal, bt); anyVisible = true }
                }
            }
        }
        binding.tvNoResults.visibility  = if (!anyVisible) View.VISIBLE else View.GONE
        binding.scrollContent.visibility = if (anyVisible) View.VISIBLE else View.GONE
    }

    private fun fillContainer(container: LinearLayout, tasks: List<Task>) {
        container.removeAllViews()
        tasks.forEach { container.addView(buildRow(it)) }
    }

    private fun buildRow(task: Task): View {
        val running  = vm.isRunning(task.id)
        val elapsed  = vm.elapsedMsFor(task.id)
        val budgetMs = task.durationMinutes * 60_000L
        val pct      = ((elapsed.toFloat() / budgetMs.coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)

        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A22"))
            val p = 12.dp()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
        }

        // Row 1: name + controls
        val row1 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }

        row1.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(requireContext()).apply { text = task.name; textSize = 14f; setTextColor(Color.WHITE) })
            addView(TextView(requireContext()).apply { text = fmtMs(budgetMs); textSize = 11f; setTextColor(Color.parseColor("#88FFFFFF")) })
        })

        val btnPlay = ImageButton(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(36.dp(), 36.dp())
            setBackgroundResource(R.drawable.bg_btn_play)
            setImageResource(if (running) R.drawable.ic_pause else R.drawable.ic_play)
            setOnClickListener {
                if (running) vm.pauseTask()
                else {
                    val cur = vm.timerState.value
                    val t = vm.tasks.value.find { it.id == task.id } ?: return@setOnClickListener
                    if (cur != null && cur.taskId != task.id) vm.switchTask(t) else vm.startTask(t)
                }
            }
        }
        row1.addView(btnPlay)

        if (elapsed > 0 || running) {
            row1.addView(ImageButton(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(36.dp(), 36.dp()).apply { marginStart = 8.dp() }
                setBackgroundResource(R.drawable.bg_circle_danger)
                setImageResource(R.drawable.ic_stop)
                setOnClickListener { vm.stopTask() }
            })
        }
        card.addView(row1)

        // Progress bar
        card.addView(ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 3.dp()).apply { topMargin = 8.dp() }
            max = 100; progress = pct
            try { progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(task.colorHex)) } catch (_: Exception) {}
        })

        // Elapsed/total label
        card.addView(TextView(requireContext()).apply {
            text = "${fmtElapsed(elapsed)} / ${fmtMs(budgetMs)}"
            textSize = 11f; setTextColor(Color.parseColor("#66FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp() }
        })
        return card
    }

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
