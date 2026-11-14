/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.domain.interactor

import android.app.PendingIntent
import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.panels.shared.model.MediaTileSize
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.MediaPlayerTile
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * State of the quick settings music player tile: the media to show, the tile size and whether the
 * tile is in the current tiles (in which case the notification-style media player is hidden).
 */
@SysUISingleton
class MediaTileInteractor
@Inject
constructor(
    @Application private val context: Context,
    private val mediaDataManager: MediaDataManager,
    private val activityStarter: ActivityStarter,
    currentTilesInteractor: CurrentTilesInteractor,
    @Background bgScope: CoroutineScope,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _size = MutableStateFlow(MediaTileSize.DEFAULT)
    val size: StateFlow<MediaTileSize> = _size.asStateFlow()

    private val entries = LinkedHashMap<String, MediaData>()
    private val _media = MutableStateFlow<MediaData?>(null)

    /** The player to show: playing first, then the most recently active, then resumable ones. */
    val media: StateFlow<MediaData?> = _media.asStateFlow()

    val isTileAdded: StateFlow<Boolean> =
        currentTilesInteractor.currentTiles
            .map { tiles -> tiles.any { it.spec == SPEC } }
            .stateIn(bgScope, SharingStarted.Eagerly, false)

    private val listener =
        object : MediaDataManager.Listener {
            override fun onMediaDataLoaded(
                key: String,
                oldKey: String?,
                data: MediaData,
                immediately: Boolean,
            ) {
                synchronized(entries) {
                    if (oldKey != null && oldKey != key) entries.remove(oldKey)
                    entries[key] = data
                    publish()
                }
            }

            override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
                synchronized(entries) {
                    entries.remove(key)
                    publish()
                }
            }
        }

    init {
        _size.value = runCatching { MediaTileSize.fromKey(prefs.getString(KEY_SIZE, null)) }
            .getOrDefault(MediaTileSize.DEFAULT)
        mediaDataManager.addListener(listener)
    }

    private fun publish() {
        val all = entries.values
        _media.value =
            all.filter { it.isPlaying == true }.maxByOrNull { it.lastActive }
                ?: all.filter { it.active }.maxByOrNull { it.lastActive }
                ?: all.maxByOrNull { it.lastActive }
    }

    fun setSize(size: MediaTileSize) {
        _size.value = size
        prefs.edit().putString(KEY_SIZE, size.key).apply()
    }

    fun openPlayer(data: MediaData) {
        val intent: PendingIntent = data.clickIntent ?: return
        activityStarter.postStartActivityDismissingKeyguard(intent)
    }

    fun playPause(data: MediaData) {
        val semantic = data.semanticActions
        when {
            semantic?.playOrPause?.action != null -> semantic.playOrPause.action.run()
            data.resumeAction != null && data.isPlaying != true -> data.resumeAction?.run()
            else -> compactAction(data, 1)
        }
    }

    fun previous(data: MediaData) {
        data.semanticActions?.prevOrCustom?.action?.let { return it.run() }
        compactAction(data, 0)
    }

    fun next(data: MediaData) {
        data.semanticActions?.nextOrCustom?.action?.let { return it.run() }
        compactAction(data, 2)
    }

    private fun compactAction(data: MediaData, position: Int) {
        val index = data.actionsToShowInCompact.getOrNull(position) ?: return
        runCatching { data.actions.getOrNull(index)?.actionIntent?.send() }
    }

    companion object {
        val SPEC: TileSpec = TileSpec.create(MediaPlayerTile.TILE_SPEC)
        private const val PREFS = "qs_media_tile"
        private const val KEY_SIZE = "size"
    }
}
