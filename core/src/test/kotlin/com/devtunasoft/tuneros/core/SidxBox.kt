package com.devtunasoft.tuneros.core

import java.nio.ByteBuffer

/** Builds ISO/IEC 14496-12 `sidx` boxes for tests, so each test states the references it means. */
internal object SidxBox {
    /**
     * Builds an index box. [references] are (referenced size, subsegment duration) pairs, all media references unless
     * [hierarchicalFirst] marks the first one as a hierarchical index.
     */
    fun build(
        version: Int = 0,
        timescale: Long = 1_000,
        firstOffset: Long = 0,
        references: List<Pair<Long, Long>>,
        hierarchicalFirst: Boolean = false,
        boxType: String = "sidx",
    ): ByteArray {
        val firstFields = if (version == 1) 16 else 8
        val total = 8 + 4 + 4 + 4 + firstFields + 4 + 12 * references.size
        val buffer = ByteBuffer.allocate(total)
        buffer.putInt(total)
        buffer.put(boxType.toByteArray(Charsets.US_ASCII))
        buffer.put(version.toByte())
        buffer.put(byteArrayOf(0, 0, 0))
        buffer.putInt(1) // reference_ID
        buffer.putInt(timescale.toInt())
        if (version == 1) {
            buffer.putLong(0) // earliest_presentation_time
            buffer.putLong(firstOffset)
        } else {
            buffer.putInt(0)
            buffer.putInt(firstOffset.toInt())
        }
        buffer.putShort(0) // reserved
        buffer.putShort(references.size.toShort())
        references.forEachIndexed { index, (size, duration) ->
            val type = if (hierarchicalFirst && index == 0) 1 else 0
            buffer.putInt((type shl 31) or size.toInt())
            buffer.putInt(duration.toInt())
            buffer.putInt(0x9000_0000.toInt()) // starts with a SAP, type 1, no delta
        }
        return buffer.array()
    }
}
