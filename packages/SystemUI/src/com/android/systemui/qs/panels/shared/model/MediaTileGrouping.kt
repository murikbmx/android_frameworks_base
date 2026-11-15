/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.shared.model

/** Sizes the music player tile can take in the quick settings grid (columns x rows). */
enum class MediaTileSize(val columns: Int, val rows: Int, val key: String) {
    S2x2(2, 2, "2x2"),
    S3x2(3, 2, "3x2"),
    S4x2(4, 2, "4x2");

    val label: String
        get() = "$columns×$rows"

    companion object {
        val DEFAULT = S3x2

        fun fromKey(key: String?): MediaTileSize = entries.firstOrNull { it.key == key } ?: DEFAULT

        fun fromColumns(columns: Int): MediaTileSize =
            entries.firstOrNull { it.columns == columns } ?: DEFAULT
    }
}

/** An item of the quick settings grid once the music player tile is grouped with its neighbours. */
sealed interface QSGridItem<T> {
    /** Number of grid rows this item takes when it is alone in its row group. */
    val rows: Int

    /** The tiles of this item, in grid order. */
    val tiles: List<T>

    data class Single<T>(val sizedTile: SizedTile<T>) : QSGridItem<T> {
        override val rows: Int
            get() = 1

        override val tiles: List<T>
            get() = listOf(sizedTile.tile)
    }

    /**
     * The music player spanning [playerColumns] x [rows], with the tiles of [sideRows] filling the
     * remaining [sideColumns] columns of those rows: icon tiles, and large tiles when they fit.
     */
    data class MediaBlock<T>(
        val media: T,
        val playerColumns: Int,
        val sideColumns: Int,
        override val rows: Int,
        val sideRows: List<List<SizedTile<T>>>,
        /** The player is on the end side, the side tiles before it. */
        val playerAtEnd: Boolean = false,
    ) : QSGridItem<T> {
        /** The tiles next to the player, row by row. */
        val side: List<T>
            get() = sideRows.flatten().map { it.tile }

        override val tiles: List<T>
            get() = listOf(media) + side
    }
}

/**
 * Groups [tiles] so that the first tile matching [isMedia] becomes a [QSGridItem.MediaBlock] of the
 * given [size]. The free cells next to the player are filled with the next icon tiles; large tiles
 * keep their relative order after the block.
 */
fun <T> groupWithMediaTile(
    tiles: List<SizedTile<T>>,
    columns: Int,
    size: MediaTileSize,
    isMedia: (T) -> Boolean,
): List<QSGridItem<T>> {
    val mediaIndex = tiles.indexOfFirst { isMedia(it.tile) }
    if (mediaIndex < 0 || columns <= 0) return tiles.map { QSGridItem.Single(it) }

    val playerColumns = size.columns.coerceAtMost(columns)
    val sideColumns = columns - playerColumns

    val result = ArrayList<QSGridItem<T>>(tiles.size)
    val row = TileRow<T>(columns)
    for (i in 0 until mediaIndex) {
        result.add(QSGridItem.Single(tiles[i]))
        if (!row.maybeAddTile(tiles[i])) {
            row.clear()
            row.maybeAddTile(tiles[i])
        }
    }

    var rest = tiles.subList(mediaIndex + 1, tiles.size).filterNot { isMedia(it.tile) }
    // Tiles right before the player that exactly fill the free columns of its row: the player
    // goes to the end, these tiles before it (the start edge of the player was moved)
    if (
        sideColumns > 0 &&
            row.tiles.isNotEmpty() &&
            row.tiles.sumOf { it.width } == sideColumns
    ) {
        val before = row.tiles.toList()
        repeat(before.size) { result.removeAt(result.size - 1) }
        val second = fillSideRows(rest, sideColumns, size.rows - 1)
        val sideRows = listOf(before) + second
        result.add(
            QSGridItem.MediaBlock(
                media = tiles[mediaIndex].tile,
                playerColumns = playerColumns,
                sideColumns = sideColumns,
                rows = size.rows,
                sideRows = sideRows,
                playerAtEnd = true,
            )
        )
        val side = second.flatten()
        rest.filterNot { side.contains(it) }.forEach { result.add(QSGridItem.Single(it)) }
        return result
    }
    // The block always starts a new row: fill the gap left in the current row first.
    if (row.tiles.isNotEmpty() && !row.isFull()) {
        val fill = mutableListOf<SizedTile<T>>()
        for (tile in rest) {
            if (row.isFull()) break
            if (row.maybeAddTile(tile)) fill.add(tile)
        }
        fill.forEach { result.add(QSGridItem.Single(it)) }
        rest = rest.filterNot { fill.contains(it) }
    }
    val sideRows = fillSideRows(rest, sideColumns, size.rows)
    val side = sideRows.flatten()
    result.add(
        QSGridItem.MediaBlock(
            media = tiles[mediaIndex].tile,
            playerColumns = playerColumns,
            sideColumns = sideColumns,
            rows = size.rows,
            sideRows = sideRows,
        )
    )
    rest.filterNot { side.contains(it) }.forEach { result.add(QSGridItem.Single(it)) }
    return result
}

/**
 * Fills [rows] rows of [columns] cells with the [tiles] that fit, in order: each tile goes to the
 * first row with enough free cells, tiles wider than [columns] are left out.
 */
private fun <T> fillSideRows(
    tiles: List<SizedTile<T>>,
    columns: Int,
    rows: Int,
): List<List<SizedTile<T>>> {
    if (columns <= 0) return emptyList()
    val result = List(rows) { mutableListOf<SizedTile<T>>() }
    val free = IntArray(rows) { columns }
    for (tile in tiles) {
        if (free.all { it == 0 }) break
        if (tile.width > columns) continue
        val row = free.indexOfFirst { it >= tile.width }
        if (row < 0) continue
        result[row].add(tile)
        free[row] -= tile.width
    }
    return result
}

/**
 * Splits grouped grid items into pages of at most [rows] rows. A media block is never split across
 * pages. Returns the tiles of each page in grid order.
 */
fun <T> splitGroupedIntoPages(
    items: List<QSGridItem<T>>,
    columns: Int,
    rows: Int,
): List<List<T>> {
    // First compute row groups: consecutive singles are packed in rows, media blocks are alone.
    val rowGroups = mutableListOf<Pair<Int, List<T>>>()
    val row = TileRow<T>(columns)
    fun flushRow() {
        if (row.tiles.isNotEmpty()) {
            rowGroups.add(1 to row.tiles.map { it.tile })
            row.clear()
        }
    }
    for (item in items) {
        when (item) {
            is QSGridItem.Single -> {
                if (!row.maybeAddTile(item.sizedTile)) {
                    flushRow()
                    row.maybeAddTile(item.sizedTile)
                }
            }
            is QSGridItem.MediaBlock -> {
                flushRow()
                rowGroups.add(item.rows to item.tiles)
            }
        }
    }
    flushRow()

    val pages = mutableListOf<List<T>>()
    var page = mutableListOf<T>()
    var used = 0
    for ((height, groupTiles) in rowGroups) {
        if (used > 0 && used + height > rows) {
            pages.add(page)
            page = mutableListOf()
            used = 0
        }
        page.addAll(groupTiles)
        used += height
    }
    if (page.isNotEmpty()) pages.add(page)
    return pages
}
