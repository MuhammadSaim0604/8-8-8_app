package com.dayblocks.app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.dayblocks.app.R
import com.dayblocks.app.data.model.Block
import com.dayblocks.app.databinding.FragmentHomeBinding
import com.dayblocks.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGreeting()
        setupClicks()
        observe()

        // Initialize summary card labels
        binding.cardTasks.tvLabel.text     = "Tasks"
        binding.cardPlanned.tvLabel.text   = "Planned"
        binding.cardUnplanned.tvLabel.text = "Unplanned"

        // Initialize block card content
        binding.blockCardSleep.tvBlockEmoji.text    = Block.SLEEP.emoji
        binding.blockCardSleep.tvBlockName.text     = Block.SLEEP.label
        binding.blockCardSleep.tvBlockSubtitle.text = Block.SLEEP.subtitle
        binding.blockCardWork.tvBlockEmoji.text     = Block.WORK.emoji
        binding.blockCardWork.tvBlockName.text      = Block.WORK.label
        binding.blockCardWork.tvBlockSubtitle.text  = Block.WORK.subtitle
        binding.blockCardPersonal.tvBlockEmoji.text    = Block.PERSONAL.emoji
        binding.blockCardPersonal.tvBlockName.text     = Block.PERSONAL.label
        binding.blockCardPersonal.tvBlockSubtitle.text = Block.PERSONAL.subtitle
    }

    private fun setupGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val (txt, icon) = when {
            hour < 6  -> "Good Night"     to "🌙"
            hour < 12 -> "Good Morning"   to "☀️"
            hour < 17 -> "Good Afternoon" to "🌤"
            hour < 21 -> "Good Evening"   to "🌆"
            else      -> "Good Night"     to "🌙"
        }
        binding.tvGreeting.text = "$icon $txt"
        binding.tvDate.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
    }

    private fun setupClicks() {
        binding.cardSleep.setOnClickListener    { go(Block.SLEEP) }
        binding.cardWork.setOnClickListener     { go(Block.WORK) }
        binding.cardPersonal.setOnClickListener { go(Block.PERSONAL) }
        binding.cardNowPlaying.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_runningTask)
        }
    }

    private fun go(block: Block) {
        findNavController().navigate(R.id.action_home_to_blockDetail,
            Bundle().apply { putString("blockId", block.name) })
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.tasks.collect { tasks ->
                val totalMin  = tasks.sumOf { it.durationMinutes }
                val unplanned = 1440 - totalMin

                binding.cardTasks.tvValue.text     = tasks.size.toString()
                binding.cardPlanned.tvValue.text   = fmtMin(totalMin)
                binding.cardUnplanned.tvValue.text = fmtMin(unplanned)

                Block.values().forEach { block ->
                    val bt   = tasks.filter { it.blockId == block.name }
                    val used = bt.sumOf { it.durationMinutes }
                    val free = 480 - used
                    val prog = (used / 480f).coerceIn(0f, 1f)
                    when (block) {
                        Block.SLEEP -> with(binding.blockCardSleep) {
                            tvUsed.text     = fmtMin(used)
                            tvFree.text     = fmtMin(free)
                            tvTaskCount.text = bt.size.toString()
                            progressRing.animateTo(prog)
                        }
                        Block.WORK -> with(binding.blockCardWork) {
                            tvUsed.text     = fmtMin(used)
                            tvFree.text     = fmtMin(free)
                            tvTaskCount.text = bt.size.toString()
                            progressRing.animateTo(prog)
                        }
                        Block.PERSONAL -> with(binding.blockCardPersonal) {
                            tvUsed.text     = fmtMin(used)
                            tvFree.text     = fmtMin(free)
                            tvTaskCount.text = bt.size.toString()
                            progressRing.animateTo(prog)
                        }
                    }
                }
                binding.cardTip.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.timerState.collect { ts ->
                if (ts != null) {
                    val task = vm.tasks.value.find { it.id == ts.taskId }
                    if (task != null) {
                        binding.cardNowPlaying.visibility = View.VISIBLE
                        binding.tvNowPlayingName.text     = task.name
                    } else {
                        binding.cardNowPlaying.visibility = View.GONE
                    }
                } else {
                    binding.cardNowPlaying.visibility = View.GONE
                }
            }
        }
    }

    private fun fmtMin(m: Int): String {
        val h = m / 60; val min = m % 60
        return when { h > 0 && min > 0 -> "${h}h ${min}m"; h > 0 -> "${h}h"; else -> "${min}m" }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
