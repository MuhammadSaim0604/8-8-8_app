package com.dayblocks.app.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

class CircularProgressView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var trackColor: Int = Color.parseColor("#1E1E2E")
        set(value) { field = value; invalidate() }

    var progressColor: Int = Color.parseColor("#3B82F6")
        set(value) { field = value; invalidate() }

    var strokeWidth: Float = 12f
        set(value) { field = value; invalidate() }

    var showGlow: Boolean = true
        set(value) { field = value; invalidate() }

    private val trackPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval        = RectF()
    private var animatedProgress = 0f

    init {
        trackPaint.style = Paint.Style.STROKE
        trackPaint.strokeCap = Paint.Cap.ROUND

        progressPaint.style = Paint.Style.STROKE
        progressPaint.strokeCap = Paint.Cap.ROUND

        glowPaint.style = Paint.Style.STROKE
        glowPaint.strokeCap = Paint.Cap.ROUND
    }

    fun animateTo(target: Float, durationMs: Long = 800) {
        ValueAnimator.ofFloat(animatedProgress, target.coerceIn(0f, 1f)).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animatedProgress = it.animatedValue as Float
                progress = animatedProgress
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - strokeWidth / 2f - 4f

        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Track
        trackPaint.color = trackColor
        trackPaint.strokeWidth = strokeWidth
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)

        if (progress <= 0f) return

        // Glow
        if (showGlow && progress > 0f) {
            glowPaint.color = progressColor
            glowPaint.strokeWidth = strokeWidth + 8f
            glowPaint.maskFilter = BlurMaskFilter(strokeWidth, BlurMaskFilter.Blur.NORMAL)
            glowPaint.alpha = 60
            val sweep = 360f * progress
            canvas.drawArc(oval, -90f, sweep, false, glowPaint)
        }

        // Progress arc
        progressPaint.color = progressColor
        progressPaint.strokeWidth = strokeWidth
        progressPaint.maskFilter = null
        val sweep = 360f * progress
        canvas.drawArc(oval, -90f, sweep, false, progressPaint)
    }
}
