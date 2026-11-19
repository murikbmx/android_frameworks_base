/*
 * Copyright (C) 2026 The LineageOS Project
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

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import com.android.systemui.flashlight.domain.interactor.FlashlightInteractor
import com.android.systemui.flashlight.shared.model.FlashlightModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import kotlin.math.ceil

private const val FLASHLIGHT_SPEC = "flashlight"

/** Darkens the part of an active flashlight tile above the current level. */
private val UnfilledOverlay = Color.Black.copy(alpha = 0.32f)

/** Live drag position (0..1) while the user drags the tile, NaN otherwise. */
class FlashlightSliderState(val interactor: FlashlightInteractor) {
    val dragFraction: MutableFloatState = mutableFloatStateOf(Float.NaN)
}

/**
 * Returns the flashlight level model for the flashlight tile when the torch supports strength
 * levels, or null for every other tile and for on/off-only torches.
 */
@Composable
fun rememberFlashlightLevel(spec: TileSpec): State<FlashlightModel.Available.Level?> {
    val isFlashlight = spec.spec == FLASHLIGHT_SPEC
    val interactor by FlashlightInteractor.instance.collectAsState()
    return produceState<FlashlightModel.Available.Level?>(null, isFlashlight, interactor) {
        val i = interactor
        if (!isFlashlight || i == null) {
            value = null
            return@produceState
        }
        i.state.collect { value = it as? FlashlightModel.Available.Level }
    }
}

@Composable
fun rememberFlashlightSliderState(): FlashlightSliderState? {
    val interactor by FlashlightInteractor.instance.collectAsState()
    val i = interactor ?: return null
    return remember(i) { FlashlightSliderState(i) }
}

/**
 * Horizontal drag across a large flashlight tile sets the torch strength; dragging to the very
 * start turns the torch off. Vertical drags are left to the shade and taps to the tile.
 */
@Composable
fun Modifier.flashlightLevelDrag(
    state: FlashlightSliderState,
    level: FlashlightModel.Available.Level,
): Modifier {
    val currentLevel by rememberUpdatedState(level)
    val view = LocalView.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return pointerInput(state, rtl) {
        var lastSent = -1
        fun fractionAt(x: Float): Float {
            val f = (x / size.width).coerceIn(0f, 1f)
            return if (rtl) 1f - f else f
        }
        fun levelFor(fraction: Float): Int =
            ceil(fraction * currentLevel.max).toInt().coerceIn(0, currentLevel.max)
        fun apply(fraction: Float, temporary: Boolean) {
            val target = levelFor(fraction)
            if (target == lastSent && temporary) return
            if (target != lastSent) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            lastSent = target
            if (target == 0) {
                if (currentLevel.enabled || !temporary) state.interactor.setEnabled(false)
            } else if (temporary) {
                state.interactor.setTemporaryLevel(target)
            } else {
                state.interactor.setLevel(target)
            }
        }
        detectHorizontalDragGestures(
            onDragStart = { offset ->
                lastSent = if (currentLevel.enabled) currentLevel.level else 0
                state.dragFraction.floatValue = fractionAt(offset.x)
                apply(state.dragFraction.floatValue, temporary = true)
            },
            onDragEnd = {
                apply(state.dragFraction.floatValue, temporary = false)
                state.dragFraction.floatValue = Float.NaN
            },
            onDragCancel = {
                apply(state.dragFraction.floatValue, temporary = false)
                state.dragFraction.floatValue = Float.NaN
            },
            onHorizontalDrag = { change, _ ->
                change.consume()
                state.dragFraction.floatValue = fractionAt(change.position.x)
                apply(state.dragFraction.floatValue, temporary = true)
            },
        )
    }
}

/** Draws the level fill: the part above the level is dimmed while the torch is on. */
@Composable
fun BoxScope.FlashlightLevelFill(
    state: FlashlightSliderState,
    level: FlashlightModel.Available.Level,
) {
    val drag = state.dragFraction.floatValue
    val target =
        when {
            !drag.isNaN() -> drag
            level.enabled -> level.level.toFloat() / level.max
            else -> 0f
        }
    val shown by
        animateFloatAsState(
            targetValue = target,
            animationSpec =
                if (drag.isNaN()) spring()
                else snap(),
            label = "FlashlightLevelFill",
        )
    val visible = level.enabled || !drag.isNaN()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        Modifier.matchParentSize().drawBehind {
            if (!visible) return@drawBehind
            val filled = size.width * shown
            val unfilled = size.width - filled
            if (unfilled <= 0f) return@drawBehind
            drawRect(
                color = UnfilledOverlay,
                topLeft = Offset(if (rtl) 0f else filled, 0f),
                size = Size(unfilled, size.height),
            )
        }
    )
}
