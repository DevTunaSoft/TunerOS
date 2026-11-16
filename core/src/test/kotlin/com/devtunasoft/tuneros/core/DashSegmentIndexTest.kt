package com.devtunasoft.tuneros.core

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashSegmentIndexTest {
    /**
     * Builds an ISO/IEC 14496-12 `sidx` box. [references] are (referenced size, subsegment duration) pairs, all media
     * references unless [hierarchicalFirst] marks the first one as a hierarchical index.
     */
    private fun sidx(
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

    private fun parseAt(bytes: ByteArray, start: Long = 1_000): DashSegmentIndex.Outcome =
        DashSegmentIndex.parse(bytes, HlsByteRange(length = bytes.size.toLong(), offset = start))

    private fun parsed(bytes: ByteArray, start: Long = 1_000): DashSegmentIndex.Outcome.Parsed {
        val outcome = parseAt(bytes, start)
        assertTrue(outcome is DashSegmentIndex.Outcome.Parsed, "expected a parsed index, got $outcome")
        return outcome as DashSegmentIndex.Outcome.Parsed
    }

    private fun refusalOf(bytes: ByteArray, start: Long = 1_000): String {
        val outcome = parseAt(bytes, start)
        assertTrue(outcome is DashSegmentIndex.Outcome.Refused, "expected a refusal, got $outcome")
        return (outcome as DashSegmentIndex.Outcome.Refused).reason
    }

    @Test fun aVersionZeroIndexLocatesEachReferenceFromTheByteAfterTheBox() {
        // The index sits at 1000. Its first media byte is the byte after the box plus first_offset.
        val bytes = sidx(references = listOf(500L to 2_000L, 700L to 2_000L, 600L to 3_000L), firstOffset = 25)
        val index = parsed(bytes)
        assertEquals(1_000L, index.timescale)
        val anchor = 1_000L + bytes.size + 25
        assertEquals(
            listOf(
                HlsByteRange(length = 500, offset = anchor),
                HlsByteRange(length = 700, offset = anchor + 500),
                HlsByteRange(length = 600, offset = anchor + 1_200),
            ),
            index.references.map { it.range },
        )
        assertEquals(listOf(2_000L, 2_000L, 3_000L), index.references.map { it.durationTicks })
    }

    @Test fun aVersionOneIndexTakesA64BitFirstOffset() {
        val bytes = sidx(version = 1, timescale = 90_000, firstOffset = 5_000_000_000L, references = listOf(100L to 180_000L))
        val index = parsed(bytes)
        assertEquals(90_000L, index.timescale)
        assertEquals(1_000L + bytes.size + 5_000_000_000L, index.references[0].range.offset)
    }

    @Test fun aHierarchicalReferenceIsRefused() {
        val bytes = sidx(references = listOf(500L to 2_000L, 700L to 2_000L), hierarchicalFirst = true)
        assertTrue(refusalOf(bytes).contains("hierarchical"))
    }

    @Test fun anEmptySubsegmentIsRefused() {
        assertTrue(refusalOf(sidx(references = listOf(0L to 2_000L))).contains("empty subsegment"))
    }

    @Test fun aZeroDurationSubsegmentIsRefused() {
        assertTrue(refusalOf(sidx(references = listOf(500L to 0L))).contains("zero-duration"))
    }

    @Test fun anIndexThatIsNotASidxBoxIsRefused() {
        assertTrue(refusalOf(sidx(references = listOf(500L to 2_000L), boxType = "moov")).contains("sidx"))
    }

    @Test fun aNonPositiveTimescaleIsRefused() {
        assertTrue(refusalOf(sidx(timescale = 0, references = listOf(500L to 2_000L))).contains("timescale"))
    }

    @Test fun anUnknownIndexVersionIsRefused() {
        val bytes = sidx(references = listOf(500L to 2_000L))
        bytes[8] = 2 // the version byte, right after the box header
        assertTrue(refusalOf(bytes).contains("version"))
    }

    @Test fun anIndexWithNoReferencesIsRefused() {
        assertTrue(refusalOf(sidx(references = emptyList())).contains("no references"))
    }

    @Test fun referencesThatRunPastTheBoxAreRefused() {
        // One reference fits the box, but the header claims two. The reference_count sits at byte 30 of a version 0 box.
        val bytes = sidx(references = listOf(500L to 2_000L))
        ByteBuffer.wrap(bytes).putShort(30, 2)
        assertTrue(refusalOf(bytes).contains("truncated"))
    }

    @Test fun aBoxLargerThanItsRangeIsRefused() {
        val bytes = sidx(references = listOf(500L to 2_000L))
        // The range names fewer bytes than the box declares.
        val outcome = DashSegmentIndex.parse(bytes.copyOf(bytes.size - 4), HlsByteRange(length = bytes.size - 4L, offset = 1_000))
        assertTrue(outcome is DashSegmentIndex.Outcome.Refused, "expected a refusal, got $outcome")
    }

    @Test fun bytesThatAreNotTheRequestedRangeAreRefused() {
        val bytes = sidx(references = listOf(500L to 2_000L))
        val outcome = DashSegmentIndex.parse(bytes, HlsByteRange(length = bytes.size + 1L, offset = 1_000))
        assertTrue(outcome is DashSegmentIndex.Outcome.Refused, "expected a refusal, got $outcome")
    }

    @Test fun anIndexLargerThanTheLimitIsRefusedBeforeItIsParsed() {
        val huge = ByteArray(200_001)
        val outcome = DashSegmentIndex.parse(huge, HlsByteRange(length = huge.size.toLong(), offset = 0))
        assertTrue(outcome is DashSegmentIndex.Outcome.Refused && outcome.reason.contains("too large"), "got $outcome")
    }

    @Test fun tooManyReferencesAreRefused() {
        val references = List(10_001) { 100L to 1_000L }
        assertTrue(refusalOf(sidx(references = references)).contains("too many"))
    }
}
