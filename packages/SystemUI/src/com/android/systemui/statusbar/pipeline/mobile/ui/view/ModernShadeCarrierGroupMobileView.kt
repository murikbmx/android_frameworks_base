/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.StyleRes
import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.telephony.AccessNetworkConstants
import android.telephony.SubscriptionManager
import android.telephony.ims.ImsException
import android.telephony.ims.ImsManager
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.RegistrationManager
import android.telephony.ims.feature.MmTelFeature
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.kairos.KairosNetwork
import com.android.systemui.kairos.buildSpec
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView.STATE_ICON
import com.android.systemui.statusbar.core.NewStatusBarIcons
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinder
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinderKairos
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.ShadeCarrierBinder
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.ShadeCarrierBinderKairos
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.ShadeCarrierGroupMobileIconViewModel
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.ShadeCarrierGroupMobileIconViewModelKairos
import com.android.systemui.util.AutoMarqueeTextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

interface ModernShadeCarrierGroupMobileViewBinding {
    fun setStyleAndTint(@StyleRes style: Int, fgColor: Int, bgColor: Int)
}

/**
 * ViewGroup containing a mobile carrier name and icon in the Shade Header. Can be multiple
 * instances as children under [ShadeCarrierGroup]
 */
class ModernShadeCarrierGroupMobileView(context: Context, attrs: AttributeSet?) :
    LinearLayout(context, attrs) {

    var subId: Int = -1

    private lateinit var binding: ModernShadeCarrierGroupMobileViewBinding

    private val imsText: TextView by lazy { requireViewById(R.id.mobile_ims_text) }
    private var imsMmTelManager: ImsMmTelManager? = null
    private var isImsRegistered = false
    private var imsTransportType = AccessNetworkConstants.TRANSPORT_TYPE_INVALID
    private var isImsVoiceCapable = false

    private val imsRegistrationCallback =
        object : RegistrationManager.RegistrationCallback() {
            override fun onRegistered(attributes: ImsRegistrationAttributes) {
                isImsRegistered = true
                imsTransportType = attributes.transportType
                updateImsStatus()
            }

            override fun onUnregistered(info: ImsReasonInfo) {
                isImsRegistered = false
                updateImsStatus()
            }
        }

    private val imsCapabilityCallback =
        object : ImsMmTelManager.CapabilityCallback() {
            override fun onCapabilitiesStatusChanged(
                capabilities: MmTelFeature.MmTelCapabilities
            ) {
                isImsVoiceCapable =
                    capabilities.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE)
                updateImsStatus()
            }
        }

    private val imsSettingObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                updateImsStatus()
            }
        }

    /**
     * Update the appearance of the mobile carrier group. The text itself can use the text
     * appearance resId, but the mobile icon needs to know specifically about fg/bg colors.
     */
    fun setStyleAndTint(@StyleRes styleResId: Int, fgColor: Int, bgColor: Int) {
        binding.setStyleAndTint(style = styleResId, fgColor = fgColor, bgColor = bgColor)
        // The badge follows the carrier text, only with a smaller text size and an outline.
        imsText.setTextAppearance(styleResId)
        imsText.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(R.dimen.shade_ims_status_text_size),
        )
        imsText.background =
            GradientDrawable().apply {
                cornerRadius = resources.getDimension(R.dimen.shade_ims_status_corner_radius)
                setStroke(
                    resources.getDimensionPixelSize(R.dimen.shade_ims_status_stroke_width),
                    imsText.currentTextColor,
                )
            }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.SHADE_IMS_STATUS),
            /* notifyForDescendants= */ false,
            imsSettingObserver,
            UserHandle.USER_ALL,
        )
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            return
        }
        val manager =
            context.getSystemService(ImsManager::class.java)?.getImsMmTelManager(subId) ?: return
        try {
            manager.registerImsRegistrationCallback(context.mainExecutor, imsRegistrationCallback)
            manager.registerMmTelCapabilityCallback(context.mainExecutor, imsCapabilityCallback)
            imsMmTelManager = manager
        } catch (e: ImsException) {
            // IMS isn't available for this subscription, e.g. there is no IMS service.
            Log.d(TAG, "IMS isn't available for subId=$subId", e)
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "Invalid subId=$subId for IMS", e)
        }
    }

    override fun onDetachedFromWindow() {
        context.contentResolver.unregisterContentObserver(imsSettingObserver)
        imsMmTelManager?.let {
            it.unregisterImsRegistrationCallback(imsRegistrationCallback)
            it.unregisterMmTelCapabilityCallback(imsCapabilityCallback)
        }
        imsMmTelManager = null
        isImsRegistered = false
        isImsVoiceCapable = false
        updateImsStatus()
        super.onDetachedFromWindow()
    }

    /** Shows VoLTE or VoWiFi while IMS is registered for voice, hides the badge otherwise. */
    private fun updateImsStatus() {
        val enabled =
            Settings.Secure.getIntForUser(
                context.contentResolver,
                Settings.Secure.SHADE_IMS_STATUS,
                1,
                UserHandle.USER_CURRENT,
            ) == 1
        val label =
            when {
                !enabled || !isImsRegistered || !isImsVoiceCapable -> null
                imsTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN ->
                    R.string.shade_ims_status_vowifi
                else -> R.string.shade_ims_status_volte
            }
        if (label == null) {
            imsText.visibility = GONE
        } else {
            imsText.setText(label)
            imsText.visibility = VISIBLE
        }
    }

    override fun toString(): String {
        return "ModernShadeCarrierGroupMobileView(" +
            "subId=$subId, " +
            "viewString=${super.toString()}"
    }

    companion object {
        private const val TAG = "ModernShadeCarrierGroupMobileView"

        /**
         * Inflates a new instance of [ModernShadeCarrierGroupMobileView], binds it to [viewModel],
         * and returns it.
         */
        @JvmStatic
        fun constructAndBind(
            context: Context,
            logger: MobileViewLogger,
            slot: String,
            viewModel: ShadeCarrierGroupMobileIconViewModel,
        ): ModernShadeCarrierGroupMobileView {
            return (LayoutInflater.from(context).inflate(R.layout.shade_carrier_new, null)
                    as ModernShadeCarrierGroupMobileView)
                .apply {
                    subId = viewModel.subscriptionId

                    val iconView = requireViewById<ModernStatusBarMobileView>(R.id.mobile_combo)
                    if (NewStatusBarIcons.isEnabled) {
                        iconView.configureLayoutForNewStatusBarIcons()
                    }
                    iconView.initView(slot) {
                        MobileIconBinder.bind(iconView, viewModel, STATE_ICON, logger)
                    }
                    logger.logNewViewBinding(this, viewModel)

                    val textView = requireViewById<AutoMarqueeTextView>(R.id.mobile_carrier_text)
                    val shadeCarrierBinding = ShadeCarrierBinder.bind(textView, viewModel)

                    binding =
                        object : ModernShadeCarrierGroupMobileViewBinding {
                            override fun setStyleAndTint(
                                @StyleRes style: Int,
                                fgColor: Int,
                                bgColor: Int,
                            ) {
                                iconView.setStaticDrawableColor(fgColor, bgColor)
                                shadeCarrierBinding.setTextAppearance(style)
                            }
                        }
                }
        }

        /**
         * Inflates a new instance of [ModernShadeCarrierGroupMobileView], binds it to [viewModel],
         * and returns it.
         */
        @JvmStatic
        fun constructAndBindKairos(
            context: Context,
            logger: MobileViewLogger,
            slot: String,
            viewModel: ShadeCarrierGroupMobileIconViewModelKairos,
            scope: CoroutineScope,
            subscriptionId: Int,
            location: StatusBarLocation,
            kairosNetwork: KairosNetwork,
        ): Pair<ModernShadeCarrierGroupMobileView, Job> {
            val view =
                (LayoutInflater.from(context).inflate(R.layout.shade_carrier_new, null)
                        as ModernShadeCarrierGroupMobileView)
                    .apply {
                        subId = subscriptionId

                        val iconView = requireViewById<ModernStatusBarMobileView>(R.id.mobile_combo)
                        if (NewStatusBarIcons.isEnabled) {
                            iconView.configureLayoutForNewStatusBarIcons()
                        }
                    }
            return view to
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val iconView =
                        view.requireViewById<ModernStatusBarMobileView>(R.id.mobile_combo)
                    iconView.initView(slot) {
                        val (binding, _) =
                            MobileIconBinderKairos.bind(
                                view = iconView,
                                viewModel = buildSpec { viewModel },
                                initialVisibilityState = STATE_ICON,
                                logger = logger,
                                scope = this,
                                kairosNetwork = kairosNetwork,
                                subId = subscriptionId,
                            )
                        binding
                    }
                    logger.logNewViewBinding(view, viewModel, location.name)

                    val textView =
                        view.requireViewById<AutoMarqueeTextView>(R.id.mobile_carrier_text)
                    val (shadeCarrierBinding, _) =
                        ShadeCarrierBinderKairos.bind(
                            subscriptionId,
                            textView,
                            buildSpec { viewModel },
                            kairosNetwork,
                            this,
                        )
                    view.binding =
                        object : ModernShadeCarrierGroupMobileViewBinding {
                            override fun setStyleAndTint(style: Int, fgColor: Int, bgColor: Int) {
                                iconView.setStaticDrawableColor(fgColor, bgColor)
                                shadeCarrierBinding.setTextAppearance(style)
                            }
                        }
                }
        }
    }
}
