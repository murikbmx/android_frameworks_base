/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.lineage.sidebar

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.android.systemui.res.R

/** An app of the sidebar or of the app picker. */
data class SidebarApp(val component: ComponentName, val label: CharSequence, val icon: Drawable)

/**
 * Full-screen overlay holding the sidebar panel (and the app picker). Tapping outside the panel
 * or pressing back closes it.
 */
@SuppressLint("ViewConstructor")
class SidebarPanelView(
    context: Context,
    private val callbacks: Callbacks,
) : FrameLayout(context) {

    interface Callbacks {
        fun onLaunch(app: SidebarApp)

        fun onAppsChanged(apps: List<ComponentName>)

        /** Asks for all launchable apps; [onLoaded] runs on the main thread. */
        fun loadAllApps(onLoaded: (List<SidebarApp>) -> Unit)

        fun onClosed()
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Number) = (v.toFloat() * density).toInt()

    private val itemSize = dp(52)
    private val itemGap = dp(10)
    private val panelPadding = dp(10)
    private val panelRadius = dp(26).toFloat()

    private var apps: List<SidebarApp> = emptyList()
    private var handleCenterY = 0
    private var closing = false

    private val list =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

    private val scroll =
        ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(14))
            addView(list)
        }

    private val addButton =
        ImageView(context).apply {
            setImageDrawable(PlusDrawable(Color.WHITE, density))
            scaleType = ImageView.ScaleType.CENTER
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.argb(36, 255, 255, 255))
                }
            contentDescription = context.getString(R.string.sidebar_add_app)
            setOnClickListener { showPicker() }
        }

    private val panel =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(panelPadding, panelPadding, panelPadding, panelPadding)
            background = solidBackground()
            elevation = dp(8).toFloat()
            addView(scroll, LinearLayout.LayoutParams(itemSize, 0))
            addView(
                addButton,
                LinearLayout.LayoutParams(itemSize, itemSize).apply { topMargin = itemGap },
            )
            isClickable = true
        }

    private var picker: View? = null

    init {
        setBackgroundColor(Color.argb(46, 0, 0, 0))
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { close() }
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        applyBlur(panel)
    }

    private fun solidBackground() =
        GradientDrawable().apply {
            cornerRadius = panelRadius
            setColor(Color.argb(205, 38, 44, 47))
        }

    private fun applyBlur(view: View) {
        val root = view.viewRootImpl ?: return
        runCatching {
            view.background =
                root.createBackgroundBlurDrawable().apply {
                    setColor(Color.argb(170, 38, 44, 47))
                    setBlurRadius(dp(30))
                    setCornerRadius(panelRadius)
                }
        }
    }

    /** Shows the panel next to the handle whose centre is at [centerY]. */
    fun show(apps: List<SidebarApp>, centerY: Int) {
        handleCenterY = centerY
        setApps(apps)
        alpha = 0f
        panel.translationX = -dp(100).toFloat()
        animate().alpha(1f).setDuration(180).start()
        panel.animate()
            .translationX(0f)
            .setInterpolator(DecelerateInterpolator(2.2f))
            .setDuration(280)
            .start()
    }

    private fun setApps(newApps: List<SidebarApp>) {
        apps = newApps
        list.removeAllViews()
        apps.forEachIndexed { index, app ->
            list.addView(
                appIcon(app, index),
                LinearLayout.LayoutParams(itemSize, itemSize).apply {
                    if (index > 0) topMargin = itemGap
                },
            )
        }
        addButton.visibility = if (apps.size < SidebarSettings.MAX_APPS) VISIBLE else GONE
        val visible = apps.size.coerceAtMost(SidebarSettings.VISIBLE_APPS)
        val scrollHeight = if (visible == 0) 0 else visible * itemSize + (visible - 1) * itemGap
        (scroll.layoutParams as LinearLayout.LayoutParams).height = scrollHeight
        (addButton.layoutParams as LinearLayout.LayoutParams).topMargin =
            if (visible == 0) 0 else itemGap
        scroll.requestLayout()
        positionPanel(scrollHeight)
    }

    private fun positionPanel(scrollHeight: Int) {
        val addHeight = if (addButton.visibility == VISIBLE) itemSize + itemGap else 0
        val panelHeight = scrollHeight + addHeight + panelPadding * 2
        val screenHeight = resources.displayMetrics.heightPixels
        val margin = dp(48)
        val top =
            (handleCenterY - panelHeight / 2).coerceIn(
                margin,
                (screenHeight - margin - panelHeight).coerceAtLeast(margin),
            )
        (panel.layoutParams as LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(10)
            topMargin = top
        }
        panel.requestLayout()
    }

    private fun appIcon(app: SidebarApp, index: Int): View =
        ImageView(context).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = app.label
            outlineProvider = RoundOutline(dp(16).toFloat())
            clipToOutline = true
            setOnClickListener {
                callbacks.onLaunch(app)
                close()
            }
            setOnLongClickListener { anchor ->
                showItemMenu(anchor, index)
                true
            }
        }

    private fun showItemMenu(anchor: View, index: Int) {
        val themed = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_DayNight)
        PopupMenu(themed, anchor, Gravity.END).apply {
            menu.add(0, MENU_REMOVE, 0, R.string.sidebar_remove_app)
            if (index > 0) menu.add(0, MENU_UP, 1, R.string.sidebar_move_up)
            if (index < apps.size - 1) menu.add(0, MENU_DOWN, 2, R.string.sidebar_move_down)
            setOnMenuItemClickListener { item ->
                val updated = apps.toMutableList()
                when (item.itemId) {
                    MENU_REMOVE -> updated.removeAt(index)
                    MENU_UP -> updated.add(index - 1, updated.removeAt(index))
                    MENU_DOWN -> updated.add(index + 1, updated.removeAt(index))
                }
                setApps(updated)
                callbacks.onAppsChanged(updated.map { it.component })
                true
            }
            show()
        }
    }

    private fun showPicker() {
        if (picker != null) return
        val title =
            TextView(context).apply {
                text = context.getString(R.string.sidebar_add_app)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding(dp(24), dp(20), dp(24), dp(12))
            }
        val grid =
            GridLayout(context).apply {
                columnCount = 4
                setPadding(dp(12), 0, dp(12), dp(16))
            }
        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = solidBackground()
                elevation = dp(12).toFloat()
                isClickable = true
                addView(title)
                addView(
                    ScrollView(context).apply {
                        isVerticalScrollBarEnabled = false
                        addView(grid)
                    },
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
                )
            }
        val screenHeight = resources.displayMetrics.heightPixels
        addView(
            card,
            LayoutParams(LayoutParams.MATCH_PARENT, (screenHeight * 0.7f).toInt()).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(16)
                rightMargin = dp(16)
            },
        )
        picker = card
        panel.animate().alpha(0f).setDuration(150).start()
        card.alpha = 0f
        card.scaleX = 0.94f
        card.scaleY = 0.94f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        applyBlur(card)

        callbacks.loadAllApps { all ->
            val present = apps.map { it.component }.toSet()
            val cellWidth = (resources.displayMetrics.widthPixels - dp(32) - dp(24)) / 4
            all.filter { it.component !in present }
                .forEach { app ->
                    val cell =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_HORIZONTAL
                            setPadding(dp(4), dp(10), dp(4), dp(10))
                            addView(
                                ImageView(context).apply { setImageDrawable(app.icon) },
                                LinearLayout.LayoutParams(dp(48), dp(48)),
                            )
                            addView(
                                TextView(context).apply {
                                    text = app.label
                                    setTextColor(Color.WHITE)
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                    maxLines = 1
                                    ellipsize = TextUtils.TruncateAt.END
                                    gravity = Gravity.CENTER
                                    setPadding(0, dp(6), 0, 0)
                                }
                            )
                            setOnClickListener {
                                val updated = (apps + app).take(SidebarSettings.MAX_APPS)
                                callbacks.onAppsChanged(updated.map { it.component })
                                hidePicker()
                                setApps(updated)
                            }
                        }
                    grid.addView(cell, GridLayout.LayoutParams().apply { width = cellWidth })
                }
        }
    }

    private fun hidePicker() {
        val card = picker ?: return
        picker = null
        removeView(card)
        panel.animate().alpha(1f).setDuration(150).start()
    }

    fun close() {
        if (closing) return
        if (picker != null) {
            hidePicker()
            return
        }
        closing = true
        panel.animate()
            .translationX(-(panel.width + dp(20)).toFloat())
            .setInterpolator(DecelerateInterpolator())
            .setDuration(200)
            .start()
        animate().alpha(0f).setDuration(200).withEndAction { callbacks.onClosed() }.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) close()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private class RoundOutline(private val radius: Float) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    /** A "+" drawn with two rounded strokes. */
    private class PlusDrawable(color: Int, density: Float) : Drawable() {
        private val size = (24 * density).toInt()
        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeWidth = 2.4f * density
                strokeCap = Paint.Cap.ROUND
            }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val half = size * 0.3f
            canvas.drawLine(cx - half, cy, cx + half, cy, paint)
            canvas.drawLine(cx, cy - half, cx, cy + half, paint)
        }

        override fun getIntrinsicWidth() = size

        override fun getIntrinsicHeight() = size

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val MENU_REMOVE = 1
        const val MENU_UP = 2
        const val MENU_DOWN = 3
    }
}

