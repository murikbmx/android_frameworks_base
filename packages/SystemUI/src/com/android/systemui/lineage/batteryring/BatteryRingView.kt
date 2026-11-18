/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.lineage.batteryring

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Ring drawn around the front camera cutout showing the battery level, with a light dot running
 * along it while charging.
 */
class BatteryRingView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    /** Radius of the middle of the ring, in px. */
    var ringRadius = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val strokeWidth = 3f * density
    private val haloWidth = 1.5f * density

    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
    private val haloPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cometPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val oval = RectF()

    private var ringColor = 0
    private var haloColor = 0

    /** Drawn part of the ring, 0..1 from 12 o'clock clockwise. */
    private var fill = 0f
    private var ringAlpha = 0f
    private var widthBoost = 0f

    /** Position of the running dot along the drawn arc (0..1), hidden if negative. */
    private var cometPos = -1f
    private var cometAlpha = 0f

    private var animator: ValueAnimator? = null

    /** True while the ring is shown with a looping animation (lockscreen while charging). */
    var isPersistent = false
        private set

    /** True while an animation runs or the ring is shown. */
    val isActive: Boolean
        get() = animator != null || ringAlpha > 0f

    var dashed = false
        set(value) {
            field = value
            updateDash()
            invalidate()
        }

    fun setColors(ring: Int, halo: Int) {
        ringColor = ring
        haloColor = halo
        invalidate()
    }

    /** Level the persistent ring moves to. */
    private var targetLevel = 0f

    /** Updates the level shown by the persistent ring, which moves to it smoothly. */
    fun updateLevel(level: Float) {
        if (isPersistent) targetLevel = level.coerceIn(0f, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateDash()
    }

    private fun updateDash() {
        ringPaint.pathEffect =
            if (dashed && ringRadius > 0f) {
                // Short dashes evenly spaced on the circle
                val circumference = (2 * PI * ringRadius).toFloat()
                val count = 24
                val step = circumference / count
                DashPathEffect(floatArrayOf(step * 0.55f, step * 0.45f), 0f)
            } else {
                null
            }
    }

    override fun onDraw(canvas: Canvas) {
        if (ringAlpha <= 0f || ringRadius <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        oval.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
        val stroke = strokeWidth * (1f + widthBoost)

        // Faint full circle under the level
        trackPaint.strokeWidth = stroke
        trackPaint.color = ringColor
        trackPaint.alpha = (255 * 0.18f * ringAlpha).toInt()
        canvas.drawCircle(cx, cy, ringRadius, trackPaint)

        val sweep = 360f * fill
        if (sweep > 0.5f) {
            // Contrasting outline keeps the ring visible on any background
            haloPaint.strokeWidth = stroke + haloWidth * 2
            haloPaint.color = haloColor
            haloPaint.alpha = (Color.alpha(haloColor) * ringAlpha).toInt()
            canvas.drawArc(oval, -90f, sweep, false, haloPaint)
            ringPaint.strokeWidth = stroke
            ringPaint.color = ringColor
            ringPaint.alpha = (255 * ringAlpha).toInt()
            canvas.drawArc(oval, -90f, sweep, false, ringPaint)
        }

        if (cometPos >= 0f && cometAlpha > 0f && fill > 0f) {
            val angle = 2 * PI * fill * cometPos - PI / 2
            val x = cx + ringRadius * cos(angle).toFloat()
            val y = cy + ringRadius * sin(angle).toFloat()
            cometPaint.color = haloColor
            cometPaint.alpha = (Color.alpha(haloColor) * cometAlpha * ringAlpha).toInt()
            canvas.drawCircle(x, y, stroke * 0.9f + haloWidth, cometPaint)
            cometPaint.color = Color.WHITE
            cometPaint.alpha = (255 * cometAlpha * ringAlpha).toInt()
            canvas.drawCircle(x, y, stroke * 0.9f, cometPaint)
        }
    }

    /**
     * Shows the ring for a while: it fills up to [level] (from empty if [fromEmpty]), then the dot
     * runs [sweeps] times along it and the ring fades out after [holdSec].
     */
    fun showCharge(level: Float, fromEmpty: Boolean, fast: Boolean, sweeps: Int, holdSec: Float) {
        isPersistent = false
        isStatic = false
        val to = level.coerceIn(0f, 1f)
        val fillDur = if (fromEmpty) 0.8f else 0f
        val period = if (fast) 0.5f else 1f
        val sweepEnd = fillDur + sweeps * period
        val fadeStart = max(sweepEnd, holdSec)
        val startAlpha = ringAlpha
        timeline(fadeStart + 0.6f, onEnd = ::reset) { t ->
            ringAlpha =
                when {
                    t >= fadeStart -> 1f - (t - fadeStart) / 0.6f
                    fromEmpty -> 1f
                    else -> min(1f, startAlpha + t / 0.2f)
                }
            fill = if (fillDur > 0f) to * ease(t / fillDur) else to
            if (t >= fillDur && t < sweepEnd) {
                cometPos = ((t - fillDur) / period) % 1f
                cometAlpha = 1f
            } else {
                cometPos = -1f
            }
        }
    }

    /**
     * Keeps the ring shown at [level] with the dot running along it from time to time, until
     * [hide]. With [charged] the ring is full and still.
     */
    fun showPersistent(level: Float, fromEmpty: Boolean, fast: Boolean, charged: Boolean) {
        targetLevel = if (charged) 1f else level.coerceIn(0f, 1f)
        val wasPersistent = isPersistent
        isPersistent = true
        isStatic = false
        val startFill = if (fromEmpty) 0f else if (wasPersistent) fill else targetLevel
        val startAlpha = ringAlpha
        val intro = if (fromEmpty) 0.8f else 0.3f
        val run = if (fast) 0.6f else 1.2f
        val period = if (fast) 2.5f else 4f
        timeline(intro, onEnd = {
            timeline(period, repeat = true) { t ->
                ringAlpha = 1f
                // Follow level changes
                fill =
                    if (abs(targetLevel - fill) < 0.001f) targetLevel
                    else fill + (targetLevel - fill) * 0.08f
                if (!charged && t < run) {
                    cometPos = t / run
                    cometAlpha = sin(PI * t / run).toFloat()
                } else {
                    cometPos = -1f
                }
            }
        }) { t ->
            ringAlpha = min(1f, startAlpha + t / intro)
            fill = startFill + (targetLevel - startFill) * ease(t / intro)
            cometPos = -1f
        }
    }

    /** The ring closes and flashes once, then fades out unless [persistAfter]. */
    fun showFull(persistAfter: Boolean) {
        val startFill = fill
        val startAlpha = ringAlpha
        isPersistent = false
        isStatic = false
        timeline(if (persistAfter) 1.6f else 3.6f, onEnd = {
            if (persistAfter) showPersistent(1f, fromEmpty = false, fast = false, charged = true)
            else reset()
        }) { t ->
            ringAlpha = if (t >= 3f) 1f - (t - 3f) / 0.6f else min(1f, startAlpha + t / 0.2f)
            fill = startFill + (1f - startFill) * ease(t / 0.7f)
            widthBoost = if (t > 0.7f && t < 1.5f) sin((t - 0.7f) / 0.8f * PI).toFloat() else 0f
            cometPos = -1f
        }
    }

    /** Pulses the ring twice at [level], then fades out. */
    fun pulseLow(level: Float) {
        isPersistent = false
        isStatic = false
        val to = level.coerceIn(0.02f, 1f)
        timeline(3f, onEnd = ::reset) { t ->
            fill = to
            cometPos = -1f
            ringAlpha =
                if (t < 2.4f) 0.35f + 0.65f * abs(sin(t / 1.2f * PI)).toFloat()
                else max(0f, 1f - (t - 2.4f) / 0.5f)
        }
    }

    /**
     * Shows the ring still at [level] with [alpha], without any animation running: for the
     * always-on display, where the screen barely updates.
     */
    fun showStatic(level: Float, alpha: Float) {
        animator?.cancel()
        animator = null
        isPersistent = false
        isStatic = true
        fill = level.coerceIn(0.02f, 1f)
        ringAlpha = alpha
        widthBoost = 0f
        cometPos = -1f
        invalidate()
    }

    /** True while the ring is shown by [showStatic]. */
    var isStatic = false
        private set

    /** Fades the ring out, or removes it at once if not [animate]. */
    fun hide(animate: Boolean = true) {
        isPersistent = false
        isStatic = false
        if (!animate || ringAlpha <= 0f) {
            animator?.cancel()
            reset()
            return
        }
        val startAlpha = ringAlpha
        timeline(0.3f, onEnd = ::reset) { t ->
            ringAlpha = startAlpha * (1f - t / 0.3f)
            cometPos = -1f
        }
    }

    private fun reset() {
        animator = null
        isPersistent = false
        isStatic = false
        ringAlpha = 0f
        fill = 0f
        widthBoost = 0f
        cometPos = -1f
        invalidate()
    }

    private fun timeline(
        durationSec: Float,
        repeat: Boolean = false,
        onEnd: () -> Unit = {},
        frame: (Float) -> Unit,
    ) {
        animator?.cancel()
        animator =
            ValueAnimator.ofFloat(0f, durationSec).apply {
                duration = (durationSec * 1000).toLong()
                interpolator = LinearInterpolator()
                if (repeat) repeatCount = ValueAnimator.INFINITE
                // The ring shows information: keep it when animations are turned off
                overrideDurationScale(1f)
                addUpdateListener {
                    val before = drawState()
                    frame(it.animatedValue as Float)
                    if (drawState() != before) invalidate()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        private var cancelled = false

                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            if (!cancelled && animator === animation) onEnd()
                        }
                    }
                )
                start()
            }
    }

    /** What onDraw depends on, to skip redrawing still frames of the looping animations. */
    private fun drawState() =
        listOf(
            (fill * 1000).toInt(),
            (ringAlpha * 255).toInt(),
            (widthBoost * 100).toInt(),
            if (cometPos < 0f) -1 else (cometPos * 1000).toInt(),
            (cometAlpha * 255).toInt(),
        )

    private fun ease(x: Float): Float = 1f - (1f - x.coerceIn(0f, 1f)).pow(3)
}
