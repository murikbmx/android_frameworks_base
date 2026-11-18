/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shared.clocks.controller

import android.content.Context
import android.content.res.Resources
import android.icu.util.TimeZone
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.customization.R
import com.android.systemui.customization.clocks.utils.ViewUtils.computeLayoutDiff
import com.android.systemui.customization.clocks.utils.ViewUtils.translation
import com.android.systemui.customization.clocks.view.DefaultClockFaceLayout
import com.android.systemui.plugins.keyguard.VRect
import com.android.systemui.plugins.keyguard.data.model.AlarmData
import com.android.systemui.plugins.keyguard.data.model.WeatherData
import com.android.systemui.plugins.keyguard.data.model.ZenData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAnimations
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAxisStyle
import com.android.systemui.plugins.keyguard.ui.clocks.ClockConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockEventListeners
import com.android.systemui.plugins.keyguard.ui.clocks.ClockEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceLayout
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPositionAnimationArgs
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPreviewConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockSettings
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import com.android.systemui.plugins.keyguard.ui.clocks.TimeFormatKind
import com.android.systemui.shared.clocks.GLASS_CLOCK_ID
import com.android.systemui.shared.clocks.view.GlassClockView
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil

/** The glass clock: one row of tall frosted glass digits, on the lockscreen and when small. */
class GlassClockController(
    private val ctx: Context,
    private val resources: Resources,
    private val settings: ClockSettings,
) : ClockController {

    override val smallClock = GlassClockFaceController(isLargeClock = false)
    override val largeClock = GlassClockFaceController(isLargeClock = true)

    override val config =
        ClockConfig(
            GLASS_CLOCK_ID,
            resources.getString(R.string.clock_lineage_glass_name),
            resources.getString(R.string.clock_lineage_glass_description),
        )

    override val events =
        object : ClockEvents {
            override fun onTimeZoneChanged(timeZone: TimeZone) {
                smallClock.events.onTimeZoneChanged(timeZone)
                largeClock.events.onTimeZoneChanged(timeZone)
            }

            override fun onTimeFormatChanged(formatKind: TimeFormatKind) {
                smallClock.events.onTimeFormatChanged(formatKind)
                largeClock.events.onTimeFormatChanged(formatKind)
            }

            override fun onLocaleChanged(locale: Locale) {
                smallClock.events.onLocaleChanged(locale)
                largeClock.events.onLocaleChanged(locale)
            }

            override fun onWeatherDataChanged(data: WeatherData) {}

            override fun onAlarmDataChanged(data: AlarmData) {}

            override fun onZenDataChanged(data: ZenData) {}
        }

    override val eventListeners = ClockEventListeners()

    override fun initialize(isDarkTheme: Boolean, dozeFraction: Float, foldFraction: Float) {
        for (face in listOf(smallClock, largeClock)) {
            face.clockView.onViewBoundsChanged = { bounds ->
                eventListeners.fire { onBoundsChanged(bounds, face.isLargeClock) }
            }
            face.clockView.onViewMaxSizeChanged = { size ->
                eventListeners.fire { onMaxSizeChanged(size, face.isLargeClock) }
            }
            face.events.onThemeChanged(face.theme.copy(isDarkTheme = isDarkTheme))
            face.animations.doze(dozeFraction)
            face.events.onTimeTick()
        }
    }

    override fun dump(pw: PrintWriter) {
        pw.println("GlassClockController: small=${smallClock.clockView.text}")
    }

    inner class GlassClockFaceController(val isLargeClock: Boolean) : ClockFaceController {
        val clockView =
            GlassClockView(ctx).apply {
                stretchToFill = !isLargeClock
                alignTop = isLargeClock
                layoutParams =
                    FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                        gravity = Gravity.CENTER
                    }
                id =
                    if (isLargeClock) ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
                    else ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
            }

        override val view: View
            get() = clockView

        override val layout: ClockFaceLayout = GlassClockFaceLayout(clockView)

        override val config = ClockFaceConfig()

        override var theme = ThemeConfig(true, settings.seedColor)

        private var timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()
        private var is24Hour = TimeFormatKind.getFromContext(ctx) == TimeFormatKind.FULL_DAY
        private var locale: Locale = Locale.getDefault()

        private fun updateTime() {
            val format = SimpleDateFormat(if (is24Hour) "HHmm" else "hhmm", locale)
            format.timeZone = timeZone
            clockView.text = format.format(Calendar.getInstance(timeZone).time)
            clockView.contentDescription =
                SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm", locale)
                    .apply { timeZone = this@GlassClockFaceController.timeZone }
                    .format(Calendar.getInstance(timeZone).time)
        }

        override val events = GlassFaceEvents()

        inner class GlassFaceEvents : ClockEvents, ClockFaceEvents {
                override fun onTimeTick() = updateTime()

                override fun onTimeZoneChanged(timeZone: TimeZone) {
                    this@GlassClockFaceController.timeZone =
                        java.util.TimeZone.getTimeZone(timeZone.id)
                    updateTime()
                }

                override fun onTimeFormatChanged(formatKind: TimeFormatKind) {
                    is24Hour = formatKind == TimeFormatKind.FULL_DAY
                    updateTime()
                }

                override fun onLocaleChanged(locale: Locale) {
                    this@GlassClockFaceController.locale = locale
                    updateTime()
                }

                override fun onThemeChanged(theme: ThemeConfig) {
                    this@GlassClockFaceController.theme = theme
                    clockView.tint = theme.getDefaultColor(ctx)
                    clockView.aodColor = theme.getAodColor(ctx)
                }

                override fun onFontSettingChanged(fontSizePx: Float) {
                    clockView.requestLayout()
                }

                override fun onTargetRegionChanged(targetRegion: VRect) {
                    // Fill the region given to the clock, keeping the digits' proportions
                    val lp =
                        if (targetRegion.width <= 0 || targetRegion.height <= 0) {
                            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        } else {
                            FrameLayout.LayoutParams(
                                ceil(targetRegion.width.toFloat()).toInt(),
                                ceil(targetRegion.height.toFloat()).toInt(),
                            )
                        }
                    lp.gravity = Gravity.CENTER
                    clockView.layoutParams = lp
                    clockView.translation = clockView.computeLayoutDiff(targetRegion, isLargeClock)
                }

                override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) {}

                override fun onWeatherDataChanged(data: WeatherData) {}

                override fun onAlarmDataChanged(data: AlarmData) {}

                override fun onZenDataChanged(data: ZenData) {}
            }

        override val animations =
            object : ClockAnimations {
                override fun enter() {}

                override fun doze(fraction: Float) {
                    clockView.dozeFraction = fraction
                }

                override fun fold(fraction: Float) {}

                override fun charge() {}

                override fun onPositionAnimated(anim: ClockPositionAnimationArgs) {}

                override fun onPickerCarouselSwiping(swipingFraction: Float) {
                    clockView.invalidate()
                }

                override fun onFidgetTap(x: Float, y: Float) {}

                override fun onFontAxesChanged(style: ClockAxisStyle) {}
            }
    }
}

/**
 * Like the lockscreen (see KeyguardSliceViewSection), the large digits in the picker preview sit
 * high, inset from the screen sides with a fixed height, instead of the stock large clock area.
 */
private class GlassClockFaceLayout(view: View) : DefaultClockFaceLayout(view) {
    override fun applyPreviewConstraints(
        clockPreviewConfig: ClockPreviewConfig,
        constraints: ConstraintSet,
    ): ConstraintSet {
        super.applyPreviewConstraints(clockPreviewConfig, constraints)
        val dp = view.resources.displayMetrics.density
        val large = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
        return constraints.apply {
            clear(large)
            constrainWidth(large, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(large, (PREVIEW_LARGE_HEIGHT_DP * dp).toInt())
            connect(
                large,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP,
                (PREVIEW_LARGE_TOP_DP * dp).toInt(),
            )
            val side = (PREVIEW_LARGE_SIDE_DP * dp).toInt()
            connect(large, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, side)
            connect(large, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, side)
        }
    }
}

// Same place and size as on the lockscreen: under the status bar and the date
private const val PREVIEW_LARGE_TOP_DP = 124
private const val PREVIEW_LARGE_HEIGHT_DP = 300
private const val PREVIEW_LARGE_SIDE_DP = 28
