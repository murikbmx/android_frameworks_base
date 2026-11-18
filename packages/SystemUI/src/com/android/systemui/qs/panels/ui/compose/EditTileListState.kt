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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.geometry.Offset
import com.android.systemui.qs.panels.shared.model.MediaTileSize
import com.android.systemui.qs.panels.shared.model.QSGridItem
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.groupWithMediaTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.compose.selection.PlacementEvent
import com.android.systemui.qs.panels.ui.model.GridCell
import com.android.systemui.qs.panels.ui.model.SpacerGridCell
import com.android.systemui.qs.panels.ui.model.TileGridCell
import com.android.systemui.qs.panels.ui.model.toGridCells
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.domain.interactor.MediaTileInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec

/**
 * Holds the state for the tiles to display and builds a grid using their sizes and the available
 * columns.
 */
class EditTileListState(
    initialTiles: List<EditTileViewModel>,
    initialLargeTiles: Set<TileSpec>,
    val columns: Int,
    val largeTilesSpan: Int,
    initialMediaWidth: Int = 0,
) : DragAndDropState {
    /** Width (in columns) of the music player tile, 0 if it has no special width. */
    var mediaWidth: Int = initialMediaWidth
        private set

    override var draggedCell by mutableStateOf<SizedTile<EditTileViewModel>?>(null)
        private set

    override var draggedPosition by mutableStateOf(Offset.Unspecified)
        private set

    override var dragType by mutableStateOf<DragType?>(null)
        private set

    // A dragged cell can be removed if it was added in the drag movement OR if it's marked as
    // removable
    override val isDraggedCellRemovable: Boolean
        get() = dragType == DragType.Add || draggedCell?.tile?.isRemovable ?: false

    override val dragInProgress: Boolean
        get() = draggedCell != null

    private val _tiles: SnapshotStateList<GridCell> =
        initialTiles.toGridCells(initialLargeTiles).toMutableStateList()
    val tiles: List<GridCell> = _tiles

    var largeTilesSpecs: Set<TileSpec> = initialLargeTiles
        private set

    /** Update the grid with this new list of tiles and new set of large tileSpecs. */
    fun updateTiles(tiles: List<EditTileViewModel>, largeTiles: Set<TileSpec>) {
        largeTilesSpecs = largeTiles
        tiles.toGridCells(largeTiles).let {
            _tiles.apply {
                clear()
                addAll(it)
            }
        }
    }

    fun tileSpecs(): List<TileSpec> {
        return _tiles.filterIsInstance<TileGridCell>().map { it.tile.tileSpec }
    }

    /**
     * Finds the closest tile to the given tileSpec.
     *
     * If the given tileSpec is found in the list of tileSpecs, this function returns the next tile
     * in the list. If the given tileSpec is the last tile in the list, this function returns the
     * previous tile in the list. If the given tileSpec is not found in the list of tileSpecs, this
     * function returns null.
     *
     * @param tileSpec The tileSpec to find the closest tile to.
     * @return The closest tile to the given tileSpec, or null if the given tileSpec is not found.
     */
    fun findNeighboringTile(tileSpec: TileSpec): TileSpec? {
        val specs = tileSpecs()
        return when (val index = specs.indexOf(tileSpec)) {
            -1 -> null
            specs.size - 1 -> { // Last element
                if (specs.size > 1) {
                    specs[index - 1]
                } else {
                    null
                }
            }
            else -> specs[index + 1] // Return next element
        }
    }

    private fun indexOf(tileSpec: TileSpec): Int {
        return _tiles.indexOfFirst { it is TileGridCell && it.tile.tileSpec == tileSpec }
    }

    /** Resize the tile corresponding to the [TileSpec] to [toIcon] */
    fun resizeTile(tileSpec: TileSpec, toIcon: Boolean) {
        val fromIndex = indexOf(tileSpec)
        if (fromIndex != INVALID_INDEX) {
            val cell = _tiles[fromIndex] as TileGridCell

            if (cell.isIcon == toIcon || isMedia(tileSpec)) return

            _tiles[fromIndex] = cell.copy(width = if (toIcon) 1 else largeTilesSpan)
            regenerateGrid(fromIndex)
        }
    }

    /** Set the width of the music player tile, in columns. */
    fun resizeMediaTile(width: Int) {
        mediaWidth = width
        val fromIndex = _tiles.indexOfFirst { it is TileGridCell && isMedia(it.tile.tileSpec) }
        if (fromIndex != INVALID_INDEX) {
            val cell = _tiles[fromIndex] as TileGridCell
            val newWidth = width.coerceIn(1, columns)
            if (cell.width == newWidth) return
            _tiles[fromIndex] = cell.copy(width = newWidth)
            regenerateGrid(fromIndex)
        }
    }

    private fun isMedia(spec: TileSpec) = spec == MediaTileInteractor.SPEC

    /**
     * The tiles quick settings shows next to the music player, placed on the cells of the player
     * row of this grid; null if there's no music player tile.
     */
    fun mediaRowGhosts(): MediaRowGhosts? {
        val cells = _tiles.toList()
        val media =
            cells.firstOrNull { it is TileGridCell && isMedia(it.tile.tileSpec) } as? TileGridCell
                ?: return null
        val block =
            groupWithMediaTile(
                    cells.filterIsInstance<TileGridCell>(),
                    columns,
                    MediaTileSize.fromColumns(media.width),
                ) {
                    isMedia(it.tileSpec)
                }
                .firstNotNullOfOrNull { it as? QSGridItem.MediaBlock<EditTileViewModel> }
                ?: return null
        // Grid columns next to the player, in order
        val sideColumns =
            (0 until columns).filter { it < media.column || it >= media.column + media.width }
        fun ghostsOf(row: Int): List<MediaGhost> {
            var position = 0
            return block.sideRows.getOrNull(row).orEmpty().mapNotNull { sized ->
                val column = sideColumns.getOrNull(position)
                position += sized.width
                column?.let { MediaGhost(sized.tile, it, sized.width) }
            }
        }
        val cellColumns = mutableMapOf<Int, IntRange>()
        // Free cells at the end of the row before the player, filled in quick settings
        val gapCellColumns = mutableMapOf<Int, IntRange>()
        var gapStart = -1
        var row = -1
        var column = 0
        cells.forEachIndexed { index, cell ->
            if (cell.row != row) {
                row = cell.row
                column = 0
            }
            val width = cell.span.currentLineSpan
            if (cell.row == media.row && cell !== media) {
                cellColumns[index] = column until column + width
            }
            if (cell.row == media.row - 1 && cell is SpacerGridCell) {
                if (gapStart < 0) gapStart = column
                gapCellColumns[index] = column until column + width
            }
            column += width
        }
        var gapColumn = gapStart
        val gap =
            if (gapStart < 0) emptyList()
            else
                block.gapFill.map { sized ->
                    MediaGhost(sized.tile, gapColumn, sized.width).also {
                        gapColumn += sized.width
                    }
                }
        return MediaRowGhosts(
            cellColumns = cellColumns,
            sideColumns = block.sideColumns,
            sideSpecs = block.side.map { it.tileSpec },
            top = ghostsOf(0),
            bottom = ghostsOf(1),
            gapCellColumns = if (gap.isEmpty()) emptyMap() else gapCellColumns,
            gap = gap,
        )
    }

    /**
     * Drag target on the second row of the music player block, below the cell at [target] and at
     * grid [column]: the dragged tile takes the place of the tile shown there. Returns false if
     * [target] isn't a cell next to the player.
     */
    override fun onTargetingMediaRow(target: Int, column: Int): Boolean {
        val dragged = draggedCell ?: return false
        val spec = dragged.tile.tileSpec
        if (isMedia(spec)) return false
        val ghosts = mediaRowGhosts() ?: return false
        if (ghosts.columnsOf(target) == null) return false
        val fromIndex = indexOf(spec)
        val width = (_tiles.getOrNull(fromIndex) as? TileGridCell)?.width ?: dragged.width
        // Too wide to sit next to the player: ignore the target
        if (width > ghosts.sideColumns) return true
        val ghost = ghosts.bottomAt(column)
        if (ghost?.tile?.tileSpec == spec) return true

        // Position of the target among the tiles next to the player, in quick settings order
        val position = ghost?.let { ghosts.sideSpecs.indexOf(it.tile.tileSpec) } ?: Int.MAX_VALUE
        val cell =
            if (fromIndex != INVALID_INDEX) {
                _tiles.removeAt(fromIndex) as TileGridCell
            } else {
                TileGridCell(SizedTileImpl(dragged.tile, width), 0, 0)
            }
        regenerateGrid()
        // Without the dragged tile, the tiles after it move up a place: insert it before the tile
        // now at the target position, or after the last one
        val sideSpecs = mediaRowGhosts()?.sideSpecs.orEmpty()
        val insertionIndex =
            sideSpecs.getOrNull(position)?.let { indexOf(it) }?.takeIf { it != INVALID_INDEX }
                ?: run {
                    val last = sideSpecs.maxOfOrNull { indexOf(it) } ?: -1
                    val anchor =
                        if (last >= 0) last
                        else _tiles.indexOfFirst { it is TileGridCell && isMedia(it.tile.tileSpec) }
                    anchor + 1
                }
        _tiles.add(insertionIndex.coerceIn(0, _tiles.size), cell)
        regenerateGrid()
        return true
    }

    private fun widthOf(spec: TileSpec, largeTiles: Set<TileSpec>): Int =
        when {
            isMedia(spec) && mediaWidth > 0 -> mediaWidth.coerceIn(1, columns)
            largeTiles.contains(spec) -> largeTilesSpan
            else -> 1
        }

    override fun isMoving(tileSpec: TileSpec): Boolean {
        return draggedCell?.let { it.tile.tileSpec == tileSpec } ?: false
    }

    override fun onStarted(cell: SizedTile<EditTileViewModel>, dragType: DragType) {
        draggedCell = cell
        this.dragType = dragType
    }

    override fun onTargeting(target: Int, insertAfter: Boolean) {
        val draggedTile = draggedCell ?: return

        val fromIndex = indexOf(draggedTile.tile.tileSpec)
        if (fromIndex == target) {
            return
        }

        val insertionIndex = if (insertAfter) target + 1 else target
        if (fromIndex != INVALID_INDEX) {
            val cell = _tiles.removeAt(fromIndex)
            regenerateGrid()
            _tiles.add(insertionIndex.coerceIn(0, _tiles.size), cell)
        } else {
            // Add the tile with a temporary row/col which will get reassigned when
            // regenerating spacers
            _tiles.add(
                insertionIndex.coerceIn(0, _tiles.size),
                TileGridCell(
                    SizedTileImpl(
                        draggedTile.tile,
                        if (isMedia(draggedTile.tile.tileSpec)) {
                            widthOf(draggedTile.tile.tileSpec, largeTilesSpecs)
                        } else {
                            draggedTile.width
                        },
                    ),
                    0,
                    0,
                ),
            )
        }

        regenerateGrid()
    }

    override fun onMoved(offset: Offset) {
        draggedPosition = offset
    }

    override fun movedOutOfBounds() {
        val draggedTile = draggedCell ?: return

        _tiles.removeIf { cell ->
            cell is TileGridCell && cell.tile.tileSpec == draggedTile.tile.tileSpec
        }
        draggedPosition = Offset.Unspecified

        // Regenerate spacers without the dragged tile
        regenerateGrid()
    }

    override fun onDrop() {
        draggedCell = null
        draggedPosition = Offset.Unspecified
        dragType = null

        // Remove the spacers
        regenerateGrid()
    }

    /**
     * Return the appropriate index to move the tile to for the placement [event]
     *
     * The grid includes spacers. As a result, indexes from the grid need to be translated to the
     * corresponding index from [currentTileSpecs].
     */
    fun targetIndexForPlacement(event: PlacementEvent): Int {
        val currentTileSpecs = tileSpecs()
        return when (event) {
            is PlacementEvent.PlaceToTileSpec -> {
                currentTileSpecs.indexOf(event.targetSpec)
            }
            is PlacementEvent.PlaceToIndex -> {
                if (event.targetIndex >= _tiles.size) {
                    currentTileSpecs.size
                } else if (event.targetIndex <= 0) {
                    0
                } else {
                    // The index may point to a spacer, so first find the first tile located
                    // after index, then use its position as a target
                    val targetTile =
                        _tiles.subList(event.targetIndex, _tiles.size).firstOrNull {
                            it is TileGridCell
                        } as? TileGridCell

                    if (targetTile == null) {
                        currentTileSpecs.size
                    } else {
                        val targetIndex = currentTileSpecs.indexOf(targetTile.tile.tileSpec)
                        val fromIndex = currentTileSpecs.indexOf(event.movingSpec)
                        if (fromIndex < targetIndex) targetIndex - 1 else targetIndex
                    }
                }
            }
        }
    }

    private fun List<EditTileViewModel>.toGridCells(largeTiles: Set<TileSpec>): List<GridCell> {
        return map { SizedTileImpl(it, widthOf(it.tileSpec, largeTiles)) }
            .toGridCells(columns)
    }

    /** Regenerate the list of [GridCell] with their new potential rows */
    private fun regenerateGrid() {
        _tiles.filterIsInstance<TileGridCell>().toGridCells(columns).let {
            _tiles.clear()
            _tiles.addAll(it)
        }
    }

    /**
     * Regenerate the list of [GridCell] with their new potential rows from [fromIndex], leaving
     * cells before that untouched.
     */
    private fun regenerateGrid(fromIndex: Int) {
        val fromRow = _tiles[fromIndex].row
        val (pre, post) = _tiles.partition { it.row < fromRow }
        post.filterIsInstance<TileGridCell>().toGridCells(columns, startingRow = fromRow).let {
            _tiles.clear()
            _tiles.addAll(pre)
            _tiles.addAll(it)
        }
    }

    companion object {
        const val INVALID_INDEX = -1
    }
}

/** A tile quick settings shows next to the music player, at grid [column] of the player rows. */
data class MediaGhost(val tile: EditTileViewModel, val column: Int, val width: Int)

/**
 * The tiles quick settings shows next to the music player: [top] for the first row, [bottom] for
 * the second one. [sideColumns] is the number of free columns next to the player.
 */
class MediaRowGhosts(
    private val cellColumns: Map<Int, IntRange>,
    val sideColumns: Int,
    val sideSpecs: List<TileSpec>,
    val top: List<MediaGhost>,
    val bottom: List<MediaGhost>,
    private val gapCellColumns: Map<Int, IntRange> = emptyMap(),
    /** The tiles quick settings moves into the free cells of the row before the player. */
    val gap: List<MediaGhost> = emptyList(),
) {
    /** The grid columns covered by the cell at [index] if it is next to the player. */
    fun columnsOf(index: Int): IntRange? = cellColumns[index]

    /** The grid columns of the free cell at [index] in the row before the player, if any. */
    fun gapColumnsOf(index: Int): IntRange? = gapCellColumns[index]

    /** The second row tile covering grid [column], if any. */
    fun bottomAt(column: Int): MediaGhost? =
        bottom.firstOrNull { column >= it.column && column < it.column + it.width }
}
