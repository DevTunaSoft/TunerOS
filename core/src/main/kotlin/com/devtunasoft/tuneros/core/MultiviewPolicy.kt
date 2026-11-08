package com.devtunasoft.tuneros.core

/** Resource-aware multiview admission control. Display only one audio track at a time. */
object MultiviewPolicy {
    data class Capacity(val decoderSlots: Int, val providerConnections: Int, val userLimit: Int = 4)
    fun allowedTiles(capacity: Capacity): Int = minOf(
        capacity.decoderSlots.coerceAtLeast(0),
        capacity.providerConnections.coerceAtLeast(0),
        capacity.userLimit.coerceIn(1, 4),
        4,
    )
    fun chooseAudioTile(tileIndex: Int, activeTiles: Int): Int {
        require(activeTiles in 1..4) { "No active multiview session" }
        require(tileIndex in 0 until activeTiles) { "Audio tile does not exist" }
        return tileIndex
    }
}
