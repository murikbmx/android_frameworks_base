/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.lineage.batteryring

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Handler
import android.os.SystemClock
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import com.android.internal.statusbar.LetterboxDetails
import com.android.internal.view.AppearanceRegion
import com.android.settingslib.fuelgauge.BatteryStatus
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.math.min

/**
 * Shows the battery level as a ring around the front camera cutout: when the charger is plugged,
 * when the screen turns on while charging, when the battery is full or low, and all the time on
 * the lockscreen while charging.
 */
@SysUISingleton
class BatteryRingController
@Inject
constructor(
    private val context: Context,
    private val keyguardStateController: KeyguardStateController,
    private val statusBarStateController: StatusBarStateController,
    private val commandQueue: CommandQueue,
    private val userTracker: UserTracker,
    @Main private val mainHandler: Handler,
    @Main private val mainExecutor: Executor,
) : CoreStartable {

    private val windowManager = context.getSystemService(WindowManager::class.java)!!
    private val resolver = context.contentResolver
    private val density = context.resources.displayMetrics.density

    private var view: BatteryRingView? = null
    private var params: LayoutParams? = null

    // Battery state
    private var level = -1
    private var plugged = false
    private var wireless = false
    private var fast = false
    private var charged = false

    // Screen state
    private var screenOn = true
    private var statusBarVisible = true
    private var lightBackground = false
    private val camerasInUse = mutableSetOf<String>()
    private var frontCameras = emptySet<String>()
    private var lastScreenOnRing = 0L
    private var screenOnPending = false
    private var fullShown = false
    private var lockWallpaperLight = false

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                updateColors()
                update()
            }
        }

    private val batteryReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onBatteryChanged(intent)
            }
        }

    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOn = false
                        screenOnPending = false
                        update()
                    }
                }
            }
        }

    private val keyguardCallback =
        object : KeyguardStateController.Callback {
            override fun onKeyguardShowingChanged() {
                updateColors()
                update()
            }
        }

    private val stateListener =
        object : StatusBarStateController.StateListener {
            override fun onDozingChanged(isDozing: Boolean) {
                update()
                if (!isDozing) showScreenOnRing()
            }
        }

    private val commandQueueCallbacks =
        object : CommandQueue.Callbacks {
            override fun onSystemBarAttributesChanged(
                displayId: Int,
                appearance: Int,
                appearanceRegions: Array<AppearanceRegion>?,
                navbarColorManagedByIme: Boolean,
                behavior: Int,
                requestedVisibleTypes: Int,
                packageName: String?,
                letterboxDetails: Array<LetterboxDetails>?,
            ) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                // Hidden status bar: fullscreen app or video
                val visible = (requestedVisibleTypes and WindowInsets.Type.statusBars()) != 0
                // Light status bar appearance means a light background behind the camera
                val cutout = cutoutCenter
                val region =
                    appearanceRegions?.firstOrNull {
                        cutout != null && it.bounds.contains(cutout.x, cutout.y)
                    }
                val light =
                    ((region?.appearance ?: appearance) and
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) != 0
                if (light != lightBackground) {
                    lightBackground = light
                    updateColors()
                }
                if (visible != statusBarVisible) {
                    statusBarVisible = visible
                    update()
                }
            }
        }

    /** Center of the top cutout, in display coordinates. */
    private var cutoutCenter: Point? = null

    private val cameraCallback =
        object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                camerasInUse.add(cameraId)
                update()
            }

            override fun onCameraAvailable(cameraId: String) {
                camerasInUse.remove(cameraId)
                update()
            }
        }

    private val wallpaperListener =
        WallpaperManager.OnColorsChangedListener { _, _ ->
            lockWallpaperLight = readLockWallpaperIsLight()
            updateColors()
        }

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            override fun onDisplayChanged(displayId: Int) {
                // Also catches 0 <-> 180 rotations, which change no configuration
                if (displayId == Display.DEFAULT_DISPLAY) updateWindow()
            }
        }

    private val userCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                updateColors()
                update()
            }
        }

    private val configCallbacks =
        object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                updateWindow()
            }

            @Deprecated("Deprecated in Java") override fun onLowMemory() {}
        }

    override fun start() {
        // Enabled per device, for OLED screens with a round camera cutout
        if (!SystemProperties.getBoolean(PROP_SUPPORTED, false)) return
        for (key in KEYS) {
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(key),
                false,
                settingsObserver,
                UserHandle.USER_ALL,
            )
        }
        context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            null,
            mainHandler,
        )
        context.registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            null,
            mainHandler,
        )
        keyguardStateController.addCallback(keyguardCallback)
        statusBarStateController.addCallback(stateListener)
        context.registerComponentCallbacks(configCallbacks)
        runCatching {
                val cameraManager = context.getSystemService(CameraManager::class.java)!!
                frontCameras =
                    cameraManager.cameraIdList
                        .filter {
                            cameraManager
                                .getCameraCharacteristics(it)
                                .get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_FRONT
                        }
                        .toSet()
                cameraManager.registerAvailabilityCallback(mainExecutor, cameraCallback)
            }
            .onFailure { Log.w(TAG, "Unable to track the front camera", it) }
        runCatching {
                context
                    .getSystemService(WallpaperManager::class.java)
                    ?.addOnColorsChangedListener(wallpaperListener, mainHandler)
            }
            .onFailure { Log.w(TAG, "Unable to track the wallpaper colors", it) }
        commandQueue.addCallback(commandQueueCallbacks)
        userTracker.addCallback(userCallback, mainExecutor)
        context
            .getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, mainHandler)
        lockWallpaperLight = readLockWallpaperIsLight()
        updateWindow()
    }

    private fun displayRotation(): Int =
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation ?: Surface.ROTATION_0

    private fun isEnabled(key: String) =
        Settings.Secure.getIntForUser(resolver, key, 1, UserHandle.USER_CURRENT) != 0

    private val keyguardVisible: Boolean
        get() = keyguardStateController.isShowing && !keyguardStateController.isOccluded

    /** Whether the ring may be drawn right now. */
    private fun canShow(): Boolean {
        val v = view ?: return false
        return isEnabled(KEY_ENABLED) &&
            v.ringRadius > 0f &&
            screenOn &&
            !statusBarStateController.isDozing &&
            (statusBarVisible || keyguardVisible) &&
            camerasInUse.none { it in frontCameras } &&
            displayRotation() == Surface.ROTATION_0
    }

    /** Still ring on the always-on display while charging. */
    private fun wantsAod(): Boolean {
        val v = view ?: return false
        return plugged &&
            statusBarStateController.isDozing &&
            Settings.Secure.getIntForUser(
                resolver,
                Settings.Secure.DOZE_ALWAYS_ON,
                0,
                UserHandle.USER_CURRENT,
            ) != 0 &&
            isEnabled(KEY_ENABLED) &&
            isEnabled(KEY_AOD) &&
            v.ringRadius > 0f &&
            camerasInUse.none { it in frontCameras } &&
            displayRotation() == Surface.ROTATION_0
    }

    private fun wantsPersistent() =
        plugged && canShow() && (alwaysMode() || (keyguardVisible && isEnabled(KEY_LOCKSCREEN)))

    /** The status bar battery is hidden: the ring always shows the level instead. */
    private fun alwaysMode() =
        isEnabled(KEY_ENABLED) &&
            Settings.Secure.getIntForUser(resolver, KEY_REPLACE_ICON, 0, UserHandle.USER_CURRENT) != 0

    private fun levelFraction() = if (level < 0) 0f else level / 100f

    /** Brings the ring in line with the current state. */
    private fun update() {
        val v = view ?: return
        if (DEBUG) {
            Log.d(TAG, "update: level=$level plugged=$plugged screenOn=$screenOn " +
                "dozing=${statusBarStateController.isDozing} sb=$statusBarVisible " +
                "kg=$keyguardVisible radius=${v.ringRadius} cams=$camerasInUse " +
                "rot=${displayRotation()} canShow=${canShow()} aod=${wantsAod()}")
        }
        if (wantsAod()) {
            applyStyle(aod = true)
            v.showStatic(if (charged) 1f else levelFraction(), AOD_ALPHA)
            return
        }
        if (!canShow()) {
            if (v.isActive) v.hide(animate = false)
            return
        }
        when {
            wantsPersistent() -> {
                if (!v.isPersistent) {
                    if (v.isStatic) v.hide(animate = false)
                    applyStyle()
                    v.showPersistent(levelFraction(), fromEmpty = false, fast = fast, charged = charged)
                }
            }
            alwaysMode() -> {
                applyStyle()
                v.showStatic(levelFraction(), 1f)
            }
            v.isPersistent || v.isStatic -> v.hide()
        }
    }

    private fun onBatteryChanged(intent: Intent) {
        val status = BatteryStatus(intent)
        val newLevel = status.level
        val newPlugged = status.isPluggedIn
        val newCharged =
            newPlugged &&
                (intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ==
                    BatteryManager.BATTERY_STATUS_FULL || newLevel >= 100)
        wireless = status.isPluggedInWireless
        fast = status.getChargingSpeed(context) == BatteryStatus.CHARGING_FAST

        val first = level < 0
        val oldLevel = level
        val wasPlugged = plugged
        val wasCharged = charged
        level = newLevel
        plugged = newPlugged
        charged = newCharged
        if (first) {
            fullShown = newCharged
            updateColors()
            update()
            return
        }

        val v = view ?: return
        if (wantsAod() || (v.isStatic && !alwaysMode())) {
            update()
            return
        }
        when {
            newPlugged && !wasPlugged -> {
                if (!canShow()) return
                applyStyle()
                if (wantsPersistent()) {
                    v.showPersistent(levelFraction(), fromEmpty = true, fast = fast, charged = charged)
                } else {
                    v.showCharge(levelFraction(), fromEmpty = true, fast = fast, sweeps = 3, holdSec = 4f)
                }
            }
            !newPlugged && wasPlugged -> {
                fullShown = false
                if (alwaysMode()) update() else if (v.isActive) v.hide()
                checkLow(oldLevel)
            }
            newCharged && !wasCharged && !fullShown -> {
                fullShown = true
                if (!canShow()) return
                applyStyle()
                v.showFull(persistAfter = wantsPersistent())
            }
            else -> {
                if (oldLevel != newLevel) {
                    if (v.isStatic) {
                        update()
                    } else {
                        applyStyle()
                        v.updateLevel(levelFraction())
                    }
                }
                if (!newPlugged) checkLow(oldLevel)
            }
        }
    }

    /** Pulses the ring when the level drops to the battery saver level or to 5%. */
    private fun checkLow(oldLevel: Int) {
        if (!isEnabled(KEY_LOW) || !canShow()) return
        val crossed = listOf(lowLevel(), CRITICAL_LEVEL).any { oldLevel > it && level <= it }
        if (!crossed) return
        applyStyle(warning = true)
        view?.pulseLow(levelFraction())
        // Back to the still ring once the pulses are over
        if (alwaysMode()) mainHandler.postDelayed({ update() }, LOW_PULSE_MS)
    }

    /** The level set on the battery saver schedule slider, or the low battery warning level. */
    private fun lowLevel(): Int {
        val trigger = Settings.Global.getInt(resolver, Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0)
        if (trigger > 0) return trigger
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_lowBatteryWarningLevel", "integer", "android")
        return if (id != 0) res.getInteger(id) else 15
    }

    private fun onScreenOn() {
        screenOn = true
        screenOnPending = true
        mainHandler.post {
            update()
            showScreenOnRing()
        }
    }

    /** The short ring when the screen turns on while charging, once doze has ended. */
    private fun showScreenOnRing() {
        if (!screenOnPending) return
        val v = view ?: return
        // Still leaving the always-on display: retried when dozing ends
        if (statusBarStateController.isDozing) return
        screenOnPending = false
        if (alwaysMode()) return
        if (!plugged || v.isPersistent || !canShow() || !isEnabled(KEY_SCREEN_ON)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastScreenOnRing < SCREEN_ON_INTERVAL_MS) return
        lastScreenOnRing = now
        applyStyle()
        if (charged) v.showFull(persistAfter = false)
        else v.showCharge(levelFraction(), fromEmpty = false, fast = fast, sweeps = 1, holdSec = 3f)
    }

    /** Colors from the level and the background, dashes for wireless charging. */
    private fun applyStyle(aod: Boolean = false, warning: Boolean = false) {
        val v = view ?: return
        // The always-on display is black
        val light =
            when {
                aod -> false
                keyguardVisible -> lockWallpaperLight
                else -> lightBackground
            }
        val color =
            if (warning) (if (light) RED_ON_LIGHT else RED_ON_DARK) else ringColor(light)
        val halo = if (light) Color.argb(150, 255, 255, 255) else Color.argb(140, 0, 0, 0)
        v.setColors(color, halo)
        v.dashed = plugged && wireless
    }

    /**
     * Color of the ring for the color setting, lighter on a dark background and darker on a light
     * one. Low levels are always red or amber, except with the fixed colors above 20%.
     */
    private fun ringColor(light: Boolean): Int {
        val mode =
            Settings.Secure.getIntForUser(resolver, KEY_COLOR, COLOR_WALLPAPER, UserHandle.USER_CURRENT)
        if (level in 0 until LOW_COLOR_LEVEL) return if (light) RED_ON_LIGHT else RED_ON_DARK
        val fixed = FIXED_COLORS[mode]
        if (fixed != null) return if (light) fixed.second else fixed.first
        if (level < MID_COLOR_LEVEL) return if (light) AMBER_ON_LIGHT else AMBER_ON_DARK
        return when (mode) {
            COLOR_LEVEL -> if (light) GREEN_ON_LIGHT else GREEN_ON_DARK
            else ->
                context.getColor(
                    if (light) android.R.color.system_accent1_600
                    else android.R.color.system_accent1_200
                )
        }
    }

    private fun updateColors() {
        if (view?.isActive == true) applyStyle()
    }

    private fun readLockWallpaperIsLight(): Boolean =
        runCatching {
                val wm = context.getSystemService(WallpaperManager::class.java) ?: return false
                val colors =
                    wm.getWallpaperColors(WallpaperManager.FLAG_LOCK)
                        ?: wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                colors != null &&
                    (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
            }
            .getOrDefault(false)

    /** Places the ring window on the top cutout, or removes it if there is none. */
    private fun updateWindow() {
        val cutout = topCutoutBounds()
        if (cutout == null || displayRotation() != Surface.ROTATION_0) {
            view?.let {
                it.hide(animate = false)
                it.ringRadius = 0f
            }
            cutoutCenter = null
            return
        }
        cutoutCenter = Point(cutout.centerX(), cutout.centerY())
        val radius = min(cutout.width(), cutout.height()) / 2f
        val stroke = 3f * density
        val ringRadius = radius + RING_GAP_DP * density + stroke / 2f
        val size = ((ringRadius + stroke * 2 + 2 * density) * 2).toInt()
        val x = cutout.centerX() - size / 2
        val y = cutout.centerY() - size / 2

        val v = view ?: createView()
        v.ringRadius = ringRadius
        val p = params ?: return
        if (p.width != size || p.x != x || p.y != y) {
            p.width = size
            p.height = size
            p.x = x
            p.y = y
            runCatching { windowManager.updateViewLayout(v, p) }
        }
        update()
    }

    /**
     * Bounds of the camera hole at the top of the screen: from the cutout shape when known, the
     * bounding rect may be a larger approximation.
     */
    private fun topCutoutBounds(): Rect? {
        val cutout = windowManager.currentWindowMetrics.windowInsets.displayCutout ?: return null
        val top = cutout.boundingRectTop.takeIf { !it.isEmpty } ?: return null
        val path = cutout.cutoutPath ?: return top
        val bounds = RectF()
        path.computeBounds(bounds, true)
        val rect = Rect()
        bounds.roundOut(rect)
        // Keep the part of the shape inside the top cutout area
        return if (rect.intersect(top)) rect else top
    }

    private fun createView(): BatteryRingView {
        val v = BatteryRingView(context)
        val p =
            LayoutParams(
                    0,
                    0,
                    LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    LayoutParams.FLAG_NOT_FOCUSABLE or
                        LayoutParams.FLAG_NOT_TOUCHABLE or
                        LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    title = "LineageBatteryRing"
                    layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    // Left out of screenshots and screen recordings, like the cutout overlays
                    privateFlags =
                        privateFlags or
                            LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS or
                            LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY
                    setFitInsetsTypes(0)
                    setTrustedOverlay()
                }
        runCatching { windowManager.addView(v, p) }
            .onFailure { Log.e(TAG, "Unable to add the battery ring", it) }
        view = v
        params = p
        return v
    }

    companion object {
        private const val TAG = "BatteryRingController"
        private const val DEBUG = false

        /** Settings.Secure keys, all enabled by default. */
        /** Set by the device tree where the ring is wanted. */
        const val PROP_SUPPORTED = "ro.lineage.battery_ring.supported"

        const val KEY_ENABLED = "lineage_battery_ring"
        const val KEY_LOW = "lineage_battery_ring_low"
        const val KEY_SCREEN_ON = "lineage_battery_ring_screen_on"
        const val KEY_LOCKSCREEN = "lineage_battery_ring_lockscreen"
        const val KEY_AOD = "lineage_battery_ring_aod"
        const val KEY_COLOR = "lineage_battery_ring_color"
        const val KEY_REPLACE_ICON = "lineage_battery_ring_replace_icon"
        private val KEYS =
            listOf(
                KEY_ENABLED,
                KEY_LOW,
                KEY_SCREEN_ON,
                KEY_LOCKSCREEN,
                KEY_AOD,
                KEY_COLOR,
                KEY_REPLACE_ICON,
            )

        // Values of KEY_COLOR
        private const val COLOR_WALLPAPER = 0
        private const val COLOR_LEVEL = 1

        /** Fixed colors of KEY_COLOR: on a dark background to on a light one. */
        private val FIXED_COLORS =
            mapOf(
                2 to (0xFFF5F5F5.toInt() to 0xFF5F6368.toInt()),
                3 to (0xFF6EE7A0.toInt() to 0xFF1E8E4E.toInt()),
                4 to (0xFF7CC4FF.toInt() to 0xFF1565C0.toInt()),
                5 to (0xFFFF8AC6.toInt() to 0xFFC2185B.toInt()),
                6 to (0xFFFFA65C.toInt() to 0xFFD45500.toInt()),
            )
        private const val GREEN_ON_DARK = 0xFF6EE7A0.toInt()
        private const val GREEN_ON_LIGHT = 0xFF1E8E4E.toInt()

        private const val AOD_ALPHA = 0.55f
        private const val LOW_PULSE_MS = 3100L


        private const val RING_GAP_DP = 2.5f
        private const val CRITICAL_LEVEL = 5
        private const val LOW_COLOR_LEVEL = 20
        private const val MID_COLOR_LEVEL = 50
        private const val SCREEN_ON_INTERVAL_MS = 30_000L

        private const val RED_ON_DARK = 0xFFFF6B6B.toInt()
        private const val RED_ON_LIGHT = 0xFFC62828.toInt()
        private const val AMBER_ON_DARK = 0xFFFFC54D.toInt()
        private const val AMBER_ON_LIGHT = 0xFFB26A00.toInt()
    }
}
