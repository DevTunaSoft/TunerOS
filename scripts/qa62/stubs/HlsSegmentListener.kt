package com.devtunasoft.tuneros.core

/** Host-only signature mirror of the interface declared in HlsCaptureEngine.kt. */
interface HlsSegmentListener {
    fun onSegmentComplete(sequence: Long, durationSeconds: Double, bytes: Long, discontinuity: Boolean)
}
