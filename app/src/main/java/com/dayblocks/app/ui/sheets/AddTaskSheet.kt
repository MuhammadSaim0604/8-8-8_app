package com.dayblocks.app.ui.sheets

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import com.dayblocks.app.R
import com.dayblocks.app.data.model.Block
import com.dayblocks.app.data.model.Task
import com.dayblocks.app.data.model.TaskColors
import com.dayblocks.app.databinding.SheetAddTaskBinding
import com.dayblocks.app.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip

class AddTaskSheet : BottomSheetDialogFragment() {

    private var _binding: SheetAddTaskBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    private var block: Block = Block.SLEEP
    private var editTask: Task? = null
    private var selectedColor: String = TaskColors.palette[0]

    companion object {
        fun newInstance(block: Block) = AddTaskSheet().apply {
            arguments = Bundle().apply { putString("block", block.name) }
        }
        fun newInstanceEdit(task: Task) = AddTaskSheet().apply {
            arguments = Bundle().apply {
                putString("block",        task.blockId)
                putString("editTaskId",   task.id)
                putString("editName",     task.name)
                putInt("editDuration",    task.durationMinutes)
                putString("editColor",    task.colorHex)
            }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = SheetAddTaskBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        block = Block.valueOf(arguments?.getString("block") ?: Block.SLEEP.name)

        val editId = arguments?.getString("editTaskId")
        if (editId != null) {
            editTask = Task(
                id              = editId,
                blockId         = block.name,
                name            = arguments?.getString("editName") ?: "",
                durationMinutes = arguments?.getInt("editDuration") ?: 60,
                colorHex        = arguments?.getString("editColor") ?: TaskColors.palette[0]
            )
        }

        // Title & close
        binding.tvSheetTitle.text = if (editTask != null) "Edit Task" else "Add Task"
        binding.btnClose.setOnClickListener { dismiss() }

        // Pickers
        binding.pickerHours.minValue   = 0; binding.pickerHours.maxValue   = 7
        binding.pickerMinutes.minValue = 0; binding.pickerMinutes.maxValue = 59
        val tick = NumberPicker.OnValueChangeListener { _, _, _ -> updateSummary() }
        binding.pickerHours.setOnValueChangedListener(tick)
        binding.pickerMinutes.setOnValueChangedListener(tick)

        // Color chips
        setupColorGrid()

        // Name watcher
        binding.etTaskName.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateSummary() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // Pre-fill
        editTask?.let { t ->
            binding.etTaskName.setText(t.name)
            binding.pickerHours.value   = t.durationMinutes / 60
            binding.pickerMinutes.value = t.durationMinutes % 60
            selectedColor = t.colorHex
        } ?: run {
            binding.pickerHours.value   = 1
            binding.pickerMinutes.value = 0
        }
        updateColorSelection()
        updateSummary()

        binding.btnSave.setOnClickListener { saveTask() }
    }

    private fun setupColorGrid() {
        binding.colorChipGroup.removeAllViews()
        TaskColors.palette.forEach { hex ->
            val chip = Chip(requireContext()).apply {
                text = ""; isCheckable = true
                val sz = (40 * resources.displayMetrics.density).toInt()
                layoutParams = ViewGroup.LayoutParams(sz, sz)
                try { chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor(hex)) }
                catch (_: Exception) {}
                tag = hex
            }
            chip.setOnClickListener { selectedColor = hex; updateColorSelection() }
            binding.colorChipGroup.addView(chip)
        }
    }

    private fun updateColorSelection() {
        for (i in 0 until binding.colorChipGroup.childCount) {
            val c = binding.colorChipGroup.getChildAt(i) as? Chip ?: continue
            c.isChecked = c.tag == selectedColor
        }
    }

    private fun updateSummary() {
        val h     = binding.pickerHours.value
        val m     = binding.pickerMinutes.value
        val total = h * 60 + m

        binding.tvDurationSummary.text = when {
            h > 0 && m > 0 -> "${h}h ${m}m · $total min total"
            h > 0           -> "${h}h · $total min total"
            else            -> "${m}m total"
        }

        val blockTasks = vm.tasks.value.filter { it.blockId == block.name && it.id != editTask?.id }
        val freeMin    = 480 - blockTasks.sumOf { it.durationMinutes }

        when {
            total >= 480 -> {
                binding.tvDurationBadge.text = "≥8h!"
                binding.tvDurationBadge.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.danger))
            }
            total > freeMin -> {
                binding.tvDurationBadge.text = "+${total - freeMin}m over"
                binding.tvDurationBadge.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#F5A623"))
            }
            else -> {
                binding.tvDurationBadge.text = "${freeMin - total}m left"
                binding.tvDurationBadge.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#34C759"))
            }
        }

        val nameOk = binding.etTaskName.text?.isNotBlank() == true
        val durOk  = total in 1..479
        val capOk  = total <= freeMin
        binding.btnSave.isEnabled = nameOk && durOk && capOk
        binding.tvError.visibility = View.GONE

        if (!nameOk) return
        if (!durOk) {
            binding.tvError.text = if (total == 0) "Duration must be at least 1 minute"
                                   else "Maximum task duration is 7h 59m"
            binding.tvError.visibility = View.VISIBLE
        } else if (!capOk) {
            binding.tvError.text = "Only ${freeMin}m free in this block."
            binding.tvError.visibility = View.VISIBLE
        }
    }

    private fun saveTask() {
        val name  = binding.etTaskName.text?.toString()?.trim() ?: return
        val h     = binding.pickerHours.value
        val m     = binding.pickerMinutes.value
        val total = h * 60 + m
        if (name.isBlank() || total < 1) return

        val existing = editTask
        if (existing != null) {
            vm.updateTask(existing.copy(name = name, durationMinutes = total, colorHex = selectedColor))
        } else {
            vm.addTask(Task(blockId = block.name, name = name,
                durationMinutes = total, colorHex = selectedColor))
        }
        dismiss()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
