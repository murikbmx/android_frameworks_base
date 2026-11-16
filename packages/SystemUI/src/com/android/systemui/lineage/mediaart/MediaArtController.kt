/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.lineage.mediaart

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.UserHandle
import android.util.Log
import android.view.View
import android.widget.ImageView
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.NotificationShadeWindowView
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.policy.KeyguardStateController
import dagger.Lazy
import java.util.concurrent.Executor
import javax.inject.Inject
import lineageos.providers.LineageSettings

/**
 * Shows the album art of the playing music as the lockscreen wallpaper, blurred and dimmed. It
 * fades out when the music is paused and comes back when it plays again.
 */
@SysUISingleton
class MediaArtController
@Inject
constructor(
    private val shadeWindowView: Lazy<NotificationShadeWindowView>,
    private val mediaManager: NotificationMediaManager,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val userTracker: UserTracker,
    context: Context,
    @Main private val mainHandler: Handler,
    @Main private val mainExecutor: Executor,
) : CoreStartable {

    private val resolver = context.contentResolver
    private val resources = context.resources

    private var view: ImageView? = null
    private var enabled = false
    private var playing = false
    private var artwork: Bitmap? = null
    private var shownArtwork: Bitmap? = null
    private var showing = false

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                readEnabled()
                update()
            }
        }

    private val mediaListener =
        object : NotificationMediaManager.MediaListener {
            override fun onPrimaryMetadataOrStateChanged(metadata: MediaMetadata?, state: Int) {
                playing = state == PlaybackState.STATE_PLAYING
                // Keep the last art while paused, so playing again needs no new metadata
                val art =
                    metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                if (metadata != null) artwork = art
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

    private val userCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                readEnabled()
                update()
            }
        }

    override fun start() {
        // The shade window is set up by other CoreStartables, look it up once they are done
        mainHandler.post { attach() }
    }

    private fun attach() {
        view = shadeWindowView.get().findViewById(R.id.lockscreen_media_art)
        val v = view
        if (v == null) {
            Log.w(TAG, "No media art view in the shade window")
            return
        }
        // Album art is square and often small: stretched over the tall screen it gets cropped
        // and blurry, so it is shown as a soft blurred backdrop, dimmed for the white text
        val radius = BLUR_RADIUS_DP * resources.displayMetrics.density
        v.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        v.setColorFilter(Color.argb(DIM_ALPHA, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
        resolver.registerContentObserver(
            LineageSettings.Secure.getUriFor(LineageSettings.Secure.LOCKSCREEN_MEDIA_METADATA),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        statusBarStateController.addCallback(stateListener)
        keyguardStateController.addCallback(keyguardCallback)
        userTracker.addCallback(userCallback, mainExecutor)
        mediaManager.addCallback(mediaListener)
        readEnabled()
        update()
    }

    private fun readEnabled() {
        enabled =
            LineageSettings.Secure.getIntForUser(
                resolver,
                LineageSettings.Secure.LOCKSCREEN_MEDIA_METADATA,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
    }

    private fun shouldShow() =
        enabled &&
            playing &&
            artwork?.isRecycled == false &&
            statusBarStateController.state != StatusBarState.SHADE &&
            !statusBarStateController.isDozing &&
            !keyguardStateController.isOccluded

    private fun update() {
        val v = view ?: return
        val show = shouldShow()
        if (DEBUG) {
            Log.d(TAG, "update: show=$show enabled=$enabled playing=$playing art=$artwork " +
                "state=${statusBarStateController.state} " +
                "dozing=${statusBarStateController.isDozing} " +
                "occluded=${keyguardStateController.isOccluded}")
        }
        if (show) {
            val art = artwork!!
            if (art !== shownArtwork) {
                setArtwork(v, art, crossfade = showing)
                shownArtwork = art
            }
            if (!showing) {
                showing = true
                v.animate().cancel()
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).withEndAction(null).setDuration(FADE_MS)
            }
        } else if (showing) {
            showing = false
            v.animate().cancel()
            // Leaving the lockscreen into the always-on display or an app: no fade there
            val fade =
                !statusBarStateController.isDozing && !keyguardStateController.isOccluded
            val hide = Runnable {
                v.visibility = View.GONE
                v.setImageDrawable(null)
                shownArtwork = null
            }
            if (fade) {
                v.animate().alpha(0f).withEndAction(hide).setDuration(FADE_MS)
            } else {
                v.alpha = 0f
                hide.run()
            }
        }
    }

    /** Sets the art, crossfading from the previous one while shown. */
    private fun setArtwork(v: ImageView, art: Bitmap, crossfade: Boolean) {
        val drawable = BitmapDrawable(resources, art)
        val old: Drawable? = v.drawable
        if (!crossfade || old == null) {
            v.setImageDrawable(drawable)
            return
        }
        val from = (old as? TransitionDrawable)?.getDrawable(1) ?: old
        // Both layers get the same bounds: only crossfade art of the same shape
        if (from.intrinsicWidth * art.height != from.intrinsicHeight * art.width) {
            v.setImageDrawable(drawable)
            return
        }
        val transition =
            TransitionDrawable(arrayOf(from, drawable)).apply { isCrossFadeEnabled = true }
        v.setImageDrawable(transition)
        transition.startTransition(FADE_MS.toInt())
    }

    companion object {
        private const val TAG = "MediaArtController"
        private const val DEBUG = false
        private const val FADE_MS = 300L
        private const val BLUR_RADIUS_DP = 40f
        private const val DIM_ALPHA = 77 // 30%
    }
}
