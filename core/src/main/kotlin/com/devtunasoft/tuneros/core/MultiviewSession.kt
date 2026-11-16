package com.devtunasoft.tuneros.core

/**
 * Admission and audio focus for one multiview session. Pure state: the Android layer opens one player per
 * admitted tile and mutes every tile except [audibleTile]. Admission never exceeds
 * [MultiviewPolicy.allowedTiles], and at most one tile is audible at a time.
 */
class MultiviewSession(capacity: MultiviewPolicy.Capacity) {
    enum class Admission { ADMITTED, DUPLICATE, INVALID_ID, AT_CAPACITY }

    /** The most tiles this session may hold for its capacity, never more than four. */
    val maxTiles: Int = MultiviewPolicy.allowedTiles(capacity)

    private val tiles = ArrayList<String>(MAX_TILES)
    private var audibleIndex = -1

    /** Open tiles in display order. A copy, so callers cannot change admission. */
    val openTiles: List<String> get() = tiles.toList()

    /** The one tile whose audio is audible, or null when no tile is open. */
    val audibleTile: String? get() = tiles.getOrNull(audibleIndex)

    /** Admits [tileId]. The first tile admitted becomes audible. Rejections leave the session unchanged. */
    fun open(tileId: String): Admission {
        if (tileId.isBlank() || tileId.length > MAX_ID_CHARS) return Admission.INVALID_ID
        if (tileId in tiles) return Admission.DUPLICATE
        if (tiles.size >= maxTiles) return Admission.AT_CAPACITY
        tiles += tileId
        if (audibleIndex < 0) audibleIndex = 0
        return Admission.ADMITTED
    }

    /** Gives audio to [tileId]. Returns false, and changes nothing, when that tile is not open. */
    fun focusAudio(tileId: String): Boolean {
        val index = tiles.indexOf(tileId)
        if (index < 0) return false
        audibleIndex = index
        return true
    }

    /**
     * Closes [tileId], for example after it failed. An audible tile hands audio to the tile that took its
     * place, or to the new last tile when the closed tile was last. Returns false when the tile was not open.
     */
    fun close(tileId: String): Boolean {
        val index = tiles.indexOf(tileId)
        if (index < 0) return false
        val wasAudible = index == audibleIndex
        tiles.removeAt(index)
        audibleIndex = when {
            tiles.isEmpty() -> -1
            index < audibleIndex -> audibleIndex - 1
            wasAudible -> minOf(index, tiles.size - 1)
            else -> audibleIndex
        }
        return true
    }

    private companion object {
        const val MAX_TILES = 4
        const val MAX_ID_CHARS = 128
    }
}
