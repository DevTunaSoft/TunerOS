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

enum class OpenResult {
    PRIMARY_OPENED, SECONDARY_OPENED, SECONDARY_REPLACED, ALREADY_OPEN, INVALID_ID,
    /** The decoder/provider refused to create a player; no tile was left half-open. */
    CREATE_FAILED,
    /** Both decoder slots are occupied and strict capacity was requested. */
    CAPACITY_REFUSED,
}

enum class FocusResult { FOCUSED, ALREADY_AUDIBLE, NOT_OPEN, INVALID_ID, RELEASED_ON_FAILURE }

/** What an open attempt did, plus the single tile that stays audible as its fallback. */
data class OpenAttempt(val result: OpenResult, val fallbackAudibleTileId: String?)

private const val AUDIBLE_VOLUME = 1f
private const val MUTED_VOLUME = 0f
private const val MAX_ID_CHARS = 128

/**
 * Bounded two-tile multiview policy.
 *
 * At most [MAX_TILES] players exist. The primary tile is the only audible one; the secondary tile is
 * always opened muted (volume 0f). Switching audio promotes the already-muted secondary and releases
 * the old primary, so audio is never doubled. [pause] releases the secondary and [stop] releases both.
 * Every release is issued exactly once, from a single deterministic place per tile. Provider and
 * volume failures are reported as structured results and never leave a half-open tile audible.
 */
class MultiviewTileController(private val factory: MultiviewPlayerFactory) {
    private class Tile(val id: String, val player: MultiviewPlayer) {
        /** Guards against a second release even when the platform release itself throws. */
        var released = false
    }

    private enum class FullPolicy { REPLACE_SECONDARY, REFUSE }

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
    fun open(tileId: String): OpenResult = attemptOpen(tileId, FullPolicy.REPLACE_SECONDARY).result

    /**
     * Strict-decoder variant: when both slots are occupied a third distinct tile is refused instead of
     * replacing the muted tile. The structured result names the single tile that remains audible.
     */
    fun openWithinCapacity(tileId: String): OpenAttempt = attemptOpen(tileId, FullPolicy.REFUSE)

    private fun attemptOpen(tileId: String, fullPolicy: FullPolicy): OpenAttempt {
        if (!valid(tileId)) return OpenAttempt(OpenResult.INVALID_ID, primary?.id)
        val currentPrimary = primary
        if (currentPrimary == null) {
            val created = createPlayer(tileId) ?: return OpenAttempt(OpenResult.CREATE_FAILED, null)
            if (!setVolume(created, AUDIBLE_VOLUME)) {
                releaseUnregistered(created)
                return OpenAttempt(OpenResult.CREATE_FAILED, null)
            }
            primary = Tile(tileId, created)
            return OpenAttempt(OpenResult.PRIMARY_OPENED, tileId)
        }
        if (tileId == currentPrimary.id || tileId == secondary?.id) {
            return OpenAttempt(OpenResult.ALREADY_OPEN, currentPrimary.id)
        }
        val oldSecondary = secondary
        if (oldSecondary == null) {
            return openSecondary(tileId, currentPrimary, OpenResult.SECONDARY_OPENED)
        }
        if (fullPolicy == FullPolicy.REFUSE) {
            // No third decoder is requested and nothing is released: audio stays on the primary.
            return OpenAttempt(OpenResult.CAPACITY_REFUSED, currentPrimary.id)
        }
        // Bounded replacement: clear and release the muted tile before freeing a decoder slot.
        secondary = null
        releaseTile(oldSecondary)
        return openSecondary(tileId, currentPrimary, OpenResult.SECONDARY_REPLACED)
    }

    private fun openSecondary(
        tileId: String,
        currentPrimary: Tile,
        success: OpenResult,
    ): OpenAttempt {
        val created = createPlayer(tileId)
            ?: return OpenAttempt(OpenResult.CREATE_FAILED, currentPrimary.id)
        if (!setVolume(created, MUTED_VOLUME)) {
            releaseUnregistered(created)
            return OpenAttempt(OpenResult.CREATE_FAILED, currentPrimary.id)
        }
        secondary = Tile(tileId, created)
        return OpenAttempt(success, currentPrimary.id)
    }

    private fun createPlayer(tileId: String): MultiviewPlayer? = try {
        factory.create(tileId)
    } catch (failure: Exception) {
        null
    }

    private fun setVolume(player: MultiviewPlayer, volume: Float): Boolean = try {
        player.setVolume(volume)
        true
    } catch (failure: Exception) {
        false
    }

    private fun releaseUnregistered(player: MultiviewPlayer) {
        try {
            player.release()
        } catch (failure: Exception) {
            // A player that was never registered cannot corrupt controller state.
        }
    }

    private fun releaseTile(tile: Tile) {
        if (tile.released) return
        tile.released = true
        try {
            tile.player.release()
        } catch (failure: Exception) {
            // Release is at most once; state is cleared by the caller regardless.
        }
    }

    /** Makes an open secondary the audible tile. The old primary is released; audio stays exclusive. */
    fun focus(tileId: String): FocusResult {
        if (!valid(tileId)) return FocusResult.INVALID_ID
        val currentPrimary = primary ?: return FocusResult.NOT_OPEN
        if (tileId == currentPrimary.id) return FocusResult.ALREADY_AUDIBLE
        val currentSecondary = secondary
        if (currentSecondary == null || tileId != currentSecondary.id) return FocusResult.NOT_OPEN
        // Release the old audible tile before promoting, so two tiles are never audible at once.
        releaseTile(currentPrimary)
        if (!setVolume(currentSecondary.player, AUDIBLE_VOLUME)) {
            // The selection failed: release the would-be tile too and leave zero audible tiles.
            releaseTile(currentSecondary)
            primary = null
            secondary = null
            return FocusResult.RELEASED_ON_FAILURE
        }
        primary = currentSecondary
        secondary = null
        return FocusResult.FOCUSED
    }

    /** Releases the muted secondary, e.g. on pause or on a channel switch. Idempotent. */
    fun pause(): Boolean {
        val currentSecondary = secondary ?: return false
        releaseTile(currentSecondary)
        secondary = null
        return true
    }

    /** Releases both tiles. Idempotent: a second call issues no further releases. */
    fun stop() {
        secondary?.let { releaseTile(it) }
        primary?.let { releaseTile(it) }
        secondary = null
        primary = null
    }

    private fun valid(id: String): Boolean = id.isNotBlank() && id.length <= MAX_ID_CHARS

    companion object {
        const val MAX_TILES = 2
    }
}
