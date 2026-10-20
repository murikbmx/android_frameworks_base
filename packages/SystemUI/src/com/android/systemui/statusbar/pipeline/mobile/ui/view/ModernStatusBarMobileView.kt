/*
 * Copyright (C) 2022 The Android Open Source Project
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
 */

package com.android.systemui.statusbar.pipeline.mobile.ui.view

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.android.keyguard.AlphaOptimizedLinearLayout
import com.android.systemui.kairos.KairosNetwork
import com.android.systemui.kairos.buildSpec
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView.getVisibleStateString
import com.android.systemui.statusbar.core.NewStatusBarIcons
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinder
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinderKairos
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModel
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModelKairos
import com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

class ModernStatusBarMobileView(context: Context, attrs: AttributeSet?) :
    ModernStatusBarView(context, attrs) {

    var subId: Int = -1

    override fun toString(): String {
        return "ModernStatusBarMobileView(" +
            "slot='$slot', " +
            "subId=$subId, " +
            "isCollecting=${binding.isCollecting()}, " +
            "visibleState=${getVisibleStateString(visibleState)}); " +
            "viewString=${super.toString()}"
    }

    public override fun onConfigurationChanged(newConfig: Configuration?) {
        configureLayoutForNewStatusBarIcons()
    }

    /**
     * When [NewStatusBarIcons] is enabled, we have to tweak the layout. This can be moved directly
     * into the layout xml when the flag is rolled out fully
     */
    fun configureLayoutForNewStatusBarIcons() {
        // Margins around the entire container
        requireViewById<AlphaOptimizedLinearLayout>(R.id.mobile_group).apply {
            val lp = layoutParams as MarginLayoutParams
            lp.marginStart =
                context.resources.getDimensionPixelSize(
                    R.dimen.status_bar_mobile_container_margin_start
                )
            lp.marginEnd =
                context.resources.getDimensionPixelSize(
                    R.dimen.status_bar_mobile_container_margin_end
                )
        }

        // triangle
        requireViewById<ImageView>(R.id.mobile_signal).apply {
            layoutParams.height =
                context.resources.getDimensionPixelSize(
                    R.dimen.status_bar_mobile_signal_size_updated
                )
        }

        // RAT indicator container and RAT indicator
        applyNetworkTypeLayout()
    }

    // Row placement of the RAT indicator container as inflated from the layout xml
    private var rowTypeContainerLayoutParams: LinearLayout.LayoutParams? = null
    private var rowTypeHeight = 0

    private val networkTypeOverlayObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                applyNetworkTypeLayout()
            }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.STATUS_BAR_MOBILE_TYPE_OVERLAY),
            false,
            networkTypeOverlayObserver,
            UserHandle.USER_ALL,
        )
        applyNetworkTypeLayout()
    }

    override fun onDetachedFromWindow() {
        context.contentResolver.unregisterContentObserver(networkTypeOverlayObserver)
        super.onDetachedFromWindow()
    }

    /**
     * Places the RAT indicator either next to the triangle, as in the layout xml, or small over the
     * top start corner of the triangle (pre-P style) when
     * [Settings.Secure.STATUS_BAR_MOBILE_TYPE_OVERLAY] is enabled.
     */
    private fun applyNetworkTypeLayout() {
        val container = requireViewById<FrameLayout>(R.id.mobile_type_container)
        val typeView = requireViewById<ImageView>(R.id.mobile_type)
        val group = requireViewById<AlphaOptimizedLinearLayout>(R.id.mobile_group)
        val signalFrame = requireViewById<ImageView>(R.id.mobile_signal).parent as FrameLayout
        val res = context.resources

        if (rowTypeContainerLayoutParams == null) {
            rowTypeContainerLayoutParams =
                LinearLayout.LayoutParams(container.layoutParams as LinearLayout.LayoutParams)
            rowTypeHeight = typeView.layoutParams.height
        }

        val overlay =
            Settings.Secure.getIntForUser(
                context.contentResolver,
                Settings.Secure.STATUS_BAR_MOBILE_TYPE_OVERLAY,
                0,
                UserHandle.USER_CURRENT,
            ) == 1

        (container.parent as ViewGroup).removeView(container)
        if (overlay) {
            val size = res.getDimensionPixelSize(R.dimen.status_bar_mobile_type_overlay_size)
            signalFrame.addView(
                container,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    size,
                    Gravity.TOP or Gravity.START,
                ),
            )
            typeView.layoutParams.height = size
        } else {
            val lp = LinearLayout.LayoutParams(rowTypeContainerLayoutParams!!)
            var typeHeight = rowTypeHeight
            if (NewStatusBarIcons.isEnabled) {
                // marginStart is moved to the outer group
                lp.marginStart = 0
                lp.marginEnd =
                    res.getDimensionPixelSize(R.dimen.status_bar_mobile_type_container_margin_end)
                lp.height =
                    res.getDimensionPixelSize(R.dimen.status_bar_mobile_container_height_updated)
                typeHeight = res.getDimensionPixelSize(R.dimen.status_bar_mobile_type_size_updated)
            }
            group.addView(
                container,
                group.indexOfChild(requireViewById(R.id.mobile_roaming_space)),
                lp,
            )
            typeView.layoutParams.height = typeHeight
        }
        typeView.requestLayout()
    }

    companion object {

        /**
         * Inflates a new instance of [ModernStatusBarMobileView], binds it to [viewModel], and
         * returns it.
         */
        @JvmStatic
        fun constructAndBind(
            context: Context,
            logger: MobileViewLogger,
            slot: String,
            viewModel: LocationBasedMobileViewModel,
        ): ModernStatusBarMobileView {
            return (LayoutInflater.from(context)
                    .inflate(R.layout.status_bar_mobile_signal_group_new, null)
                    as ModernStatusBarMobileView)
                .apply {
                    // Flag-specific configuration
                    if (NewStatusBarIcons.isEnabled) {
                        configureLayoutForNewStatusBarIcons()
                    }

                    subId = viewModel.subscriptionId
                    initView(slot) {
                        MobileIconBinder.bind(view = this, viewModel = viewModel, logger = logger)
                    }
                    logger.logNewViewBinding(this, viewModel)
                }
        }

        /**
         * Inflates a new instance of [ModernStatusBarMobileView], binds it to [viewModel], and
         * returns it.
         */
        @JvmStatic
        fun constructAndBind(
            context: Context,
            logger: MobileViewLogger,
            slot: String,
            viewModel: LocationBasedMobileViewModelKairos,
            scope: CoroutineScope,
            subscriptionId: Int,
            location: StatusBarLocation,
            kairosNetwork: KairosNetwork,
        ): Pair<ModernStatusBarMobileView, Job> {
            val view =
                (LayoutInflater.from(context)
                        .inflate(R.layout.status_bar_mobile_signal_group_new, null)
                        as ModernStatusBarMobileView)
                    .apply {
                        // Flag-specific configuration
                        if (NewStatusBarIcons.isEnabled) {
                            configureLayoutForNewStatusBarIcons()
                        }

                        subId = subscriptionId
                    }

            lateinit var jobResult: Job
            view.initView(slot) {
                val (binding, job) =
                    MobileIconBinderKairos.bind(
                        view = view,
                        viewModel = buildSpec { viewModel },
                        logger = logger,
                        scope = scope,
                        kairosNetwork = kairosNetwork,
                        subId = subscriptionId,
                    )
                jobResult = job
                binding
            }
            logger.logNewViewBinding(view, viewModel, location.name)
            return view to jobResult
        }
    }
}
