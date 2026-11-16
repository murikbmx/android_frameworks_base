/*
 * SPDX-FileCopyrightText: 2015 The CyanogenMod Project
 * SPDX-FileCopyrightText: 2017-2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.lineage.visualizer

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.session.PlaybackState
import android.os.Handler
import android.os.UserHandle
import android.util.Log
import android.view.View
import com.android.internal.graphics.ColorUtils
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.NotificationShadeWindowView
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.policy.KeyguardStateController
import dagger.Lazy
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import lineageos.providers.LineageSettings

/**
 * Lockscreen music visualizer: shows the spectrum of the playing music along the bottom of the
 * lockscreen, colored after the album art or the wallpaper. The audio output is only captured
 * while the bars are on screen, so the device can still sleep during offloaded playback.
 */
@SysUISingleton
class VisualizerController
@Inject
constructor(
    private val context: Context,
    private val shadeWindowView: Lazy<NotificationShadeWindowView>,
    private val mediaManager: NotificationMediaManager,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val userTracker: UserTracker,
    private val shadeInteractor: Lazy<ShadeInteractor>,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainHandler: Handler,
    @Main private val mainExecutor: Executor,
    @Background private val bgHandler: Handler,
) : CoreStartable {

    private val resolver = context.contentResolver
    private val wallpaperManager = context.getSystemService(WallpaperManager::class.java)

    private var view: VisualizerView? = null

    // Only touched on the background thread
    private var visualizer: Visualizer? = null

    private var enabled = false
    private var screenOn = true
    private var playing = false
    private var artwork: Bitmap? = null
    private var linked = false
    private var shadeExpansion = 0f

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                readEnabled()
                update()
            }
        }

    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                screenOn = intent.action == Intent.ACTION_SCREEN_ON
                update()
            }
        }

    private val mediaListener =
        object : NotificationMediaManager.MediaListener {
            override fun onPrimaryMetadataOrStateChanged(metadata: MediaMetadata?, state: Int) {
                playing = state == PlaybackState.STATE_PLAYING
                val art =
                    metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                if (art !== artwork) {
                    artwork = art
                    updateColor()
                }
                update()
            }
        }

    private val stateListener =
        object : StatusBarStateController.StateListener {
            override fun onStateChanged(newState: Int) = update()

            override fun onDozingChanged(isDozing: Boolean) = update()
        }

    private val keyguardCallback =
        object : KeyguardStateController.Callback {
            // Also called when the keyguard gets occluded
            override fun onKeyguardShowingChanged() = update()
        }

    private val wallpaperListener =
        WallpaperManager.OnColorsChangedListener { _, _ -> if (artwork == null) updateColor() }

    private val userCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                readEnabled()
                updateColor()
                update()
            }
        }

    private val captureListener =
        object : Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, rate: Int) {}

            override fun onFftDataCapture(v: Visualizer, fft: ByteArray, samplingRate: Int) {
                val data = fft.copyOf()
                mainHandler.post { view?.onFftData(data) }
            }
        }

    override fun start() {
        // The shade window is set up by other CoreStartables, look it up once they are done
        mainHandler.post { attach() }
    }

    private fun attach() {
        view = shadeWindowView.get().findViewById(R.id.lockscreen_visualizer)
        if (view == null) {
            Log.w(TAG, "No visualizer view in the shade window")
            return
        }
        resolver.registerContentObserver(
            LineageSettings.Secure.getUriFor(LineageSettings.Secure.LOCKSCREEN_VISUALIZER_ENABLED),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
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
        statusBarStateController.addCallback(stateListener)
        keyguardStateController.addCallback(keyguardCallback)
        userTracker.addCallback(userCallback, mainExecutor)
        runCatching { wallpaperManager?.addOnColorsChangedListener(wallpaperListener, mainHandler) }
            .onFailure { Log.w(TAG, "Unable to track the wallpaper colors", it) }
        mediaManager.addCallback(mediaListener)
        // The shade is translucent: fade the bars out while it is pulled down over them
        applicationScope.launch {
            shadeInteractor.get().anyExpansion.collect { expansion ->
                val wasOpen = shadeExpansion >= 1f
                shadeExpansion = expansion
                view?.transitionAlpha = (1f - expansion * SHADE_FADE_SPEED).coerceIn(0f, 1f)
                if ((expansion >= 1f) != wasOpen) update()
            }
        }
        readEnabled()
        updateColor()
        update()
    }

    private fun readEnabled() {
        enabled =
            LineageSettings.Secure.getIntForUser(
                resolver,
                LineageSettings.Secure.LOCKSCREEN_VISUALIZER_ENABLED,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
    }

    /** On the lockscreen, not the shade pulled down over it from the notifications. */
    private fun onKeyguard() = statusBarStateController.state == StatusBarState.KEYGUARD

    private fun shouldShow() =
        enabled &&
            playing &&
            screenOn &&
            onKeyguard() &&
            shadeExpansion < 1f &&
            !statusBarStateController.isDozing &&
            !keyguardStateController.isOccluded

    private fun update() {
        val v = view ?: return
        val show = shouldShow()
        if (DEBUG) {
            Log.d(TAG, "update: show=$show enabled=$enabled playing=$playing screenOn=$screenOn " +
                "state=${statusBarStateController.state} " +
                "dozing=${statusBarStateController.isDozing} " +
                "occluded=${keyguardStateController.isOccluded} linked=$linked")
        }
        if (show) {
            v.visibility = View.VISIBLE
            if (!linked) {
                linked = true
                bgHandler.post(::link)
            }
            v.start()
        } else if (linked) {
            // Without a screen there is nothing to fade
            v.stop(animate = screenOn) {
                if (!shouldShow()) {
                    linked = false
                    bgHandler.post(::unlink)
                    v.visibility = View.GONE
                }
            }
        } else {
            v.visibility = View.GONE
        }
    }

    private fun link() {
        if (visualizer != null) return
        visualizer =
            try {
                // Session 0 is the output mix
                Visualizer(0).apply {
                    enabled = false
                    captureSize = Visualizer.getCaptureSizeRange()[0]
                    setDataCaptureListener(
                        captureListener,
                        Visualizer.getMaxCaptureRate(),
                        false,
                        true,
                    )
                    enabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start the visualizer", e)
                null
            }
    }

    private fun unlink() {
        visualizer?.let {
            it.enabled = false
            it.release()
        }
        visualizer = null
    }

    /** Colors the bars after the album art, or the lockscreen wallpaper without one. */
    private fun updateColor() {
        val art = artwork
        bgHandler.post {
            val colors =
                runCatching {
                        if (art != null && !art.isRecycled) {
                            WallpaperColors.fromBitmap(art)
                        } else {
                            wallpaperManager?.getWallpaperColors(WallpaperManager.FLAG_LOCK)
                                ?: wallpaperManager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                        }
                    }
                    .onFailure { Log.w(TAG, "Unable to get the colors", it) }
                    .getOrNull()
            val color = lighten(colors?.primaryColor?.toArgb() ?: Color.WHITE)
            mainHandler.post {
                // A newer artwork arrived meanwhile
                if (art === artwork) view?.setColor(color)
            }
        }
    }

    /**
     * Mixes the color with white: the bars stand out from the album art they are colored after
     * and from the dark lockscreen.
     */
    private fun lighten(color: Int) = ColorUtils.blendARGB(color, Color.WHITE, WHITE_AMOUNT)

    companion object {
        private const val TAG = "VisualizerController"
        private const val DEBUG = false
        private const val WHITE_AMOUNT = 0.45f
        private const val SHADE_FADE_SPEED = 2f
    }
}
