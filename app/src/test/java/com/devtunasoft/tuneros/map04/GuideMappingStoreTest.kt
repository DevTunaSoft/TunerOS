package com.devtunasoft.tuneros.map04

import com.devtunasoft.tuneros.GuideMappingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic, deterministic storage and codec: no Android framework, no provider data. */
private class FakeStorage(
    var raw: String? = null,
    var commitResult: Boolean = true,
    var removeResult: Boolean = true,
) : GuideMappingStore.Storage {
    var writes = 0
        private set
    var removes = 0
        private set

    override fun read(): String? = raw

    override fun write(value: String): Boolean {
        writes++
        if (commitResult) raw = value
        return commitResult
    }

    override fun remove(): Boolean {
        removes++
        if (removeResult) raw = null
        return removeResult
    }
}

/** Minimal delimiter format used only by these JVM tests; the production codec stays org.json. */
private class FakeCodec : GuideMappingStore.Codec {
    var decoded: Map<String, Any?> = emptyMap()
    var throwOnDecode: Boolean = false
    var lastEncoded: Map<String, String> = emptyMap()
        private set

    override fun decode(raw: String): Map<String, Any?> {
        if (throwOnDecode) throw IllegalArgumentException("synthetic corrupt payload")
        if (decoded.isNotEmpty()) return decoded
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, Any?>()
        for (pair in raw.split('\u0001')) {
            val split = pair.indexOf('=')
            if (split >= 0) result[pair.substring(0, split)] = pair.substring(split + 1)
        }
        return result
    }

    override fun encode(mappings: Map<String, String>): String {
        lastEncoded = LinkedHashMap(mappings)
        return mappings.entries.joinToString("\u0001") { "${it.key}=${it.value}" }
    }
}

class GuideMappingStoreTest {
    private val storage = FakeStorage()
    private val codec = FakeCodec()
    private val store = GuideMappingStore(storage, codec)

    @Test
    fun missingPayloadLoadsAsEmpty() {
        assertNull(storage.raw)
        assertTrue(store.load().isEmpty())
        assertEquals(0, storage.writes)
    }

    @Test
    fun validMappingsRoundTrip() {
        val mappings = linkedMapOf("chan.one" to "guide.one", "chan.two" to "guide.two")
        assertTrue(store.save(mappings))
        assertEquals(mappings, codec.lastEncoded)
        assertEquals(mappings, store.load())
    }

    @Test
    fun emptyValuesAndUnusualKeysSurviveRoundTrip() {
        val mappings = linkedMapOf("key.with.dots" to "", "key-with-dash" to "guide.zero")
        assertTrue(store.save(mappings))
        assertEquals(mappings, store.load())
    }

    /**
     * Regression: one malformed record must not destroy every other user mapping. A single channel's saved
     * value is an independent choice, and the next save would otherwise overwrite the whole blob permanently.
     */
    @Test
    fun malformedValueDoesNotDiscardValidMappings() {
        storage.raw = "synthetic"
        codec.decoded = linkedMapOf("keep.me" to "guide.keep", "bad.value" to 7)
        assertEquals(mapOf("keep.me" to "guide.keep"), store.load())
    }

    @Test
    fun totalCorruptionStillLoadsAsEmpty() {
        storage.raw = "synthetic"
        codec.throwOnDecode = true
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun emptyPayloadLoadsAsEmpty() {
        storage.raw = ""
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun loadNeverWritesBack() {
        storage.raw = "chan.one=guide.one"
        assertEquals(mapOf("chan.one" to "guide.one"), store.load())
        assertEquals(0, storage.writes)
        assertEquals(0, storage.removes)
    }

    @Test
    fun saveReportsFailedCommit() {
        storage.commitResult = false
        val before = storage.raw
        assertFalse(store.save(mapOf("chan.one" to "guide.one")))
        assertEquals(before, storage.raw)
        assertEquals(1, storage.writes)
    }

    @Test
    fun saveOfEmptyMapPersistsEmptyPayload() {
        assertTrue(store.save(mapOf("chan.one" to "guide.one")))
        assertTrue(store.save(emptyMap()))
        assertTrue(store.load().isEmpty())
        assertEquals(2, storage.writes)
    }

    @Test
    fun clearRemovesPayloadAndReportsFailure() {
        assertTrue(store.save(mapOf("chan.one" to "guide.one")))
        assertTrue(store.clear())
        assertNull(storage.raw)
        assertTrue(store.load().isEmpty())
        storage.removeResult = false
        assertFalse(store.clear())
        assertEquals(2, storage.removes)
    }

    /**
     * The store is a dumb persistence boundary: it must not silently prune a key that a caller considers stale.
     * Pruning needs the live playlist and belongs to the caller (see MAP-04 audit note).
     */
    @Test
    fun saveDoesNotPruneKeysImplicitly() {
        val mappings = linkedMapOf(
            "live.channel" to "guide.live",
            "stale.channel" to "guide.stale",
        )
        assertTrue(store.save(mappings))
        assertEquals(mappings, codec.lastEncoded)
        assertEquals(mappings, store.load())
    }

    @Test
    fun saveDoesNotMutateCallerMap() {
        val mappings = linkedMapOf("chan.one" to "guide.one")
        assertTrue(store.save(mappings))
        mappings["chan.two"] = "guide.two"
        assertEquals(mapOf("chan.one" to "guide.one"), codec.lastEncoded)
    }
}
