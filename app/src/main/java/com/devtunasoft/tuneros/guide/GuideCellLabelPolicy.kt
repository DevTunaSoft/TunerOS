package com.devtunasoft.tuneros.guide

/**
 * Pure geometry decisions for a cable-guide programme cell's title.
 *
 * No Android dependency: the clipping/readability rule is asserted on the JVM for narrow windows,
 * dense schedules and large-font runs. Callers pass pixels measured with the current display
 * metrics, so dp/sp conversion stays in the Android view.
 */
object GuideCellLabelPolicy {
    /**
     * Horizontal room left for a title after the cell's approved left and right insets. This is the
     * region that ProgramTimelineView clips its text to.
     */
    fun textRegionWidth(cellWidthPx: Float, horizontalPaddingPx: Float): Float =
        cellWidthPx - 2f * horizontalPaddingPx

    /**
     * Whether a title should be painted in a cell this wide. A region that is positive but narrower
     * than one glyph is an unreadable fragment, not a label; the cell still shows its focus fill.
     * The previous inline guard accepted any positive region, so a resized or large-font cell could
     * paint a focused programme as an unreadable sliver. Require room for at least one glyph.
     */
    fun drawsTitle(cellWidthPx: Float, horizontalPaddingPx: Float, glyphWidthPx: Float): Boolean =
        textRegionWidth(cellWidthPx, horizontalPaddingPx) >= glyphWidthPx
}
