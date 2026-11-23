package com.devtunasoft.tuneros.qa15

import java.io.Writer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Random

/**
 * QA-15 scratch fixture: deterministic, bounded, dependency-free.
 *
 * All identifiers are local scratch tokens. No provider address, credential, PIN,
 * DRM key, media payload or real guide data is generated. The same generator is
 * used by the host gate and by the Android instrumentation mirror so both digest
 * the identical byte stream.
 */
object Qa15Fixture {
    const val VERSION = "QA15-REPEATED-EPG-V1"
    const val SEED = 20261123L
    const val CHANNELS = 4096
    const val PROGRAMS_PER_CHANNEL = 24
    const val SLOT_SECONDS = 1800L
    const val WINDOW_SLOTS = 6
    const val BASE_EPOCH = 1_800_000_000L
    const val WINDOW_FROM = BASE_EPOCH + 4L * SLOT_SECONDS
    const val WINDOW_TO = WINDOW_FROM + WINDOW_SLOTS * SLOT_SECONDS
    const val CYCLES = 6
    const val MAX_ROW_CELLS = 8
    const val CORRUPT_VARIANTS = 6

    // Mutating either prefix to a sensitive-looking token must trip the privacy check.
    const val TITLE_PREFIX = "Scratch Show"
    const val CHANNEL_PREFIX = "scratch-local:qa15/channel/"

    private val stampFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
    private val stamps: List<String> = List(PROGRAMS_PER_CHANNEL + 1) { slot ->
        stampFormat.format(Instant.ofEpochSecond(BASE_EPOCH + slot * SLOT_SECONDS).atOffset(ZoneOffset.UTC))
    }

    fun guideId(channel: Int): String = String.format(Locale.ROOT, "g-%05d", channel)

    private fun token(channel: Int): Int = Random(SEED + channel).nextInt(1_000_000)

    fun title(channel: Int, slot: Int): String =
        "$TITLE_PREFIX ${token(channel)}-${(slot * 7919) % 97}"

    fun writeXml(out: Writer) {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.write("<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n")
        out.write("<tv>\n")
        for (channel in 0 until CHANNELS) {
            out.write("<channel id=\"${guideId(channel)}\">")
            out.write("<display-name>Scratch Channel ${String.format(Locale.ROOT, "%05d", channel)}</display-name>")
            out.write("</channel>\n")
        }
        for (channel in 0 until CHANNELS) {
            val id = guideId(channel)
            for (slot in 0 until PROGRAMS_PER_CHANNEL) {
                out.write("<programme channel=\"$id\" start=\"${stamps[slot]}\" stop=\"${stamps[slot + 1]}\">")
                out.write("<title>${title(channel, slot)}</title>")
                out.write("</programme>\n")
            }
        }
        out.write("</tv>\n")
        out.flush()
    }

    /** Compact deterministic sidecar, digested by both host and device mirrors. */
    fun writeSidecar(out: Writer) {
        out.write("$VERSION\nseed=$SEED\n")
        out.write("channels=$CHANNELS programs=$PROGRAMS_PER_CHANNEL slot=$SLOT_SECONDS\n")
        for (channel in 0 until CHANNELS) {
            out.write("$channel,${token(channel)},${guideId(channel)},${title(channel, 0)}\n")
        }
        out.flush()
    }

    private class DigestWriter(private val digest: MessageDigest) : Writer() {
        private val scratch = CharArray(4096)
        override fun write(buffer: CharArray, offset: Int, length: Int) {
            val bytes = String(buffer, offset, length).toByteArray(Charsets.UTF_8)
            digest.update(bytes)
        }

        override fun flush() = Unit
        override fun close() = Unit
    }

    fun sidecarDigest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        writeSidecar(DigestWriter(digest))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
