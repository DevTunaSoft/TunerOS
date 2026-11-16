package com.devtunasoft.tuneros.core

import java.io.OutputStream
import java.net.URI

data class LoadedDashManifest(
    val effectiveUrl: String,
    val text: String,
)

/**
 * Network access for DASH recording. Implementations keep provider headers, never log URLs or bodies, and
 * never follow a redirect to another scheme.
 */
interface DashCaptureSource {
    fun loadManifest(url: String): LoadedDashManifest

    fun copySegment(
        url: String,
        output: OutputStream,
        cancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): StreamRecorder.Result
}

/**
 * Records one static DASH video representation: the initialization segment once, then each media segment in
 * order. The segment count comes from the presentation duration and the fixed segment duration. Anything the
 * planner refuses is reported as UNSUPPORTED before any byte is written.
 */
class DashCaptureEngine {
    enum class StopReason { END_LIST, CANCELLED, DEADLINE, LOW_STORAGE, UNSUPPORTED }

    data class Result(
        val bytesWritten: Long,
        val stopReason: StopReason,
        val detail: String? = null,
    )

    fun record(
        initialUrl: String,
        output: OutputStream,
        endEpochSeconds: Long,
        source: DashCaptureSource,
        cancelled: () -> Boolean,
        nowEpochSeconds: () -> Long,
        onProgress: (Long) -> Unit = {},
    ): Result {
        require(endEpochSeconds > 0)
        if (cancelled()) return Result(0, StopReason.CANCELLED)
        if (nowEpochSeconds() >= endEpochSeconds) return Result(0, StopReason.DEADLINE)

        val loaded = source.loadManifest(initialUrl)
        val plan = when (val outcome = DashManifest.plan(loaded.text)) {
            is DashPlanOutcome.Unsupported -> return Result(0, StopReason.UNSUPPORTED, outcome.reason)
            is DashPlanOutcome.Planned -> outcome.plan
        }
        val count = Math.ceil(
            plan.mediaPresentationDurationSeconds / plan.segmentDurationSeconds - COUNT_TOLERANCE,
        ).toLong()
        if (count < 1) return Result(0, StopReason.UNSUPPORTED, "DASH manifest has no media segments")
        if (count > MAX_SEGMENTS) return Result(0, StopReason.UNSUPPORTED, "DASH manifest has too many segments")

        val base = if (plan.baseUrl == null) {
            loaded.effectiveUrl
        } else {
            resolveOrNull(loaded.effectiveUrl, plan.baseUrl)
                ?: return Result(0, StopReason.UNSUPPORTED, UNSUPPORTED_RESOURCE)
        }

        var totalBytes = 0L
        val initializationTemplate = plan.initializationTemplate
        if (initializationTemplate != null) {
            val expanded = DashManifest.expand(
                initializationTemplate, plan.representationId, plan.bandwidth, plan.startNumber,
            ) ?: return Result(0, StopReason.UNSUPPORTED, UNSUPPORTED_TOKEN)
            val url = resolveOrNull(base, expanded) ?: return Result(0, StopReason.UNSUPPORTED, UNSUPPORTED_RESOURCE)
            val before = totalBytes
            val init = source.copySegment(url, output, cancelled) { bytes -> onProgress(before + bytes) }
            totalBytes = Math.addExact(totalBytes, init.bytesWritten)
            when (init.stopReason) {
                StreamRecorder.StopReason.END_OF_STREAM -> {
                    if (init.bytesWritten == 0L) {
                        return Result(totalBytes, StopReason.UNSUPPORTED, "DASH initialization segment is empty")
                    }
                    onProgress(totalBytes)
                }
                StreamRecorder.StopReason.CANCELLED -> return Result(totalBytes, StopReason.CANCELLED)
                StreamRecorder.StopReason.LOW_STORAGE -> return Result(totalBytes, StopReason.LOW_STORAGE)
            }
        }

        for (index in 0 until count) {
            val number = plan.startNumber + index
            if (cancelled()) return Result(totalBytes, StopReason.CANCELLED)
            if (nowEpochSeconds() >= endEpochSeconds) return Result(totalBytes, StopReason.DEADLINE)
            val expanded = DashManifest.expand(plan.mediaTemplate, plan.representationId, plan.bandwidth, number)
                ?: return Result(totalBytes, StopReason.UNSUPPORTED, UNSUPPORTED_TOKEN)
            val url = resolveOrNull(base, expanded) ?: return Result(totalBytes, StopReason.UNSUPPORTED, UNSUPPORTED_RESOURCE)
            val before = totalBytes
            val copy = source.copySegment(url, output, cancelled) { bytes -> onProgress(before + bytes) }
            totalBytes = Math.addExact(totalBytes, copy.bytesWritten)
            when (copy.stopReason) {
                StreamRecorder.StopReason.END_OF_STREAM -> onProgress(totalBytes)
                StreamRecorder.StopReason.CANCELLED -> return Result(totalBytes, StopReason.CANCELLED)
                StreamRecorder.StopReason.LOW_STORAGE -> return Result(totalBytes, StopReason.LOW_STORAGE)
            }
        }
        return Result(totalBytes, StopReason.END_LIST)
    }

    /** Resolves [relative] against [base]. Null when the result is not HTTP(S) or would downgrade HTTPS. */
    private fun resolveOrNull(base: String, relative: String): String? = runCatching {
        val baseUri = URI(base)
        val resolved = baseUri.resolve(relative)
        val httpScheme = resolved.scheme.equals("http", ignoreCase = true) ||
            resolved.scheme.equals("https", ignoreCase = true)
        val downgrade = baseUri.scheme.equals("https", ignoreCase = true) &&
            resolved.scheme.equals("http", ignoreCase = true)
        if (!httpScheme || resolved.host.isNullOrBlank() || downgrade) null else resolved.toString()
    }.getOrNull()

    private companion object {
        /** Counts whole segments, so a duration a few milliseconds past a segment boundary adds no segment. */
        const val COUNT_TOLERANCE = 0.05
        const val MAX_SEGMENTS = 100_000L
        const val UNSUPPORTED_RESOURCE = "DASH resource must use HTTP or HTTPS"
        const val UNSUPPORTED_TOKEN = "DASH template uses a token that is not supported"
    }
}
