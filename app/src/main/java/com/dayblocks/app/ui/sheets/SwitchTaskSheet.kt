package com.dayblocks.app.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.dayblocks.app.data.model.Task
import com.dayblocks.app.databinding.SheetSwitchTaskBinding
import com.dayblocks.app.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SwitchTaskSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSwitchTaskBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    companion object {
        fun newInstance(newTask: Task) = SwitchTaskSheet().apply {
            arguments = Bundle().apply {
                putString("newTaskId",   newTask.id)
                putString("newTaskName", newTask.name)
            }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = SheetSwitchTaskBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val newTaskId   = arguments?.getString("newTaskId")   ?: return
        val newTaskName = arguments?.getString("newTaskName")  ?: ""

        val currentTask = vm.timerState.value?.taskId?.let { id ->
            vm.tasks.value.find { it.id == id }
        }
        binding.tvCurrentTask.text = currentTask?.name ?: "Current Task"
        binding.tvNewTask.text     = newTaskName

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnSwitch.setOnClickListener {
            val task = vm.tasks.value.find { it.id == newTaskId }
            if (task != null) vm.switchTask(task)
            dismiss()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
