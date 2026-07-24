package com.dayblocks.app.ui.stats

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.dayblocks.app.R
import com.dayblocks.app.data.model.Block
import com.dayblocks.app.data.model.HistoryEntry
import com.dayblocks.app.databinding.FragmentStatsBinding
import com.dayblocks.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentStatsBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize card labels
        binding.cardStreak.tvLabel.text       = "Day Streak 🔥"
        binding.cardTotalTracked.tvLabel.text = "Total Tracked"
        binding.cardSessions.tvLabel.text     = "Sessions"

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.history.collect { history ->
                val hasData = history.isNotEmpty()
                binding.llStatsEmpty.visibility = if (!hasData) View.VISIBLE else View.GONE
                binding.llSummaryCards.visibility = if (hasData) View.VISIBLE else View.GONE
                binding.cardTodayUsage.visibility = if (hasData) View.VISIBLE else View.GONE
                binding.cardWeekly.visibility     = if (hasData) View.VISIBLE else View.GONE
                binding.cardTopTasks.visibility   = if (hasData) View.VISIBLE else View.GONE
                binding.cardRecent.visibility     = if (hasData) View.VISIBLE else View.GONE
                if (!hasData) return@collect

                // Summary
                binding.cardStreak.tvValue.text       = vm.dayStreak().toString()
                binding.cardTotalTracked.tvValue.text = fmtMs(history.sumOf { it.elapsedMs })
                binding.cardSessions.tvValue.text     = history.size.toString()

                // Today's usage
                val today  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val todayH = history.filter { it.date == today }
                val maxMs  = 8L * 3600_000L

                fun blockMs(b: Block) = todayH.filter { it.blockId == b.name }.sumOf { it.elapsedMs }
                val sleepMs    = blockMs(Block.SLEEP)
                val workMs     = blockMs(Block.WORK)
                val personalMs = blockMs(Block.PERSONAL)

                // pbSleep/Work/Personal are Views with bg_progress drawables — animate via scaleX
                animateBar(binding.pbSleep,     sleepMs,    maxMs)
                animateBar(binding.pbWork,      workMs,     maxMs)
                animateBar(binding.pbPersonal,  personalMs, maxMs)

                binding.tvSleepTime.text    = fmtMs(sleepMs)
                binding.tvWorkTime.text     = fmtMs(workMs)
                binding.tvPersonalTime.text = fmtMs(personalMs)

                buildWeekChart()
                buildTopTasks(history)
                buildRecentSessions(history)
            }
        }
    }

    private fun animateBar(v: View, ms: Long, maxMs: Long) {
        val pct = (ms.toFloat() / maxMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        v.post {
            v.pivotX = 0f
            v.animate().scaleX(pct).setDuration(600).start()
        }
    }

    private fun buildWeekChart() {
        val container = binding.llBarChart
        container.removeAllViews()

        val days  = vm.weeklyData()  // List<Pair<label, ms>>
        val maxMs = days.maxOfOrNull { it.second } ?: 1L
        val total = days.sumOf { it.second }
        binding.tvWeekTotal.text = fmtMs(total)

        days.forEachIndexed { idx, (label, ms) ->
            val isToday = idx == 6
            val barPct  = if (maxMs > 0) (ms.toFloat() / maxMs) else 0f

            val col = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            col.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    12.dp(), maxOf((barPct * 80).toInt().dp(), 4.dp())
                )
                setBackgroundColor(if (isToday)
                    ContextCompat.getColor(requireContext(), R.color.accent_blue)
                else Color.parseColor("#33FFFFFF"))
            })
            col.addView(TextView(requireContext()).apply {
                text = label; textSize = 10f
                setTextColor(if (isToday)
                    ContextCompat.getColor(requireContext(), R.color.accent_blue)
                else Color.parseColor("#88FFFFFF"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4.dp() }
            })
            container.addView(col)
        }
    }

    private fun buildTopTasks(history: List<HistoryEntry>) {
        val container = binding.llTopTasks
        container.removeAllViews()

        val taskTotals = history.groupBy { it.taskId }
            .mapValues { (_, e) -> e.sumOf { it.elapsedMs } }
            .entries.sortedByDescending { it.value }.take(5)
        val maxMs  = taskTotals.firstOrNull()?.value ?: 1L
        val medals = listOf("🥇", "🥈", "🥉", "4️⃣", "5️⃣")

        taskTotals.forEachIndexed { i, (taskId, ms) ->
            val entry = history.lastOrNull { it.taskId == taskId } ?: return@forEachIndexed
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, 8.dp(), 0, 8.dp())
            }
            val top = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(requireContext()).apply {
                text = medals.getOrElse(i) { "${i+1}" }; textSize = 16f
                layoutParams = LinearLayout.LayoutParams(32.dp(), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            top.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(requireContext()).apply {
                    text = entry.taskName; textSize = 14f; setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(requireContext()).apply {
                    text = entry.blockName; textSize = 11f; setTextColor(Color.parseColor("#88FFFFFF"))
                })
            })
            top.addView(TextView(requireContext()).apply {
                text = fmtMs(ms); textSize = 12f; setTextColor(Color.parseColor("#AAAAAA"))
            })
            row.addView(top)
            row.addView(ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4.dp())
                    .apply { topMargin = 6.dp() }
                max = 100; progress = pct(ms, maxMs)
                progressTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.accent_blue))
            })
            container.addView(row)
        }
    }

    private fun buildRecentSessions(history: List<HistoryEntry>) {
        val container = binding.llRecentSessions
        container.removeAllViews()
        history.sortedByDescending { it.stoppedAt }.take(10).forEach { e ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10.dp(), 0, 10.dp())
            }
            row.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(requireContext()).apply { text = e.taskName; textSize = 13f; setTextColor(Color.WHITE) })
                addView(TextView(requireContext()).apply {
                    text = "${e.blockName} · ${e.date}"; textSize = 11f; setTextColor(Color.parseColor("#88FFFFFF"))
                })
            })
            row.addView(TextView(requireContext()).apply {
                text = fmtMs(e.elapsedMs); textSize = 12f; setTextColor(Color.parseColor("#AAAAAA"))
            })
            container.addView(row)
            container.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 2 }
                setBackgroundColor(Color.parseColor("#22FFFFFF"))
            })
        }
    }

    private fun pct(ms: Long, max: Long) = ((ms.toFloat() / max.coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)
    private fun fmtMs(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60
        return when { h > 0 && m > 0 -> "${h}h ${m}m"; h > 0 -> "${h}h"; else -> "${m}m" }
    }
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
