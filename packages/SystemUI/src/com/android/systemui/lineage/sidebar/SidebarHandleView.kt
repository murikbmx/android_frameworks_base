/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.lineage.sidebar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Thin semi-transparent handle on the left edge. Swiping right (or tapping) opens the panel,
 * dragging vertically moves the handle. Its rect is excluded from the back gesture so the rest
 * of the edge keeps working as "back".
 */
@SuppressLint("ViewConstructor")
class SidebarHandleView(
    context: Context,
    private val callbacks: Callbacks,
) : View(context) {

    interface Callbacks {
        fun onOpen()

        /** Vertical drag of the handle by [dy] pixels since the gesture started. */
        fun onMove(dy: Float)

        fun onMoveFinished()
    }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val openDistance = 20 * density

    private val pillWidth = 5 * density
    private val pillHeight = 84 * density
    private val fill =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
    private val outline =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = Color.argb(40, 0, 0, 0)
        }
    private val pill = RectF()

    private var downX = 0f
    private var downY = 0f
    private var mode = Mode.NONE

    private enum class Mode {
        NONE,
        MOVE,
        OPENED,
    }

    var dimmed = false
        set(value) {
            field = value
            alpha = if (value) 0.5f else 1f
        }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
    }

    override fun onDraw(canvas: Canvas) {
        val top = (height - pillHeight) / 2f
        pill.set(0f, top, pillWidth, top + pillHeight)
        val r = pillWidth / 2f
        canvas.drawRoundRect(pill, r, r, fill)
        canvas.drawRoundRect(pill, r, r, outline)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                mode = Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                when (mode) {
                    Mode.NONE ->
                        if (dx > openDistance && dx > abs(dy)) {
                            mode = Mode.OPENED
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
                            callbacks.onOpen()
                        } else if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                            mode = Mode.MOVE
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                    Mode.MOVE -> callbacks.onMove(dy)
                    Mode.OPENED -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.MOVE -> callbacks.onMoveFinished()
                    Mode.NONE -> {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        callbacks.onOpen()
                    }
                    Mode.OPENED -> Unit
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.MOVE) callbacks.onMoveFinished()
                mode = Mode.NONE
            }
        }
        return true
    }
}
