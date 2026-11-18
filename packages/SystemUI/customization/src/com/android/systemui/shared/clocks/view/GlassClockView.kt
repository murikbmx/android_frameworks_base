/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shared.clocks.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import com.android.systemui.customization.R
import com.android.systemui.plugins.keyguard.VPointF
import com.android.systemui.plugins.keyguard.VRectF
import kotlin.math.min

/**
 * The time as one row of very tall, narrow digits that look like frosted glass: a translucent fill
 * tinted from the wallpaper color, a light edge and a soft shadow. Outline only while dozing.
 */
class GlassClockView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private val typeface: Typeface =
        runCatching { resources.getFont(R.font.lineage_glass_clock) }.getOrDefault(Typeface.DEFAULT)

    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@GlassClockView.typeface
            textAlign = Paint.Align.LEFT
        }
    private val edgePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@GlassClockView.typeface
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
    private val bounds = Rect()

    /** The digits drawn, e.g. "0829". */
    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    /** Color the glass is tinted with. */
    var tint: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    /** Color of the outline on the always-on display. */
    var aodColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    /** Fill the whole view, stretching the digits (the small clock under the date). */
    var stretchToFill = false
        set(value) {
            field = value
            invalidate()
        }

    /** Draw the digits at the top of the view, right under the date, not centered. */
    var alignTop = false
        set(value) {
            field = value
            invalidate()
        }

    var dozeFraction: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var onViewBoundsChanged: ((VRectF) -> Unit)? = null
    var onViewMaxSizeChanged: ((VPointF) -> Unit)? = null

    init {
        // The shadow needs a software layer to blur
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** Width over height of the digits as drawn (stretched vertically). */
    private fun aspect(): Float {
        fillPaint.textSize = REFERENCE_SIZE
        val sample = text.ifEmpty { "0000" }
        fillPaint.getTextBounds(sample, 0, sample.length, bounds)
        return bounds.width() / (bounds.height() * STRETCH_Y)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val aspect = aspect()
        var w = width
        var h = (width / aspect).toInt()
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && h > height) {
            h = height
            w = (height * aspect).toInt()
        }
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) w = width
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) h = height
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            onViewBoundsChanged?.invoke(
                VRectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            )
            onViewMaxSizeChanged?.invoke(VPointF(width.toFloat(), height.toFloat()))
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty() || width == 0 || height == 0) return

        // Fit the stretched digits in the view
        fillPaint.textSize = REFERENCE_SIZE
        fillPaint.getTextBounds(text, 0, text.length, bounds)
        val textW = bounds.width().toFloat()
        val textH = bounds.height() * STRETCH_Y
        val scale = if (stretchToFill) height / textH else min(width / textW, height / textH)
        val size = REFERENCE_SIZE * scale
        fillPaint.textSize = size
        edgePaint.textSize = size
        fillPaint.getTextBounds(text, 0, text.length, bounds)
        val stretchX = if (stretchToFill) width / bounds.width().toFloat() else 1f
        val drawnW = bounds.width().toFloat()
        val drawnH = bounds.height() * STRETCH_Y
        val x = if (stretchToFill) -bounds.left.toFloat() else (width - drawnW) / 2f - bounds.left
        val y = if (alignTop) 0f else (height - drawnH) / 2f

        canvas.save()
        canvas.translate(0f, y)
        canvas.scale(stretchX, STRETCH_Y)
        val baseline = -bounds.top.toFloat()

        if (dozeFraction < 1f) {
            val alpha = 1f - dozeFraction
            // Lighter at the top left, deeper at the bottom right, see-through like glass
            val light = blend(tint, Color.WHITE, 0.40f)
            val deep = blend(tint, Color.WHITE, 0.05f)
            fillPaint.shader =
                LinearGradient(
                    x,
                    0f,
                    x + drawnW,
                    bounds.height().toFloat(),
                    withAlpha(light, (FILL_ALPHA * alpha).toInt()),
                    withAlpha(deep, (FILL_ALPHA * 0.85f * alpha).toInt()),
                    Shader.TileMode.CLAMP,
                )
            fillPaint.setShadowLayer(
                10f * density,
                0f,
                4f * density / STRETCH_Y,
                Color.argb((70 * alpha).toInt(), 0, 0, 0),
            )
            canvas.drawText(text, x, baseline, fillPaint)
            fillPaint.clearShadowLayer()

            edgePaint.strokeWidth = 1.2f * density
            edgePaint.color = Color.argb((120 * alpha).toInt(), 255, 255, 255)
            canvas.drawText(text, x, baseline, edgePaint)
        }
        if (dozeFraction > 0f) {
            // Thin outline on the always-on display: few lit pixels
            edgePaint.strokeWidth = 1.5f * density
            edgePaint.color = withAlpha(aodColor, (255 * dozeFraction).toInt())
            canvas.drawText(text, x, baseline, edgePaint)
        }
        canvas.restore()
    }

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val inv = 1f - ratio
        return Color.argb(
            (Color.alpha(from) * inv + Color.alpha(to) * ratio).toInt(),
            (Color.red(from) * inv + Color.red(to) * ratio).toInt(),
            (Color.green(from) * inv + Color.green(to) * ratio).toInt(),
            (Color.blue(from) * inv + Color.blue(to) * ratio).toInt(),
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00ffffff) or (alpha.coerceIn(0, 255) shl 24)

    companion object {
        private const val REFERENCE_SIZE = 200f

        /** The font is stretched vertically to get the tall glass digits. */
        private const val STRETCH_Y = 1.45f
        private const val FILL_ALPHA = 215
    }
}
