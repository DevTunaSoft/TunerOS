package com.devtunasoft.tuneros.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-01: objective guide-row readability/clipping coverage. All values are pixels, generated from
 * deterministic fixtures; no device, screenshot or manual visual check is involved.
 */
class GuideCellLabelPolicyTest {
    private val padding = 10f // the guide's approved 10dp cell inset, as pixels at density 1

    @Test
    fun aFocusedCellWithASubGlyphRegionMustNotPaintAnUnreadableFragment() {
        // A resized window leaves a focused programme only 24px of cell. Its text region is 4px,
        // narrower than one 8px glyph: the old "any positive region" guard paints a useless sliver.
        assertEquals(4f, GuideCellLabelPolicy.textRegionWidth(24f, padding), 0f)
        assertFalse(
            "a 4px region cannot show an 8px glyph; a focused cell must not render an unreadable title",
            GuideCellLabelPolicy.drawsTitle(24f, padding, 8f),
        )
    }

    @Test
    fun aCellWithNoTextRegionNeverPaintsATitle() {
        assertFalse(GuideCellLabelPolicy.drawsTitle(20f, padding, 8f)) // exactly two insets
        assertFalse(GuideCellLabelPolicy.drawsTitle(12f, padding, 8f)) // smaller than the insets
        assertTrue(GuideCellLabelPolicy.textRegionWidth(12f, padding) < 0f)
    }

    @Test
    fun aNormalCellStillPaintsItsTitle() {
        assertEquals(100f, GuideCellLabelPolicy.textRegionWidth(120f, padding), 0f)
        assertTrue(GuideCellLabelPolicy.drawsTitle(120f, padding, 8f))
    }

    @Test
    fun largerFontMakesTheSameCellUnlabelable() {
        // 30px cell -> 10px region: readable at 8px glyphs, not at 12px glyphs (large font).
        assertTrue(GuideCellLabelPolicy.drawsTitle(30f, padding, 8f))
        assertFalse(
            "large-font glyphs must not be painted into a region that cannot hold one glyph",
            GuideCellLabelPolicy.drawsTitle(30f, padding, 12f),
        )
    }

    @Test
    fun aCellExactlyOneGlyphWideIsReadable() {
        // Boundary: region == glyph width is the smallest acceptable label.
        assertTrue(GuideCellLabelPolicy.drawsTitle(28f, padding, 8f))
    }

    @Test
    fun drawingIsMonotonicInCellWidthAndInGlyphWidth() {
        for (glyph in listOf(6f, 8f, 12f)) {
            var previous = false
            for (cellWidth in 0..160) {
                val current = GuideCellLabelPolicy.drawsTitle(cellWidth.toFloat(), padding, glyph)
                assertFalse("wider cell must not lose a label the narrower one had", previous && !current)
                previous = current
            }
        }
        // For a fixed narrow cell, enlarging the glyph can only turn drawing off, never on.
        val cell = 30f
        assertTrue(GuideCellLabelPolicy.drawsTitle(cell, padding, 6f))
        assertFalse(GuideCellLabelPolicy.drawsTitle(cell, padding, 20f))
    }
}
