package com.devtunasoft.tuneros

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/**
 * The focus ring for remote navigation: a white outline while the view has focus, and nothing otherwise. It is meant to be
 * set as a view's foreground, so the view's own background and text are unchanged.
 */
internal fun focusRingDrawable(density: Float): StateListDrawable {
    val ring = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke((4 * density).toInt(), Color.WHITE)
        cornerRadius = 8 * density
    }
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), ring)
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }
}
