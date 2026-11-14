/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.app.WallpaperColors
import android.content.Context
import android.content.theming.ThemeStyle
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.monet.ColorScheme
import com.android.systemui.qs.panels.domain.interactor.MediaTileInteractor
import com.android.systemui.qs.panels.shared.model.MediaTileSize
import com.android.systemui.res.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Dimensions of the player content, scaled with the tile size. */
private data class PlayerMetrics(
    val padding: Dp,
    val title: TextUnit,
    val artist: TextUnit,
    val side: Dp,
    val icon: Dp,
    val playWidth: Dp,
    val playHeight: Dp,
    val playIcon: Dp,
    val buttonGap: Dp,
    val showProgress: Boolean,
    val inline: Boolean,
)

private fun metricsFor(size: MediaTileSize) =
    when (size) {
        MediaTileSize.S2x2 ->
            PlayerMetrics(12.dp, 15.sp, 12.sp, 40.dp, 20.dp, 56.dp, 44.dp, 24.dp, 0.dp, false, false)
        MediaTileSize.S3x2 ->
            PlayerMetrics(16.dp, 18.sp, 14.sp, 44.dp, 22.dp, 68.dp, 50.dp, 26.dp, 22.dp, true, false)
        MediaTileSize.S4x2 ->
            PlayerMetrics(18.dp, 20.sp, 15.sp, 48.dp, 24.dp, 76.dp, 54.dp, 28.dp, 32.dp, true, false)
    }

private data class Artwork(val bitmap: ImageBitmap?, val accent: Color?, val onAccent: Color?)

private suspend fun loadArtwork(context: Context, data: MediaData?): Artwork =
    withContext(Dispatchers.Default) {
        val icon = data?.artwork ?: return@withContext Artwork(null, null, null)
        runCatching {
                val drawable = icon.loadDrawable(context) ?: return@runCatching null
                val bitmap = drawable.toBitmap(ART_SIZE, ART_SIZE)
                val scheme =
                    ColorScheme(WallpaperColors.fromBitmap(bitmap), true, ThemeStyle.CONTENT)
                Artwork(
                    bitmap.asImageBitmap(),
                    Color(scheme.accent1.s200),
                    Color(scheme.accent1.s800),
                )
            }
            .getOrNull() ?: Artwork(null, null, null)
    }

private const val ART_SIZE = 512

/** Progress of the session in [0, 1], or null when unknown. */
@Composable
private fun rememberProgress(context: Context, data: MediaData?, enabled: Boolean): Float? {
    val token = data?.token
    var progress by remember(token) { mutableStateOf<Float?>(null) }
    LaunchedEffect(token, enabled) {
        if (!enabled || token == null) {
            progress = null
            return@LaunchedEffect
        }
        val controller = runCatching { MediaController(context, token) }.getOrNull()
        while (controller != null) {
            val state = controller.playbackState
            val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            progress =
                if (state == null || duration <= 0L) {
                    null
                } else {
                    var position = state.position
                    if (state.state == PlaybackState.STATE_PLAYING) {
                        val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                        position += (elapsed * state.playbackSpeed).toLong()
                    }
                    (position.toFloat() / duration).coerceIn(0f, 1f)
                }
            delay(1000)
        }
    }
    return progress
}

private val PrevIcon = vectorIcon("M6,6h2v12H6zM9.5,12l8.5,6V6z")
private val NextIcon = vectorIcon("M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
private val PlayIcon = vectorIcon("M8,5v14l11,-7z")
private val PauseIcon = vectorIcon("M6,19h4V5H6v14zM14,5v14h4V5h-4z")
private val NoteIcon =
    vectorIcon(
        "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55C7.79,13 6,14.79 6,17s1.79,4 4,4 4,-1.79 " +
            "4,-4V7h4V3h-6z"
    )

private fun vectorIcon(path: String): ImageVector =
    ImageVector.Builder(
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        .addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.White),
        )
        .build()

/**
 * The music player tile: artwork filling the tile, a dark scrim under the text and the buttons so
 * they stay readable on any artwork, title, artist and previous / play-pause / next buttons.
 * The size is changed in the quick settings edit mode, like other tiles.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaPlayerTileContent(
    interactor: MediaTileInteractor,
    size: MediaTileSize,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val data by interactor.media.collectAsStateWithLifecycle()
    val metrics = metricsFor(size)
    val artwork by
        produceState(Artwork(null, null, null), data?.artwork, data?.packageName) {
            value = loadArtwork(context, data)
        }
    val progress = rememberProgress(context, data, metrics.showProgress && data != null)
    val playing = data?.isPlaying == true

    val shape = RoundedCornerShape(if (size.rows > 1) 32.dp else 36.dp)
    val accent = artwork.accent ?: MaterialTheme.colorScheme.primary
    val onAccent = artwork.onAccent ?: MaterialTheme.colorScheme.onPrimary
    val hasArt = artwork.bitmap != null
    val contentColor = if (hasArt) Color.White else MaterialTheme.colorScheme.onSurface
    val sideButtonColor =
        if (hasArt) Color.Black.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surfaceContainerHighest
    val textShadow =
        if (hasArt) Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 6f) else null
    val title = data?.song?.toString() ?: stringResource(R.string.qs_media_tile_nothing_playing)
    val artist = data?.artist?.toString() ?: data?.app.orEmpty()

    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = { data?.let(interactor::openPlayer) })
    ) {
        val bitmap = artwork.bitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize()
                    .background(
                        if (metrics.inline) {
                            Brush.horizontalGradient(
                                0f to Color.Black.copy(alpha = 0.82f),
                                0.55f to Color.Black.copy(alpha = 0.6f),
                                1f to Color.Black.copy(alpha = 0.7f),
                            )
                        } else {
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.25f to Color.Transparent,
                                0.6f to Color.Black.copy(alpha = 0.55f),
                                1f to Color.Black.copy(alpha = 0.85f),
                            )
                        }
                    )
            )
        } else if (!metrics.inline) {
            Icon(
                imageVector = NoteIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.align(Alignment.TopEnd).padding(metrics.padding).size(32.dp),
            )
        }

        val texts: @Composable (Modifier) -> Unit = { textModifier ->
            Column(textModifier) {
                Text(
                    text = title,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style =
                        TextStyle(
                            fontSize = metrics.title,
                            fontWeight = FontWeight.SemiBold,
                            shadow = textShadow,
                        ),
                )
                if (artist.isNotEmpty()) {
                    Text(
                        text = artist,
                        color = contentColor.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontSize = metrics.artist, shadow = textShadow),
                    )
                }
            }
        }

        val buttons: @Composable () -> Unit = {
            Row(
                horizontalArrangement =
                    if (metrics.buttonGap == 0.dp) Arrangement.SpaceBetween
                    else Arrangement.spacedBy(metrics.buttonGap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (metrics.inline) Modifier else Modifier.fillMaxWidth(),
            ) {
                PlayerButton(
                    icon = PrevIcon,
                    description = stringResource(R.string.qs_media_tile_previous),
                    width = metrics.side,
                    height = metrics.side,
                    iconSize = metrics.icon,
                    shape = CircleShape,
                    background = sideButtonColor,
                    tint = contentColor,
                    enabled = data != null,
                ) {
                    data?.let(interactor::previous)
                }
                val playRadius by animateDpAsState(if (playing) 16.dp else metrics.playHeight / 2)
                PlayerButton(
                    icon = if (playing) PauseIcon else PlayIcon,
                    description =
                        stringResource(
                            if (playing) R.string.qs_media_tile_pause
                            else R.string.qs_media_tile_play
                        ),
                    width = metrics.playWidth,
                    height = metrics.playHeight,
                    iconSize = metrics.playIcon,
                    shape = RoundedCornerShape(playRadius),
                    background = accent,
                    tint = onAccent,
                    enabled = data != null,
                ) {
                    data?.let(interactor::playPause)
                }
                PlayerButton(
                    icon = NextIcon,
                    description = stringResource(R.string.qs_media_tile_next),
                    width = metrics.side,
                    height = metrics.side,
                    iconSize = metrics.icon,
                    shape = CircleShape,
                    background = sideButtonColor,
                    tint = contentColor,
                    enabled = data != null,
                ) {
                    data?.let(interactor::next)
                }
            }
        }

        if (metrics.inline) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier =
                    Modifier.fillMaxSize().padding(start = 20.dp, end = metrics.padding),
            ) {
                texts(Modifier.weight(1f))
                buttons()
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                modifier = Modifier.fillMaxSize().padding(metrics.padding),
            ) {
                texts(Modifier.fillMaxWidth())
                if (progress != null) {
                    Box(
                        Modifier.fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(contentColor.copy(alpha = 0.3f))
                    ) {
                        Box(
                            Modifier.fillMaxWidth(progress)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(accent)
                        )
                    }
                }
                buttons()
            }
        }

    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerButton(
    icon: ImageVector,
    description: String,
    width: Dp,
    height: Dp,
    iconSize: Dp,
    shape: androidx.compose.ui.graphics.Shape,
    background: Color,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(width, height)
                .clip(shape)
                .background(background)
                .semantics { contentDescription = description }
                .combinedClickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(iconSize),
        )
    }
}
