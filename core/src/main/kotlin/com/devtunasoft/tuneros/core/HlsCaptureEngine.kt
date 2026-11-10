package com.devtunasoft.tuneros.core

import java.io.OutputStream
import java.net.URI

data class LoadedHlsPlaylist(
    val effectiveUrl: String,
    val playlist: HlsPlaylistSnapshot,
)

interface HlsCaptureSource {
    fun loadPlaylist(url: String): LoadedHlsPlaylist

    fun copySegment(
        url: String,
        byteRange: HlsByteRange?,
        output: OutputStream,
        cancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): StreamRecorder.Result
}

class HlsCaptureEngine(
    private val maxStalledPolls: Int = 6,
) {
    init { require(maxStalledPolls > 0) }

    enum class StopReason {
        END_LIST,
        CANCELLED,
        DEADLINE,
        LOW_STORAGE,
        STALLED,
        UNSUPPORTED,
    }

    data class Result(
        val bytesWritten: Long,
        val lastSequence: Long?,
        val stopReason: StopReason,
        val detail: String? = null,
    )

    fun record(
        initialUrl: String,
        output: OutputStream,
        endEpochSeconds: Long,
        source: HlsCaptureSource,
        cancelled: () -> Boolean,
        nowEpochSeconds: () -> Long,
        sleepMillis: (Long) -> Unit,
        onProgress: (Long) -> Unit = {},
    ): Result {
        require(endEpochSeconds > 0)
        var playlistUrl = initialUrl
        var lastSequence: Long? = null
        var totalBytes = 0L
        var stalledPolls = 0
        var selectedVariant = false

        while (true) {
            if (cancelled()) return Result(totalBytes, lastSequence, StopReason.CANCELLED)
            if (nowEpochSeconds() >= endEpochSeconds) {
                return Result(totalBytes, lastSequence, StopReason.DEADLINE)
            }

            val loaded = source.loadPlaylist(playlistUrl)
            when (val playlist = loaded.playlist) {
                is HlsMasterPlaylist -> {
                    if (selectedVariant) {
                        return Result(
                            totalBytes,
                            lastSequence,
                            StopReason.UNSUPPORTED,
                            "Nested HLS master playlist",
                        )
                    }
                    val variant = playlist.variants.maxWithOrNull(
                        compareBy<HlsVariant> { it.bandwidth ?: 0L }.thenBy { it.uri },
                    ) ?: return Result(
                        totalBytes,
                        lastSequence,
                        StopReason.UNSUPPORTED,
                        "HLS master has no variants",
                    )
                    playlistUrl = resolveHttp(loaded.effectiveUrl, variant.uri)
                    selectedVariant = true
                    continue
                }

                is HlsMediaPlaylist -> {
                    val available = playlist.segments
                    if (lastSequence != null && available.isNotEmpty() &&
                        available.last().sequence < lastSequence
                    ) {
                        return Result(
                            totalBytes,
                            lastSequence,
                            StopReason.UNSUPPORTED,
                            "HLS media sequence reset",
                        )
                    }

                    val candidates = when {
                        lastSequence == null && !playlist.endList ->
                            available.lastOrNull()?.let(::listOf).orEmpty()
                        lastSequence == null -> available
                        else -> available.filter { it.sequence > lastSequence }
                    }

                    if (candidates.isEmpty()) {
                        if (playlist.endList) {
                            return Result(totalBytes, lastSequence, StopReason.END_LIST)
                        }
                        stalledPolls++
                        if (stalledPolls >= maxStalledPolls) {
                            return Result(
                                totalBytes,
                                lastSequence,
                                StopReason.STALLED,
                                "HLS playlist stopped advancing",
                            )
                        }
                        sleepMillis(pollDelayMillis(playlist.targetDurationSeconds))
                        continue
                    }
                    stalledPolls = 0

                    for (segment in candidates) {
                        if (cancelled()) {
                            return Result(totalBytes, lastSequence, StopReason.CANCELLED)
                        }
                        if (nowEpochSeconds() >= endEpochSeconds) {
                            return Result(totalBytes, lastSequence, StopReason.DEADLINE)
                        }
                        if (!segment.keyMethod.equals("NONE", ignoreCase = true)) {
                            return Result(
                                totalBytes,
                                lastSequence,
                                StopReason.UNSUPPORTED,
                                "Encrypted HLS is not supported yet (${segment.keyMethod})",
                            )
                        }
                        if (segment.initializationMapUri != null) {
                            return Result(
                                totalBytes,
                                lastSequence,
                                StopReason.UNSUPPORTED,
                                "fMP4 HLS is not supported yet",
                            )
                        }

                        val segmentUrl = resolveHttp(loaded.effectiveUrl, segment.uri)
                        val before = totalBytes
                        val copy = source.copySegment(
                            url = segmentUrl,
                            byteRange = segment.byteRange,
                            output = output,
                            cancelled = cancelled,
                            onProgress = { segmentBytes -> onProgress(before + segmentBytes) },
                        )
                        totalBytes = Math.addExact(totalBytes, copy.bytesWritten)

                        when (copy.stopReason) {
                            StreamRecorder.StopReason.END_OF_STREAM -> {
                                lastSequence = segment.sequence
                                onProgress(totalBytes)
                            }
                            StreamRecorder.StopReason.CANCELLED ->
                                return Result(totalBytes, lastSequence, StopReason.CANCELLED)
                            StreamRecorder.StopReason.LOW_STORAGE ->
                                return Result(totalBytes, lastSequence, StopReason.LOW_STORAGE)
                        }
                    }

                    if (playlist.endList) {
                        return Result(totalBytes, lastSequence, StopReason.END_LIST)
                    }
                    sleepMillis(pollDelayMillis(playlist.targetDurationSeconds))
                }
            }
        }
    }

    private fun pollDelayMillis(targetDurationSeconds: Long?): Long =
        ((targetDurationSeconds ?: 4L) * 500L).coerceIn(1_000L, 10_000L)

    private fun resolveHttp(base: String, relative: String): String {
        val baseUri = URI(base)
        val resolved = baseUri.resolve(relative)
        require(resolved.scheme?.lowercase() in setOf("http", "https") && !resolved.host.isNullOrBlank()) {
            "HLS resource must use HTTP or HTTPS"
        }
        require(!(baseUri.scheme.equals("https", ignoreCase = true) &&
            resolved.scheme.equals("http", ignoreCase = true))) {
            "HLS HTTPS downgrade blocked"
        }
        return resolved.toString()
    }
}
