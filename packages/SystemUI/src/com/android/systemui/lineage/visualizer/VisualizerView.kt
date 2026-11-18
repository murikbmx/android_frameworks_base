/*
 * SPDX-FileCopyrightText: 2015 The CyanogenMod Project
 * SPDX-FileCopyrightText: 2017-2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.lineage.visualizer

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.log10

/**
 * Bars of the music spectrum drawn along the bottom of the lockscreen. The data and the state come
 * from [VisualizerController].
 */
class VisualizerView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) :
    View(context, attrs, defStyle) {

    private val paint =
        Paint().apply {
            isAntiAlias = true
            color = Color.TRANSPARENT
        }

    /** Line segments for [Canvas.drawLines]: x0, y0 (top of the bar), x1, y1 (bottom). */
    private val points = FloatArray(BARS * 4)
    private val animators = Array(BARS) { ValueAnimator() }
    private var colorAnimator: ObjectAnimator? = null
    private var color = Color.TRANSPARENT

    /** Height of one dB of a bar, 16px at xxhdpi like the original CyanogenMod visualizer. */
    private val dbHeight = resources.displayMetrics.density * 16f / 3f

    /** Whether the bars are drawn, between [start] and [stop]. */
    var isRunning = false
        private set

    init {
        alpha = 0f
        for (i in 0 until BARS) {
            val top = i * 4 + 1
            animators[i].duration = BAR_ANIMATION_MS
            animators[i].addUpdateListener {
                points[top] = it.animatedValue as Float
                invalidate()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        var barUnit = w / BARS.toFloat()
        val barWidth = barUnit * 8f / 9f
        barUnit = barWidth + (barUnit - barWidth) * BARS / (BARS - 1f)
        paint.strokeWidth = barWidth
        for (i in 0 until BARS) {
            animators[i].cancel()
            points[i * 4] = i * barUnit + barWidth / 2
            points[i * 4 + 2] = points[i * 4]
            points[i * 4 + 1] = h.toFloat()
            points[i * 4 + 3] = h.toFloat()
        }
    }

    override fun hasOverlappingRendering() = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isRunning) canvas.drawLines(points, paint)
    }

    /** New FFT data from the visualizer, as returned by Visualizer.getFft(). */
    fun onFftData(fft: ByteArray) {
        if (!isRunning || height == 0) return
        val bottom = points[3]
        for (i in 0 until BARS) {
            // Bin 0 holds the DC and Nyquist parts, the bars start at bin 1
            val index = i * 2 + 2
            if (index + 1 >= fft.size) break
            val re = fft[index].toFloat()
            val im = fft[index + 1].toFloat()
            val magnitude = re * re + im * im
            val db = if (magnitude > 0f) (10 * log10(magnitude)).toInt() else 0
            animators[i].cancel()
            animators[i].setFloatValues(points[i * 4 + 1], bottom - db * dbHeight)
            animators[i].start()
        }
    }

    /** Fades the bars in. */
    fun start() {
        // Also turns a fade out around, which then never calls its end action
        isRunning = true
        animate().cancel()
        animate().alpha(1f).withEndAction(null).setDuration(FADE_IN_MS)
    }

    /** Fades the bars out, then calls [endAction]. */
    fun stop(animate: Boolean, endAction: Runnable) {
        if (!isRunning) {
            endAction.run()
            return
        }
        animate().cancel()
        val finish = Runnable {
            isRunning = false
            for (i in 0 until BARS) {
                animators[i].cancel()
                points[i * 4 + 1] = points[i * 4 + 3]
            }
            invalidate()
            endAction.run()
        }
        if (animate) {
            animate().alpha(0f).withEndAction(finish).setDuration(FADE_OUT_MS)
        } else {
            // Right away: with the screen off there may be no frame to run an animation
            alpha = 0f
            finish.run()
        }
    }

    /** Color of the bars, made translucent. */
    fun setColor(newColor: Int) {
        val c = Color.argb(BAR_ALPHA, Color.red(newColor), Color.green(newColor), Color.blue(newColor))
        if (color == c) return
        color = c
        colorAnimator?.cancel()
        if (isRunning) {
            colorAnimator =
                ObjectAnimator.ofArgb(paint, "color", paint.color, c).apply {
                    startDelay = COLOR_DELAY_MS
                    duration = COLOR_DURATION_MS
                    addUpdateListener { invalidate() }
                    start()
                }
        } else {
            paint.color = c
        }
    }

    companion object {
        const val BARS = 32
        private const val BAR_ALPHA = 140
        private const val BAR_ANIMATION_MS = 128L
        private const val FADE_IN_MS = 800L
        private const val FADE_OUT_MS = 600L
        private const val COLOR_DELAY_MS = 600L
        private const val COLOR_DURATION_MS = 1200L
    }
}
