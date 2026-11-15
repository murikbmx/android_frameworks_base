/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.keyguard.KeyguardSliceView
import com.android.keyguard.KeyguardSliceViewController
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.KeyguardSliceViewBinder
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

class KeyguardSliceViewSection
@Inject
constructor(
    @ShadeDisplayAware val context: Context,
    val smartspaceController: LockscreenSmartspaceController,
    val layoutInflater: LayoutInflater,
    @Main val handler: Handler,
    @Background val bgHandler: Handler,
    val activityStarter: ActivityStarter,
    val configurationController: ConfigurationController,
    val dumpManager: DumpManager,
    val displayTracker: DisplayTracker,
    val keyguardInteractor: KeyguardInteractor,
    val powerInteractor: PowerInteractor,
    val aodBurnInViewModel: AodBurnInViewModel,
    val keyguardSmartspaceViewModel: KeyguardSmartspaceViewModel,
    val keyguardClockViewModel: KeyguardClockViewModel,
) : KeyguardSection() {
    private lateinit var sliceView: KeyguardSliceView
    private var disposableHandle: DisposableHandle? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (smartspaceController.isEnabled) return
        sliceView =
            layoutInflater.inflate(R.layout.keyguard_slice_view, null, false) as KeyguardSliceView
        constraintLayout.addView(sliceView)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (smartspaceController.isEnabled) return
        val controller =
            KeyguardSliceViewController(
                handler,
                bgHandler,
                sliceView,
                activityStarter,
                configurationController,
                dumpManager,
                displayTracker,
                keyguardInteractor,
                powerInteractor,
            )
        controller.setupUri(null)
        controller.init()

        disposableHandle?.dispose()
        disposableHandle =
            KeyguardSliceViewBinder.bind(
                sliceView,
                keyguardInteractor,
                controller,
                aodBurnInViewModel,
            )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (smartspaceController.isEnabled) return
        val dateWeatherPaddingStart = KeyguardSmartspaceViewModel.getDateWeatherStartMargin(context)
        constraintSet.apply {
            connect(
                R.id.keyguard_slice_view,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                dateWeatherPaddingStart,
            )
            connect(
                R.id.keyguard_slice_view,
                ConstraintSet.END,
                if (keyguardSmartspaceViewModel.isFullWidthShade.value) {
                    ConstraintSet.PARENT_ID
                } else {
                    R.id.split_shade_guideline
                },
                ConstraintSet.END,
            )
            constrainHeight(R.id.keyguard_slice_view, ConstraintSet.WRAP_CONTENT)

            connect(
                R.id.keyguard_slice_view,
                ConstraintSet.TOP,
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                ConstraintSet.BOTTOM,
            )

            createBarrier(
                R.id.smart_space_barrier_bottom,
                Barrier.BOTTOM,
                0,
                *intArrayOf(R.id.keyguard_slice_view),
            )
        }
        applyGlassClockConstraints(constraintSet)
    }

    /**
     * The glass clock: the date first and the digits right under it, centered and high on screen
     * without notifications, wide at the start with them.
     */
    private fun applyGlassClockConstraints(constraintSet: ConstraintSet) {
        if (keyguardClockViewModel.currentClock.value?.config?.id != GLASS_CLOCK_ID) return
        val res = context.resources
        val dp = res.displayMetrics.density
        val startMargin = KeyguardSmartspaceViewModel.getDateWeatherStartMargin(context)
        val date = R.id.keyguard_slice_view
        val small = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
        val large = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
        val parent = ConstraintSet.PARENT_ID
        constraintSet.apply {
            clear(date)
            constrainHeight(date, ConstraintSet.WRAP_CONTENT)
            // Below the status bar, where the small clock would start
            connect(date, ConstraintSet.TOP, R.id.small_clock_guideline_top, ConstraintSet.BOTTOM)
            if (keyguardClockViewModel.isLargeClockVisible.value) {
                constrainWidth(date, ConstraintSet.WRAP_CONTENT)
                connect(date, ConstraintSet.START, parent, ConstraintSet.START)
                connect(date, ConstraintSet.END, parent, ConstraintSet.END)
                val side = (GLASS_LARGE_SIDE_DP * dp).toInt()
                clear(large)
                constrainWidth(large, ConstraintSet.MATCH_CONSTRAINT)
                constrainHeight(large, (GLASS_LARGE_HEIGHT_DP * dp).toInt())
                connect(large, ConstraintSet.TOP, date, ConstraintSet.BOTTOM, (4 * dp).toInt())
                connect(large, ConstraintSet.START, parent, ConstraintSet.START, side)
                connect(large, ConstraintSet.END, parent, ConstraintSet.END, side)
            } else {
                constrainWidth(date, ConstraintSet.WRAP_CONTENT)
                connect(date, ConstraintSet.START, parent, ConstraintSet.START, startMargin)
                clear(small)
                constrainWidth(small, (GLASS_SMALL_WIDTH_DP * dp).toInt())
                constrainHeight(small, (GLASS_SMALL_HEIGHT_DP * dp).toInt())
                connect(small, ConstraintSet.TOP, date, ConstraintSet.BOTTOM, (2 * dp).toInt())
                connect(small, ConstraintSet.START, parent, ConstraintSet.START, startMargin)
            }
            // Notifications start below the date and the clock
            createBarrier(R.id.smart_space_barrier_bottom, Barrier.BOTTOM, 0, date, small)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {}
}

/** Id of the glass clock face, see DefaultClockProvider in SystemUICustomizationLib. */
private const val GLASS_CLOCK_ID = "LINEAGE_GLASS"
private const val GLASS_LARGE_HEIGHT_DP = 300
private const val GLASS_LARGE_SIDE_DP = 28
private const val GLASS_SMALL_WIDTH_DP = 240
private const val GLASS_SMALL_HEIGHT_DP = 120
