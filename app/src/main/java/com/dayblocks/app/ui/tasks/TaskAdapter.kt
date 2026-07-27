package com.dayblocks.app.ui.tasks

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dayblocks.app.R
import com.dayblocks.app.data.model.Task
import com.dayblocks.app.data.model.TimerState

/**
 * Task card adapter — kept for potential future RecyclerView usage.
 * The main app currently builds task rows dynamically; this adapter is
 * ready if a RecyclerView-based implementation is needed.
 */
class TaskAdapter(
    private val onPlay:    (Task) -> Unit,
    private val onStop:    (Task) -> Unit,
    private val onEdit:    (Task) -> Unit,
    private val onDelete:  (Task) -> Unit,
    private val timerState: () -> TimerState?,
    private val elapsedMs:  (String) -> Long,
    private val isRunning:  (String) -> Boolean,
    private val isPaused:   (String) -> Boolean
) : ListAdapter<Task, TaskAdapter.VH>(Diff()) {

    class Diff : DiffUtil.ItemCallback<Task>() {
        override fun areItemsTheSame(o: Task, n: Task) = o.id == n.id
        override fun areContentsTheSame(o: Task, n: Task) = o == n
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName:      TextView  = v.findViewById(R.id.tvTaskName)
        val tvDur:       TextView  = v.findViewById(R.id.tvDuration)
        val tvElapsed:   TextView  = v.findViewById(R.id.tvElapsed)
        val vProgress:   View      = v.findViewById(R.id.vTaskProgress)
        val flProgressContainer: View = v.findViewById(R.id.flProgressContainer)
        val vAccent:     View      = v.findViewById(R.id.vAccentBar)
        val vDot:        View      = v.findViewById(R.id.vPulseDot)
        val btnEdit:     View      = v.findViewById(R.id.btnEdit)
        val btnDel:      View      = v.findViewById(R.id.btnDelete)
        val btnPlay:     FrameLayout = v.findViewById(R.id.btnPlayPause)
        val ivPlayIcon:  ImageView = v.findViewById(R.id.ivPlayPauseIcon)
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_task_card, p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val task     = getItem(pos)
        val running  = isRunning(task.id)
        val elapsed  = elapsedMs(task.id)
        val budgetMs = task.durationMinutes * 60_000L

        safeColor(task.colorHex)?.let { h.vAccent.setBackgroundColor(it) }
        h.vDot.visibility = if (running) View.VISIBLE else View.GONE
        h.tvName.text = task.name
        h.tvDur.text  = "⏱ ${fmtMs(budgetMs)}"

        if (elapsed > 0 || running) {
            h.tvElapsed.visibility = View.VISIBLE
            h.tvElapsed.text = "· ${fmtElapsed(elapsed)}"
        } else {
            h.tvElapsed.visibility = View.GONE
        }

        // Progress strip
        if (elapsed > 0 || running) {
            h.flProgressContainer.visibility = View.VISIBLE
            val pct = (elapsed.toFloat() / budgetMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            h.vProgress.post {
                val parentW = (h.vProgress.parent as? ViewGroup)?.width ?: 0
                h.vProgress.layoutParams.width = (parentW * pct).toInt()
                h.vProgress.requestLayout()
            }
            safeColor(task.colorHex)?.let { h.vProgress.setBackgroundColor(it) }
        } else {
            h.flProgressContainer.visibility = View.GONE
        }

        h.ivPlayIcon.setImageResource(if (running) R.drawable.ic_pause else R.drawable.ic_play)
        h.btnPlay.setOnClickListener { onPlay(task) }
        h.btnEdit.setOnClickListener  { onEdit(task) }
        h.btnDel.setOnClickListener   { onDelete(task) }
    }

    private fun safeColor(hex: String): Int? = try { Color.parseColor(hex) } catch (_: Exception) { null }

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60
        return when { h > 0 && m > 0 -> "${h}h ${m}m"; h > 0 -> "${h}h"; else -> "${m}m" }
    }
    private fun fmtElapsed(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }
}
