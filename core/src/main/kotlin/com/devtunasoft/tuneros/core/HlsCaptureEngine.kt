package com.devtunasoft.tuneros.core

import java.io.IOException
import java.io.OutputStream
import java.net.URI

data class LoadedHlsPlaylist(
    val effectiveUrl: String,
    val playlist: HlsPlaylistSnapshot,
)

interface HlsCaptureSource {
    fun loadPlaylist(
        url: String,
        afterSequenceExclusive: Long? = null,
    ): LoadedHlsPlaylist

    fun copySegment(
        url: String,
        byteRange: HlsByteRange?,
        output: OutputStream,
        cancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): StreamRecorder.Result

    /** Fetches an AES-128 key. The bytes stay in memory for one recording and are never logged. */
    fun loadKey(url: String): ByteArray
}

private const val MAX_CACHED_KEYS = 4

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
        GAP,
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
        var initializationUri: String? = null
        var mediaWritten = false
        val keys = LinkedHashMap<String, ByteArray>()
        // Keys are cached by absolute URL, a few at a time, so rotating keys cannot grow memory.
        fun keyFor(resolvedUrl: String): ByteArray? {
            keys[resolvedUrl]?.let { return it }
            val fetched = source.loadKey(resolvedUrl)
            if (fetched.size != HlsAes128.KEY_BYTES) return null
            if (keys.size >= MAX_CACHED_KEYS) keys.remove(keys.keys.first())
            keys[resolvedUrl] = fetched
            return fetched
        }

        while (true) {
            if (cancelled()) return Result(totalBytes, lastSequence, StopReason.CANCELLED)
            if (nowEpochSeconds() >= endEpochSeconds) {
                return Result(totalBytes, lastSequence, StopReason.DEADLINE)
            }

            val loaded = source.loadPlaylist(playlistUrl, lastSequence)
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
                    var variant: HlsVariant? = null
                    for (candidate in playlist.variants) {
                        val hasExternalAudio = candidate.audioGroup != null &&
                            candidate.audioGroup in playlist.externalAudioGroups
                        val hasExternalVideo = candidate.videoGroup != null &&
                            candidate.videoGroup in playlist.externalVideoGroups
                        if (hasExternalAudio || hasExternalVideo) continue

                        val current = variant
                        val candidateBandwidth = candidate.bandwidth ?: 0L
                        val currentBandwidth = current?.bandwidth ?: Long.MIN_VALUE
                        if (current == null ||
                            candidateBandwidth > currentBandwidth ||
                            (candidateBandwidth == currentBandwidth && candidate.uri > current.uri)
                        ) {
                            variant = candidate
                        }
                    }
                    val selected = variant ?: return Result(
                        totalBytes,
                        lastSequence,
                        StopReason.UNSUPPORTED,
                        "HLS master requires separate audio/video rendition capture",
                    )
                    playlistUrl = resolveHttp(loaded.effectiveUrl, selected.uri)
                    selectedVariant = true
                    continue
                }

                is HlsMediaPlaylist -> {
                    val available = playlist.segments
                    if (lastSequence != null && playlist.latestSequence != null &&
                        playlist.latestSequence < lastSequence
                    ) {
                        return Result(
                            totalBytes,
                            lastSequence,
                            StopReason.UNSUPPORTED,
                            "HLS media sequence reset",
                        )
                    }
                    if (lastSequence != null) {
                        val expected = if (lastSequence == Long.MAX_VALUE) Long.MAX_VALUE else lastSequence + 1
                        val firstAvailable = available.firstOrNull()?.sequence
                        if (firstAvailable != null && firstAvailable > expected) {
                            return Result(
                                totalBytes,
                                lastSequence,
                                StopReason.GAP,
                                "HLS segment gap: expected $expected but first available is $firstAvailable",
                            )
                        }
                    }

                    // Parsed HLS sequences are ordered. Select the first relevant
                    // index directly rather than allocating a filtered list on
                    // every playlist refresh throughout a long DVR recording.
                    var firstCandidate = when {
                        lastSequence == null && !playlist.endList -> available.lastIndex
                        else -> 0
                    }
                    if (lastSequence != null) {
                        while (firstCandidate < available.size &&
                            available[firstCandidate].sequence <= lastSequence
                        ) firstCandidate++
                    }

                    if (firstCandidate !in available.indices) {
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

                    for (index in firstCandidate until available.size) {
                        val segment = available[index]
                        if (cancelled()) {
                            return Result(totalBytes, lastSequence, StopReason.CANCELLED)
                        }
                        if (nowEpochSeconds() >= endEpochSeconds) {
                            return Result(totalBytes, lastSequence, StopReason.DEADLINE)
                        }
                        val keyed = !segment.keyMethod.equals("NONE", ignoreCase = true)
                        if (keyed && !segment.keyMethod.equals("AES-128", ignoreCase = true)) {
                            return Result(
                                totalBytes,
                                lastSequence,
                                StopReason.UNSUPPORTED,
                                "Encrypted HLS method is not supported (${segment.keyMethod})",
                            )
                        }
                        val keyFormat = segment.keyFormat
                        if (keyed && keyFormat != null && !keyFormat.equals("identity", ignoreCase = true)) {
                            return Result(
                                totalBytes,
                                lastSequence,
                                StopReason.UNSUPPORTED,
                                "Encrypted HLS key format is not supported",
                            )
                        }
                        if (keyed && segment.initializationMapUri != null) {
                            return Result(
                                totalBytes,
                                lastSequence,
                                StopReason.UNSUPPORTED,
                                "Encrypted fMP4 initialization is not supported",
                            )
                        }
                        val mapUri = segment.initializationMapUri
                        if (mapUri != null) {
                            if (segment.initializationMapHasByteRange) {
                                return Result(
                                    totalBytes,
                                    lastSequence,
                                    StopReason.UNSUPPORTED,
                                    "fMP4 initialization byte range is not supported",
                                )
                            }
                            val written = initializationUri
                            if (written == null && mediaWritten) {
                                return Result(
                                    totalBytes,
                                    lastSequence,
                                    StopReason.UNSUPPORTED,
                                    "fMP4 initialization appeared after media was recorded",
                                )
                            }
                            if (written != null && written != mapUri) {
                                return Result(
                                    totalBytes,
                                    lastSequence,
                                    StopReason.UNSUPPORTED,
                                    "fMP4 initialization changed during recording",
                                )
                            }
                            if (written == null) {
                                // Written once, before the first fragment. Later fragments depend on it.
                                val before = totalBytes
                                val initCopy = source.copySegment(
                                    url = resolveHttp(loaded.effectiveUrl, mapUri),
                                    byteRange = null,
                                    output = output,
                                    cancelled = cancelled,
                                    onProgress = { bytes -> onProgress(before + bytes) },
                                )
                                totalBytes = Math.addExact(totalBytes, initCopy.bytesWritten)
                                when (initCopy.stopReason) {
                                    StreamRecorder.StopReason.END_OF_STREAM -> {
                                        if (initCopy.bytesWritten == 0L) {
                                            return Result(
                                                totalBytes,
                                                lastSequence,
                                                StopReason.UNSUPPORTED,
                                                "fMP4 initialization segment is empty",
                                            )
                                        }
                                        initializationUri = mapUri
                                        onProgress(totalBytes)
                                    }
                                    StreamRecorder.StopReason.CANCELLED ->
                                        return Result(totalBytes, lastSequence, StopReason.CANCELLED)
                                    StreamRecorder.StopReason.LOW_STORAGE ->
                                        return Result(totalBytes, lastSequence, StopReason.LOW_STORAGE)
                                }
                            }
                        }

                        val segmentUrl = resolveHttp(loaded.effectiveUrl, segment.uri)
                        val before = totalBytes
                        val decryptor = if (keyed) {
                            val keyUri = segment.keyUri ?: return Result(
                                totalBytes, lastSequence, StopReason.UNSUPPORTED, "AES-128 key URI is missing",
                            )
                            val key = keyFor(resolveHttp(loaded.effectiveUrl, keyUri)) ?: return Result(
                                totalBytes, lastSequence, StopReason.UNSUPPORTED, "AES-128 key must be 16 bytes",
                            )
                            val iv = HlsAes128.initializationVector(segment.keyIv, segment.sequence) ?: return Result(
                                totalBytes, lastSequence, StopReason.UNSUPPORTED, "AES-128 IV is malformed",
                            )
                            HlsAes128.Decryptor(key, iv, output)
                        } else {
                            null
                        }
                        val copy = source.copySegment(
                            url = segmentUrl,
                            byteRange = segment.byteRange,
                            output = decryptor ?: output,
                            cancelled = cancelled,
                            // Progress counts plaintext on disk. The copy counts the ciphertext it read.
                            onProgress = { segmentBytes ->
                                onProgress(before + (decryptor?.plaintextBytes ?: segmentBytes))
                            },
                        )
                        if (decryptor != null && copy.stopReason == StreamRecorder.StopReason.END_OF_STREAM) {
                            try {
                                decryptor.finish()
                            } catch (error: IOException) {
                                totalBytes = Math.addExact(totalBytes, decryptor.plaintextBytes)
                                return Result(
                                    totalBytes,
                                    lastSequence,
                                    StopReason.UNSUPPORTED,
                                    "Encrypted HLS segment could not be decrypted",
                                )
                            }
                        }
                        // Byte-range and body-length checks use ciphertext. Disk totals use plaintext.
                        totalBytes = Math.addExact(totalBytes, decryptor?.plaintextBytes ?: copy.bytesWritten)

                        when (copy.stopReason) {
                            StreamRecorder.StopReason.END_OF_STREAM -> {
                                val expectedBytes = segment.byteRange?.length
                                if (expectedBytes != null && copy.bytesWritten != expectedBytes) {
                                    return Result(
                                        totalBytes,
                                        lastSequence,
                                        StopReason.GAP,
                                        "HLS byte range incomplete: expected $expectedBytes bytes, " +
                                            "received ${copy.bytesWritten}",
                                    )
                                }
                                lastSequence = segment.sequence
                                mediaWritten = true
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

    /** Clamp seconds before multiplication so malformed manifests cannot overflow into rapid polls. */
    private fun pollDelayMillis(targetDurationSeconds: Long?): Long =
        (targetDurationSeconds ?: 4L).coerceIn(2L, 20L) * 500L

    private fun resolveHttp(base: String, relative: String): String {
        val baseUri = URI(base)
        val resolved = baseUri.resolve(relative)
        require((resolved.scheme.equals("http", ignoreCase = true) ||
            resolved.scheme.equals("https", ignoreCase = true)) &&
            !resolved.host.isNullOrBlank()) {
            "HLS resource must use HTTP or HTTPS"
        }
        require(!(baseUri.scheme.equals("https", ignoreCase = true) &&
            resolved.scheme.equals("http", ignoreCase = true))) {
            "HLS HTTPS downgrade blocked"
        }
        return resolved.toString()
    }
}
