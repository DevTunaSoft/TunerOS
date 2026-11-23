package com.devtunasoft.tuneros.multiview

/**
 * The smallest thing the multiview controller needs from a platform player. Keeping this surface
 * framework-free lets the tile/audio/lifecycle policy run in a plain JVM unit test. The Android layer
 * supplies one real instance per tile; the tests supply a recording fake.
 */
interface MultiviewPlayer {
    /** 0f mutes this tile; 1f is normal volume. */
    fun setVolume(volume: Float)

    /** Releases this tile's decoder and media resources. Called at most once per instance. */
    fun release()
}

/** Builds one platform player for a tile. The caller owns the media item and view wiring. */
fun interface MultiviewPlayerFactory {
    fun create(tileId: String): MultiviewPlayer
}

enum class OpenResult { PRIMARY_OPENED, SECONDARY_OPENED, SECONDARY_REPLACED, ALREADY_OPEN, INVALID_ID }

enum class FocusResult { FOCUSED, ALREADY_AUDIBLE, NOT_OPEN, INVALID_ID }

private const val AUDIBLE_VOLUME = 1f
private const val MUTED_VOLUME = 0f
private const val MAX_ID_CHARS = 128

/**
 * Bounded two-tile multiview policy.
 *
 * At most [MAX_TILES] players exist. The primary tile is the only audible one; the secondary tile is
 * always opened muted (volume 0f). Switching audio promotes the already-muted secondary and releases
 * the old primary, so audio is never doubled. [pause] releases the secondary and [stop] releases both.
 * Every release is issued exactly once, from a single deterministic place per tile.
 */
class MultiviewTileController(private val factory: MultiviewPlayerFactory) {
    private class Tile(val id: String, val player: MultiviewPlayer)

    private var primary: Tile? = null
    private var secondary: Tile? = null

    val primaryTileId: String? get() = primary?.id
    val secondaryTileId: String? get() = secondary?.id

    /** Exactly one tile is audible, or null when no tile is open. */
    val audibleTileId: String? get() = primary?.id

    /** Never greater than [MAX_TILES]. */
    val playerCount: Int
        get() = (if (primary == null) 0 else 1) + (if (secondary == null) 0 else 1)

    /** Open tiles in display order. A copy, so callers cannot mutate the session. */
    val openTileIds: List<String> get() = listOfNotNull(primary?.id, secondary?.id)

    fun isAudible(tileId: String): Boolean = tileId == primary?.id
    fun isMuted(tileId: String): Boolean = tileId == secondary?.id

    /**
     * Shows [tileId]. The first tile becomes the audible primary; the next becomes a muted secondary;
     * a third bounded-replaces the secondary. Reopening an already visible tile is a no-op.
     */
    fun open(tileId: String): OpenResult {
        if (!valid(tileId)) return OpenResult.INVALID_ID
        val currentPrimary = primary
        if (currentPrimary == null) {
            primary = Tile(tileId, factory.create(tileId).also { it.setVolume(AUDIBLE_VOLUME) })
            return OpenResult.PRIMARY_OPENED
        }
        if (tileId == currentPrimary.id) return OpenResult.ALREADY_OPEN
        val oldSecondary = secondary
        if (oldSecondary != null && tileId == oldSecondary.id) return OpenResult.ALREADY_OPEN
        // Bounded replacement: the old muted tile is released before the new one is created.
        oldSecondary?.player?.release()
        secondary = Tile(tileId, factory.create(tileId).also { it.setVolume(MUTED_VOLUME) })
        return if (oldSecondary == null) OpenResult.SECONDARY_OPENED else OpenResult.SECONDARY_REPLACED
    }

    /** Makes an open secondary the audible tile. The old primary is released; audio stays exclusive. */
    fun focus(tileId: String): FocusResult {
        if (!valid(tileId)) return FocusResult.INVALID_ID
        val currentPrimary = primary ?: return FocusResult.NOT_OPEN
        if (tileId == currentPrimary.id) return FocusResult.ALREADY_AUDIBLE
        val currentSecondary = secondary
        if (currentSecondary == null || tileId != currentSecondary.id) return FocusResult.NOT_OPEN
        currentPrimary.player.release()
        // Reuse the promoted player: it was muted, now it is the single audible tile.
        currentSecondary.player.setVolume(AUDIBLE_VOLUME)
        primary = currentSecondary
        secondary = null
        return FocusResult.FOCUSED
    }

    /** Releases the muted secondary, e.g. on pause or on a channel switch. Idempotent. */
    fun pause(): Boolean {
        val currentSecondary = secondary ?: return false
        currentSecondary.player.release()
        secondary = null
        return true
    }

    /** Releases both tiles. Idempotent: a second call issues no further releases. */
    fun stop() {
        secondary?.player?.release()
        primary?.player?.release()
        secondary = null
        primary = null
    }

    private fun valid(id: String): Boolean = id.isNotBlank() && id.length <= MAX_ID_CHARS

    companion object {
        const val MAX_TILES = 2
    }
}
