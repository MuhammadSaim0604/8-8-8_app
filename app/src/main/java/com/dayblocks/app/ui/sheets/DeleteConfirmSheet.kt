package com.dayblocks.app.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.dayblocks.app.data.model.Task
import com.dayblocks.app.databinding.SheetDeleteConfirmBinding
import com.dayblocks.app.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class DeleteConfirmSheet : BottomSheetDialogFragment() {

    private var _binding: SheetDeleteConfirmBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    companion object {
        fun newInstance(task: Task) = DeleteConfirmSheet().apply {
            arguments = Bundle().apply {
                putString("taskId",    task.id)
                putString("taskName",  task.name)
                putInt("durMin",       task.durationMinutes)
            }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = SheetDeleteConfirmBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val taskId  = arguments?.getString("taskId")  ?: return
        val name    = arguments?.getString("taskName") ?: ""
        val durMin  = arguments?.getInt("durMin")      ?: 0
        val h = durMin / 60; val m = durMin % 60

        binding.tvTaskName.text = name
        binding.tvTaskDuration.text = when {
            h > 0 && m > 0 -> "${h}h ${m}m"; h > 0 -> "${h}h"; else -> "${m}m"
        }
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnDelete.setOnClickListener { vm.deleteTask(taskId); dismiss() }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
