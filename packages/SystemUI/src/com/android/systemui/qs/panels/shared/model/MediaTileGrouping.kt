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
     * The music player spanning [playerColumns] x [rows], with [side] icon tiles filling the
     * remaining [sideColumns] columns of those rows (row by row).
     */
    data class MediaBlock<T>(
        val media: T,
        val playerColumns: Int,
        val sideColumns: Int,
        override val rows: Int,
        val side: List<T>,
    ) : QSGridItem<T> {
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
    val sideCount = sideColumns * size.rows

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
    val side = rest.filter { it.width == 1 }.take(sideCount)
    result.add(
        QSGridItem.MediaBlock(
            media = tiles[mediaIndex].tile,
            playerColumns = playerColumns,
            sideColumns = sideColumns,
            rows = size.rows,
            side = side.map { it.tile },
        )
    )
    rest.filterNot { side.contains(it) }.forEach { result.add(QSGridItem.Single(it)) }
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
