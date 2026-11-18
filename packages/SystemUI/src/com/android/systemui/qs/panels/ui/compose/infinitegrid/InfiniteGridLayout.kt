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
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.grid.ui.compose.VerticalSpannedGrid
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.panels.domain.interactor.MediaTileInteractor
import com.android.systemui.qs.panels.shared.model.MediaTileSize
import com.android.systemui.qs.panels.shared.model.QSGridItem
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.shared.model.groupWithMediaTile
import com.android.systemui.qs.panels.ui.compose.EditTileListState
import com.android.systemui.qs.panels.ui.compose.PaginatableGridLayout
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.compose.bounceableInfo
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconTilesViewModel
import com.android.systemui.qs.panels.ui.viewmodel.InfiniteGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TextFeedbackContentViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.ui.QuickSettings.Elements.toElementKey
import com.android.systemui.res.R
import com.android.systemui.shade.shared.flag.DualShadeFlag
import javax.inject.Inject
import kotlinx.coroutines.launch

@SysUISingleton
class InfiniteGridLayout
@Inject
constructor(
    private val detailsViewModel: DetailsViewModel,
    private val iconTilesViewModel: IconTilesViewModel,
    override val viewModelFactory: InfiniteGridViewModel.Factory,
    private val textFeedbackContentViewModelFactory: TextFeedbackContentViewModel.Factory,
    private val tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
    private val mediaTileInteractor: MediaTileInteractor,
) : PaginatableGridLayout {

    @Composable
    override fun ContentScope.TileGrid(
        tiles: List<TileViewModel>,
        modifier: Modifier,
        listening: () -> Boolean,
        enableRevealEffect: Boolean,
    ) {
        val viewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.TileGrid") {
                viewModelFactory.create()
            }

        val context = LocalContext.current
        val textFeedbackViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.TileGrid", key = context) {
                textFeedbackContentViewModelFactory.create(context)
            }

        val columns = viewModel.columnsWithMediaViewModel.columns
        val largeTilesSpan = viewModel.columnsWithMediaViewModel.largeSpan
        val largeTiles by viewModel.iconTilesViewModel.largeTilesState
        // Tiles or largeTiles may be updated while this is composed, so listen to any changes
        val sizedTiles =
            remember(tiles, largeTiles, largeTilesSpan) {
                tiles.map {
                    SizedTileImpl(it, if (largeTiles.contains(it.spec)) largeTilesSpan else 1)
                }
            }
        val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        val mediaSize by mediaTileInteractor.size.collectAsStateWithLifecycle()
        val items =
            remember(sizedTiles, columns, mediaSize) {
                groupWithMediaTile(sizedTiles, columns, mediaSize) {
                    it.spec == MediaTileInteractor.SPEC
                }
            }
        val bounceables =
            remember(sizedTiles) { List(sizedTiles.size) { BounceableTileViewModel() } }
        val indexOfSpec =
            remember(sizedTiles) {
                sizedTiles.withIndex().associate { (index, tile) -> tile.tile.spec to index }
            }
        val spans by
            remember(items, columns) {
                derivedStateOf {
                    items.fastMap {
                        when (it) {
                            is QSGridItem.Single -> it.sizedTile.width
                            is QSGridItem.MediaBlock -> columns
                        }
                    }
                }
            }
        val columnSpacing = dimensionResource(R.dimen.qs_tile_margin_horizontal)
        val rowSpacing = dimensionResource(R.dimen.qs_tile_margin_vertical)
        val tileHeight = CommonTileDefaults.TileHeight

        @Composable
        fun GridTile(sized: SizedTile<TileViewModel>, column: Int, first: Boolean, last: Boolean) {
            val index = indexOfSpec[sized.tile.spec] ?: return
            Element(sized.tile.spec.toElementKey(), Modifier) {
                Tile(
                    tile = sized.tile,
                    iconOnly = iconTilesViewModel.isIconTile(sized.tile.spec),
                    squishiness = { squishiness },
                    tileHapticsViewModelFactory = tileHapticsViewModelFactory,
                    coroutineScope = scope,
                    bounceableInfo =
                        bounceables.bounceableInfo(
                            sized,
                            index = index,
                            column = column,
                            columns = columns,
                            isFirstInRow = first,
                            isLastInRow = last,
                        ),
                    detailsViewModel = detailsViewModel,
                    isVisible = listening,
                    requestToggleTextFeedback = textFeedbackViewModel::requestShowFeedback,
                    enableRevealEffect = enableRevealEffect,
                )
            }
        }

        VerticalSpannedGrid(
            columns = columns,
            columnSpacing = columnSpacing,
            rowSpacing = rowSpacing,
            spans = spans,
            keys = { items[it].tiles.first().spec },
            modifier = modifier,
        ) { spanIndex, column, isFirstInColumn, isLastInColumn ->
            when (val item = items[spanIndex]) {
                is QSGridItem.Single ->
                    GridTile(item.sizedTile, column, isFirstInColumn, isLastInColumn)
                is QSGridItem.MediaBlock ->
                    BoxWithConstraints(Modifier.verticalSquish { squishiness }) {
                        val cell = (maxWidth - columnSpacing * (columns - 1)) / columns
                        val blockHeight = tileHeight * item.rows + rowSpacing * (item.rows - 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(columnSpacing)) {
                            val player: @Composable () -> Unit = {
                                Element(item.media.spec.toElementKey(), Modifier) {
                                    MediaPlayerTileContent(
                                        interactor = mediaTileInteractor,
                                        size = mediaSize,
                                        modifier =
                                            Modifier.size(
                                                width =
                                                    cell * item.playerColumns +
                                                        columnSpacing * (item.playerColumns - 1),
                                                height = blockHeight,
                                            ),
                                    )
                                }
                            }
                            val sideTiles: @Composable () -> Unit = {
                                if (item.sideColumns > 0) {
                                    Column(verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
                                        item.sideRows.forEach { rowTiles ->
                                            Row(
                                                horizontalArrangement =
                                                    Arrangement.spacedBy(columnSpacing)
                                            ) {
                                                var column = if (item.playerAtEnd) 0 else item.playerColumns
                                                rowTiles.forEachIndexed { i, sized ->
                                                    val tileColumn = column
                                                    column += sized.width
                                                    Box(
                                                        Modifier.width(
                                                            cell * sized.width +
                                                                columnSpacing * (sized.width - 1)
                                                        )
                                                    ) {
                                                        GridTile(
                                                            sized,
                                                            column = tileColumn,
                                                            first = false,
                                                            last = i == rowTiles.size - 1,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            // The player is on the end side when its start edge was moved
                            if (item.playerAtEnd) {
                                sideTiles()
                                player()
                            } else {
                                player()
                                sideTiles()
                            }
                        }
                    }
            }
        }

        TileListener(tiles, listening)
    }

    @Composable
    override fun EditTileGrid(
        tiles: List<EditTileViewModel>,
        modifier: Modifier,
        onAddTile: (TileSpec, Int) -> Unit,
        onRemoveTile: (TileSpec) -> Unit,
        onSetTiles: (List<TileSpec>) -> Unit,
        onStopEditing: () -> Unit,
    ) {
        val viewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.EditTileGrid") {
                viewModelFactory.create()
            }
        val columnsViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.EditTileGrid") {
                viewModel.columnsWithMediaViewModelFactory.createWithoutMediaTracking()
            }
        val snapshotViewModel =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.snapshotViewModelFactory.create()
            }
        val topBarActionsViewModel =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.editTopBarActionsViewModelFactory.create()
            }
        val scrollState = rememberScrollState()
        val coroutineScope = rememberCoroutineScope()
        val dialogDelegate =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.resetDialogDelegateFactory.create {
                    // Clear the stack of snapshots on reset
                    snapshotViewModel.clearStack()

                    // Automatically scroll to the top on reset
                    coroutineScope.launch { scrollState.animateScrollTo(0) }
                }
            }
        val showDualShadeSetting =
            DualShadeFlag.isEnabled &&
                LocalResources.current.getBoolean(
                    com.android.settingslib.R.bool.config_useDualShadeSetting
                )
        val actions =
            remember(topBarActionsViewModel, showDualShadeSetting) {
                topBarActionsViewModel.actions(showDualShadeSetting).toMutableStateList()
            }
        val columns = columnsViewModel.columns
        val largeTilesSpan = columnsViewModel.largeSpan
        val largeTiles by viewModel.iconTilesViewModel.largeTilesState

        val currentTiles by rememberUpdatedState(tiles.filter { it.isCurrent })
        val listState =
            remember(columns, largeTilesSpan) {
                EditTileListState(
                    currentTiles,
                    largeTiles,
                    columns = columns,
                    largeTilesSpan = largeTilesSpan,
                    initialMediaWidth = mediaTileInteractor.size.value.columns,
                )
            }
        LaunchedEffect(currentTiles, largeTiles) { listState.updateTiles(currentTiles, largeTiles) }

        CompositionLocalProvider(LocalEditMediaTileInteractor provides mediaTileInteractor) {
            DefaultEditTileGrid(
                listState = listState,
                allTiles = tiles,
                modifier = modifier,
                scrollState = scrollState,
                snapshotViewModel = snapshotViewModel,
                onStopEditing = onStopEditing,
                topBarActions = actions,
            ) { action ->
                // Opening the dialog doesn't require a snapshot
                if (action != EditAction.ResetGrid) {
                    snapshotViewModel.takeSnapshot(currentTiles.map { it.tileSpec }, largeTiles)
                }

                when (action) {
                    is EditAction.AddTile -> {
                        onAddTile(action.tileSpec, listState.tileSpecs().size)
                    }
                    is EditAction.InsertTile -> {
                        onAddTile(action.tileSpec, action.position)
                    }
                    is EditAction.RemoveTile -> {
                        onRemoveTile(action.tileSpec)
                    }
                    EditAction.ResetGrid -> {
                        dialogDelegate.showDialog()
                    }
                    is EditAction.ResizeTile -> {
                        iconTilesViewModel.resize(action.tileSpec, action.toIcon)
                    }
                    is EditAction.ResizeMediaTile -> {
                        mediaTileInteractor.setSize(MediaTileSize.fromColumns(action.width))
                    }
                    is EditAction.SetTiles -> {
                        onSetTiles(action.tileSpecs)
                    }
                }
            }
        }
    }
}
