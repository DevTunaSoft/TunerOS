package com.devtunasoft.tuneros.multiview

/**
 * Bounded bookkeeping for the two physical tile views. Uses two nullable fields rather than a
 * collection: no allocation per switch and no way to grow past two tiles. The Android layer owns the
 * real PlayerViews; this class only decides which slot a tile occupies, so the recycling rule is
 * testable on a plain JVM.
 */
class TileSlotPool {
    private var first: String? = null
    private var second: String? = null

    val liveCount: Int
        get() = (if (first == null) 0 else 1) + (if (second == null) 0 else 1)

    fun slotOf(tileId: String): Int? = when {
        first == tileId -> 0
        second == tileId -> 1
        else -> null
    }

    fun tileAt(slot: Int): String? = when (slot) {
        0 -> first
        1 -> second
        else -> null
    }

    /** Returns the slot already held by this tile, or claims a free one. Never holds more than two. */
    fun acquire(tileId: String): Int {
        require(tileId.isNotBlank()) { "Blank tile id" }
        slotOf(tileId)?.let { return it }
        if (first == null) {
            first = tileId
            return 0
        }
        if (second == null) {
            second = tileId
            return 1
        }
        throw IllegalStateException("No free video tile slot")
    }

    /** Frees the slot. Idempotent and safe for an id that was never acquired. */
    fun release(tileId: String): Boolean {
        if (first == tileId) {
            first = null
            return true
        }
        if (second == tileId) {
            second = null
            return true
        }
        return false
    }

    fun clear() {
        first = null
        second = null
    }
}

/**
 * Opt-in state for the two-tile view plus bounded tile-id derivation. A new instance is always off:
 * multiview is never enabled by a constructor, intent extra or persisted preference.
 */
class MultiviewUiPolicy {
    private val slots = TileSlotPool()

    var isEnabled: Boolean = false
        private set

    /** Explicit user opt-in only. */
    fun enable() {
        isEnabled = true
    }

    /** Returns to Classic Cable and frees every slot on a deterministic teardown path. */
    fun disable() {
        isEnabled = false
        slots.clear()
    }

    /** Frees slots on an activity lifecycle stop without changing the user's opt-in choice. */
    fun clearSlots() {
        slots.clear()
    }

    val liveTileCount: Int get() = slots.liveCount

    fun acquireSlot(tileId: String): Int = slots.acquire(tileId)
    fun releaseSlot(tileId: String): Boolean = slots.release(tileId)
    fun slotOf(tileId: String): Int? = slots.slotOf(tileId)
    fun tileAt(slot: Int): String? = slots.tileAt(slot)

    companion object {
        private const val MAX_RAW_TILE_ID = 64

        /**
         * Bounded, nonblank id for a tile. Ordinary short channel keys are kept verbatim so repeated
         * selections of the same channel are recognised; long or blank keys are reduced to a short
         * deterministic token. The raw key is never logged or exposed as a status message.
         */
        fun tileIdFor(key: String): String {
            val trimmed = key.trim()
            if (trimmed.isNotEmpty() && trimmed.length <= MAX_RAW_TILE_ID) return trimmed
            return "t-" + (trimmed.hashCode().toLong() and 0xFFFF_FFFFL).toString(36)
        }
    }
}
