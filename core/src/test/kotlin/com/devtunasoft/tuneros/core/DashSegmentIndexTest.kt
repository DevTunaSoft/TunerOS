package com.devtunasoft.tuneros.core

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashSegmentIndexTest {
    private fun sidx(
        version: Int = 0,
        timescale: Long = 1_000,
        firstOffset: Long = 0,
        references: List<Pair<Long, Long>>,
        hierarchicalFirst: Boolean = false,
        boxType: String = "sidx",
    ): ByteArray = SidxBox.build(version, timescale, firstOffset, references, hierarchicalFirst, boxType)

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

    @Test fun theFirstAndLastReferencesLocateTheirBytesFromTheAnchor() {
        val bytes = sidx(references = listOf(500L to 2_000L, 700L to 2_000L, 600L to 3_000L), firstOffset = 25)
        val anchor = 1_000L + bytes.size + 25
        val references = parsed(bytes).references
        assertEquals(3, references.size)
        assertEquals(HlsByteRange(length = 500, offset = anchor), references.first().range)
        assertEquals(HlsByteRange(length = 600, offset = anchor + 1_200), references.last().range)
    }

    @Test fun aVersionOneIndexLocatesItsReferencesBeyondTheThirtyTwoBitLimit() {
        // 8 GiB of first_offset, which a version 0 index cannot hold.
        val firstOffset = 1L shl 33
        val bytes = sidx(
            version = 1,
            timescale = 90_000,
            firstOffset = firstOffset,
            references = listOf(100L to 180_000L, 200L to 180_000L),
        )
        val anchor = 1_000L + bytes.size + firstOffset
        assertEquals(
            listOf(HlsByteRange(length = 100, offset = anchor), HlsByteRange(length = 200, offset = anchor + 100)),
            parsed(bytes).references.map { it.range },
        )
    }

    @Test fun theLargestVersionZeroFirstOffsetIsAddedExactly() {
        // A version 0 first_offset is an unsigned 32-bit field, so 0xFFFFFFFF is the largest it can name.
        val bytes = sidx(firstOffset = 0xFFFF_FFFFL, references = listOf(500L to 2_000L))
        assertEquals(1_000L + bytes.size + 0xFFFF_FFFFL, parsed(bytes).references[0].range.offset)
    }

    @Test fun aReferenceEndingAtTheLargestAllowedByteIsAccepted() {
        // The manifest's byte ranges stop at 10^15, and an index names no byte past that bound either.
        val largestByte = 1_000_000_000_000_000L
        val size = sidx(version = 1, references = listOf(100L to 1_000L)).size.toLong()
        // A 100-byte reference whose last byte is largestByte starts at largestByte - 99.
        val bytes = sidx(version = 1, firstOffset = largestByte - 99 - (1_000L + size), references = listOf(100L to 1_000L))
        assertEquals(largestByte - 99, parsed(bytes).references[0].range.offset)
    }

    @Test fun aReferenceEndingPastTheLargestAllowedByteIsRefused() {
        val largestByte = 1_000_000_000_000_000L
        val size = sidx(version = 1, references = listOf(100L to 1_000L)).size.toLong()
        // One byte further: the last byte is largestByte + 1.
        val bytes = sidx(version = 1, firstOffset = largestByte - 98 - (1_000L + size), references = listOf(100L to 1_000L))
        assertTrue(refusalOf(bytes).contains("too large"))
    }

    @Test fun aVersionOneFirstOffsetThatOverflowsOrIsNegativeIsRefused() {
        assertTrue(refusalOf(sidx(version = 1, firstOffset = Long.MAX_VALUE, references = listOf(500L to 2_000L))).contains("overflow"))
        assertTrue(refusalOf(sidx(version = 1, firstOffset = -1, references = listOf(500L to 2_000L))).contains("overflow"))
    }

    @Test fun anEmptySubsegmentAnywhereInTheIndexIsRefused() {
        assertTrue(refusalOf(sidx(references = listOf(500L to 2_000L, 0L to 2_000L, 700L to 2_000L))).contains("empty subsegment"))
    }
}
