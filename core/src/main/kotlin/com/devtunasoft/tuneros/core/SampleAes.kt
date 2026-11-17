package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * SAMPLE-AES for MPEG-2 transport stream segments with an identity key.
 *
 * UNVERIFIED: no SAMPLE-AES stream with its key has been checked against a real packager or player. The sample rules
 * follow Apple's archived sample-encryption notes. The transport-packet handling is this code's own.
 *
 * Video (H.264, stream type 0x1B): in a NAL unit of type 1 or 5 whose RBSP is longer than 48 bytes, the first 32 bytes
 * stay clear. Then one 16-byte block is encrypted and the next nine stay clear, for as long as more than 16 bytes remain.
 * The CBC chain runs over the encrypted blocks only and restarts in each NAL unit. Emulation-prevention bytes are removed
 * before the rule is applied and written again after it.
 *
 * Audio (AAC in ADTS, stream type 0x0F): each frame keeps its header and its first 16 payload bytes clear. The whole
 * 16-byte blocks after them form one CBC chain that restarts in each frame. A final partial block stays clear.
 *
 * Packets: each PES unit keeps its own transport packets. Its rewritten bytes go back into them, and the stuffing of each
 * adaptation field takes up any change in size. Adaptation flags and the PCR are kept. A segment that cannot be written
 * back this way is refused, and so is one with AC-3, E-AC-3, HEVC, MPEG or LATM media, or PES private data. Other stream
 * types, such as metadata, are copied unchanged.
 */
internal object SampleAes {
    /** The largest segment that is rewritten, because the whole segment is held in memory while it is. */
    const val MAX_SEGMENT_BYTES = 8 * 1024 * 1024

    private const val PACKET = 188
    private const val PACKET_BODY = 184
    private const val SYNC_BYTE = 0x47
    private const val BLOCK = 16
    private const val CLEAR_VIDEO_LEAD = 32
    private const val SKIPPED_BLOCKS = 9
    private const val MIN_ENCRYPTED_RBSP = 48
    private const val CLEAR_AUDIO_LEAD = 16
    private const val PAT_PID = 0
    private const val TABLE_PAT = 0x00
    private const val TABLE_PMT = 0x02
    private const val STREAM_H264 = 0x1B
    private const val STREAM_AAC = 0x0F
    private val REFUSED_STREAM_TYPES = setOf(0x01, 0x02, 0x03, 0x04, 0x06, 0x11, 0x24, 0x80, 0x81, 0x83, 0x87)
    private val NO_OPTIONAL_HEADER = setOf(0xBC, 0xBE, 0xBF, 0xF0, 0xF1, 0xF2, 0xF8, 0xFF)

    /** Decrypts a whole segment, or returns null when this code cannot rewrite it. A wrong key still gives bytes. */
    fun decryptSegment(segment: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? =
        transform(segment, key, iv, decrypt = true)

    /** The encrypting direction, so tests can make SAMPLE-AES input from a clear segment. */
    internal fun encryptSegment(segment: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? =
        transform(segment, key, iv, decrypt = false)

    private fun transform(segment: ByteArray, key: ByteArray, iv: ByteArray, decrypt: Boolean): ByteArray? {
        if (key.size != BLOCK || iv.size != BLOCK) return null
        if (segment.isEmpty() || segment.size % PACKET != 0 || segment.size > MAX_SEGMENT_BYTES) return null
        val streams = mediaStreams(segment) ?: return null
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val output = segment.copyOf()
        var rewritten = 0
        for ((pid, streamType) in streams) {
            for (unit in pesUnits(segment, pid) ?: return null) {
                val pes = transformPes(unit.bytes(segment), streamType, cipher, iv, decrypt) ?: return null
                if (!writeBack(segment, output, unit, pes)) return null
                rewritten++
            }
        }
        return if (rewritten > 0 && consistent(output, streams)) output else null
    }

    private fun transformPes(
        pes: ByteArray,
        streamType: Int,
        cipher: Cipher,
        iv: ByteArray,
        decrypt: Boolean,
    ): ByteArray? {
        if (pes.size < 9 || pes[0] != 0.toByte() || pes[1] != 0.toByte() || pes[2] != 1.toByte()) return null
        val streamId = pes[3].toInt() and 0xFF
        val headerLength = if (streamId in NO_OPTIONAL_HEADER) 6 else 9 + (pes[8].toInt() and 0xFF)
        if (headerLength > pes.size) return null
        val elementary = pes.copyOfRange(headerLength, pes.size)
        val rewritten = (if (streamType == STREAM_H264) {
            transformVideo(elementary, cipher, iv, decrypt)
        } else {
            transformAudio(elementary, cipher, iv, decrypt)
        }) ?: return null
        val result = pes.copyOfRange(0, headerLength) + rewritten
        val declared = ((pes[4].toInt() and 0xFF) shl 8) or (pes[5].toInt() and 0xFF)
        if (declared != 0) {
            val length = result.size - 6
            when {
                length <= 0xFFFF -> {
                    result[4] = (length ushr 8).toByte()
                    result[5] = length.toByte()
                }
                // Video may be unbounded (length 0) in a transport stream. Audio has no such allowance.
                streamType == STREAM_H264 -> {
                    result[4] = 0.toByte()
                    result[5] = 0.toByte()
                }
                else -> return null
            }
        }
        return result
    }

    private fun transformVideo(es: ByteArray, cipher: Cipher, iv: ByteArray, decrypt: Boolean): ByteArray? {
        val starts = startCodes(es)
        if (starts.isEmpty()) return null
        val out = ByteArrayOutputStream(es.size + 64)
        out.write(es, 0, starts[0])
        for (index in starts.indices) {
            val prefixEnd = starts[index] + 3
            val next = if (index + 1 < starts.size) starts[index + 1] else es.size
            // Zero bytes before the next start code are trailing_zero_8bits, so they are not part of this NAL unit.
            var nalEnd = next
            while (nalEnd > prefixEnd && es[nalEnd - 1] == 0.toByte()) nalEnd--
            out.write(es, starts[index], 3)
            out.write(transformNal(es.copyOfRange(prefixEnd, nalEnd), cipher, iv, decrypt) ?: return null)
            out.write(es, nalEnd, next - nalEnd)
        }
        return out.toByteArray()
    }

    private fun startCodes(es: ByteArray): List<Int> {
        val found = ArrayList<Int>()
        var index = 0
        while (index + 2 < es.size) {
            if (es[index] == 0.toByte() && es[index + 1] == 0.toByte() && es[index + 2] == 1.toByte()) {
                found += index
                index += 3
            } else {
                index++
            }
        }
        return found
    }

    private fun transformNal(nal: ByteArray, cipher: Cipher, iv: ByteArray, decrypt: Boolean): ByteArray? {
        if (nal.isEmpty()) return nal
        val type = nal[0].toInt() and 0x1F
        if (type != 1 && type != 5) return nal
        val rbsp = removeEmulationPrevention(nal)
        if (rbsp.size <= MIN_ENCRYPTED_RBSP) return nal
        var core = rbsp.size
        while (core > 0 && rbsp[core - 1] == 0.toByte()) core--
        // Zero bytes at the end of the RBSP are cabac_zero_words, which come in pairs. The last byte of the core is never
        // encrypted, so decryption finds the same count of zeros and the same boundary.
        if ((rbsp.size - core) % 2 != 0) return null
        patternInPlace(cipher, rbsp, core, iv, decrypt)
        return insertEmulationPrevention(rbsp)
    }

    /** One block in ten from byte 32 on, while more than one block remains before [end]. The chain skips clear blocks. */
    private fun patternInPlace(cipher: Cipher, data: ByteArray, end: Int, iv: ByteArray, decrypt: Boolean) {
        var chain = iv
        var position = CLEAR_VIDEO_LEAD
        while (end - position > BLOCK) {
            chain = cbcBlock(cipher, data, position, chain, decrypt)
            position += BLOCK + minOf(SKIPPED_BLOCKS * BLOCK, end - position - BLOCK)
        }
    }

    private fun transformAudio(es: ByteArray, cipher: Cipher, iv: ByteArray, decrypt: Boolean): ByteArray? {
        val out = es.copyOf()
        var position = 0
        while (position < out.size) {
            if (out.size - position < 7) return null
            val first = out[position].toInt() and 0xFF
            val second = out[position + 1].toInt() and 0xFF
            if (first != 0xFF || (second and 0xF0) != 0xF0) return null
            val headerLength = if ((second and 0x01) == 1) 7 else 9
            val frameLength = ((out[position + 3].toInt() and 0x03) shl 11) or
                ((out[position + 4].toInt() and 0xFF) shl 3) or
                ((out[position + 5].toInt() and 0xE0) ushr 5)
            if (frameLength < headerLength || position + frameLength > out.size) return null
            val payloadLength = frameLength - headerLength
            if (payloadLength > CLEAR_AUDIO_LEAD) {
                val encrypted = ((payloadLength - CLEAR_AUDIO_LEAD) / BLOCK) * BLOCK
                cbcInPlace(cipher, out, position + headerLength + CLEAR_AUDIO_LEAD, encrypted, iv, decrypt)
            }
            position += frameLength
        }
        return out
    }

    /** CBC over [length] bytes from [from], with a fresh chain from [iv]. The length is a multiple of 16. */
    private fun cbcInPlace(cipher: Cipher, data: ByteArray, from: Int, length: Int, iv: ByteArray, decrypt: Boolean) {
        var chain = iv
        var position = from
        while (position < from + length) {
            chain = cbcBlock(cipher, data, position, chain, decrypt)
            position += BLOCK
        }
    }

    /** Encrypts or decrypts the block at [position] in place, chained from [chain]. Returns the next chaining value. */
    private fun cbcBlock(cipher: Cipher, data: ByteArray, position: Int, chain: ByteArray, decrypt: Boolean): ByteArray {
        val block = data.copyOfRange(position, position + BLOCK)
        if (decrypt) {
            xor(cipher.doFinal(block), chain).copyInto(data, position)
            return block
        }
        val encrypted = cipher.doFinal(xor(block, chain))
        encrypted.copyInto(data, position)
        return encrypted
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray =
        ByteArray(BLOCK) { index -> (a[index].toInt() xor b[index].toInt()).toByte() }

    private fun removeEmulationPrevention(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (byte in nal) {
            val value = byte.toInt() and 0xFF
            if (zeros >= 2 && value == 3) {
                zeros = 0
                continue
            }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun insertEmulationPrevention(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size + 16)
        var zeros = 0
        for (byte in rbsp) {
            val value = byte.toInt() and 0xFF
            if (zeros >= 2 && value <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        // A NAL unit cannot end in 0x00, so a final 0x03 follows an RBSP that ends in cabac_zero_words.
        if (rbsp.isNotEmpty() && rbsp[rbsp.size - 1] == 0.toByte()) out.write(3)
        return out.toByteArray()
    }

    /** The H.264 and AAC PIDs with their stream types, or null when a table is unreadable or a refused stream is listed. */
    private fun mediaStreams(segment: ByteArray): Map<Int, Int>? {
        val pat = sectionOf(segment, PAT_PID) ?: return null
        if (pat.size < 8 || pat[0] != TABLE_PAT.toByte()) return null
        val patEnd = sectionEnd(pat) ?: return null
        val pmtPids = LinkedHashSet<Int>()
        var position = 8
        while (position + 4 <= patEnd) {
            val program = ((pat[position].toInt() and 0xFF) shl 8) or (pat[position + 1].toInt() and 0xFF)
            val pid = ((pat[position + 2].toInt() and 0x1F) shl 8) or (pat[position + 3].toInt() and 0xFF)
            if (program != 0) pmtPids += pid
            position += 4
        }
        if (pmtPids.isEmpty()) return null
        val streams = LinkedHashMap<Int, Int>()
        for (pmtPid in pmtPids) {
            val pmt = sectionOf(segment, pmtPid) ?: return null
            if (pmt.size < 12 || pmt[0] != TABLE_PMT.toByte()) return null
            val pmtEnd = sectionEnd(pmt) ?: return null
            position = 12 + (((pmt[10].toInt() and 0x0F) shl 8) or (pmt[11].toInt() and 0xFF))
            if (position > pmtEnd) return null
            while (position + 5 <= pmtEnd) {
                val type = pmt[position].toInt() and 0xFF
                val pid = ((pmt[position + 1].toInt() and 0x1F) shl 8) or (pmt[position + 2].toInt() and 0xFF)
                val infoLength = ((pmt[position + 3].toInt() and 0x0F) shl 8) or (pmt[position + 4].toInt() and 0xFF)
                if (type in REFUSED_STREAM_TYPES) return null
                if (type == STREAM_H264 || type == STREAM_AAC) streams[pid] = type
                position += 5 + infoLength
            }
        }
        return if (streams.isEmpty()) null else streams
    }

    /** Index of the section's CRC, or null when the section runs past the bytes that were read for it. */
    private fun sectionEnd(section: ByteArray): Int? {
        val end = 3 + (((section[1].toInt() and 0x0F) shl 8) or (section[2].toInt() and 0xFF))
        return if (end <= section.size) end - 4 else null
    }

    /** The first table section that starts in a packet of [pid], from its pointer field to the end of that packet. */
    private fun sectionOf(segment: ByteArray, pid: Int): ByteArray? {
        var offset = 0
        while (offset < segment.size) {
            val packet = parsePacket(segment, offset) ?: return null
            if (packet.pid == pid && packet.pusi && packet.payloadLength > 0) {
                val start = packet.payloadStart + 1 + (segment[packet.payloadStart].toInt() and 0xFF)
                val end = packet.payloadStart + packet.payloadLength
                return if (start < end) segment.copyOfRange(start, end) else null
            }
            offset += PACKET
        }
        return null
    }

    private class Packet(
        val offset: Int,
        val pid: Int,
        val pusi: Boolean,
        val adaptationTotal: Int,
        val payloadStart: Int,
        val payloadLength: Int,
    )

    private fun parsePacket(segment: ByteArray, offset: Int): Packet? {
        if ((segment[offset].toInt() and 0xFF) != SYNC_BYTE) return null
        val pid = ((segment[offset + 1].toInt() and 0x1F) shl 8) or (segment[offset + 2].toInt() and 0xFF)
        val pusi = (segment[offset + 1].toInt() and 0x40) != 0
        val control = (segment[offset + 3].toInt() shr 4) and 0x03
        val adaptationTotal = if ((control and 0x02) != 0) 1 + (segment[offset + 4].toInt() and 0xFF) else 0
        if (adaptationTotal > PACKET_BODY) return null
        val payloadLength = if ((control and 0x01) != 0) PACKET_BODY - adaptationTotal else 0
        return Packet(offset, pid, pusi, adaptationTotal, offset + 4 + adaptationTotal, payloadLength)
    }

    private class PesUnit {
        val packets = ArrayList<Packet>()

        fun bytes(segment: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(packets.sumOf { it.payloadLength })
            for (packet in packets) out.write(segment, packet.payloadStart, packet.payloadLength)
            return out.toByteArray()
        }
    }

    /**
     * The PES units of [pid]. Each starts at a packet with the payload-unit-start flag. Payload that arrives before the
     * first start is refused.
     */
    private fun pesUnits(segment: ByteArray, pid: Int): List<PesUnit>? {
        val units = ArrayList<PesUnit>()
        var offset = 0
        while (offset < segment.size) {
            val packet = parsePacket(segment, offset) ?: return null
            offset += PACKET
            if (packet.pid != pid || packet.payloadLength == 0) continue
            if (packet.pusi) units += PesUnit()
            val current = units.lastOrNull() ?: return null
            current.packets += packet
        }
        return units
    }

    /**
     * Writes a rewritten PES unit back over its own packets. Each packet takes as much payload as its adaptation field
     * leaves room for, in order, and keeps at least one byte for every packet after it, so none goes empty and the
     * continuity counters stay valid. Bytes that do not fit are an error.
     */
    private fun writeBack(segment: ByteArray, output: ByteArray, unit: PesUnit, pes: ByteArray): Boolean {
        var cursor = 0
        val packets = unit.packets
        for (index in packets.indices) {
            val packet = packets[index]
            val kept = keptAdaptationBytes(segment, packet) ?: return false
            val room = if (kept == 0) PACKET_BODY else PACKET_BODY - kept
            val laterPackets = packets.size - index - 1
            val payload = minOf(room, pes.size - cursor - laterPackets)
            if (payload < 1) return false
            writePacket(segment, output, packet, kept, pes, cursor, payload)
            cursor += payload
        }
        return cursor == pes.size
    }

    private fun writePacket(
        segment: ByteArray,
        output: ByteArray,
        packet: Packet,
        kept: Int,
        pes: ByteArray,
        cursor: Int,
        payload: Int,
    ) {
        val base = packet.offset
        // Adaptation section bytes, length byte included. Zero when the packet has no adaptation field.
        val adaptation = PACKET_BODY - payload
        output[base + 3] = ((segment[base + 3].toInt() and 0x0F) or (if (adaptation == 0) 0x10 else 0x30)).toByte()
        if (adaptation > 0) {
            output[base + 4] = (adaptation - 1).toByte()
            if (adaptation >= 2) {
                if (kept >= 2) {
                    // The flags and optional fields (such as the PCR) are copied; stuffing fills the rest.
                    System.arraycopy(segment, base + 5, output, base + 5, kept - 1)
                } else {
                    output[base + 5] = 0
                }
                output.fill(0xFF.toByte(), base + 4 + maxOf(kept, 2), base + 4 + adaptation)
            }
        }
        System.arraycopy(pes, cursor, output, base + 4 + adaptation, payload)
    }

    /**
     * Bytes of the packet's adaptation field that carry data: the length byte, the flags and any optional fields. Stuffing
     * is not counted. Null when the field is malformed.
     */
    private fun keptAdaptationBytes(segment: ByteArray, packet: Packet): Int? {
        val total = packet.adaptationTotal
        if (total == 0) return 0
        if (total == 1) return 1
        val start = packet.offset + 4
        val end = start + total
        val flags = segment[start + 1].toInt() and 0xFF
        var position = start + 2
        if ((flags and 0x10) != 0) position += 6
        if ((flags and 0x08) != 0) position += 6
        if ((flags and 0x04) != 0) position += 1
        if ((flags and 0x02) != 0) {
            if (position >= end) return null
            position += 1 + (segment[position].toInt() and 0xFF)
        }
        if ((flags and 0x01) != 0) {
            if (position >= end) return null
            position += 1 + (segment[position].toInt() and 0xFF)
        }
        return if (position <= end) position - start else null
    }

    /** Each rewritten unit still declares the length of its own bytes, so the packet layout matches the PES. */
    private fun consistent(output: ByteArray, streams: Map<Int, Int>): Boolean {
        for (pid in streams.keys) {
            for (unit in pesUnits(output, pid) ?: return false) {
                val pes = unit.bytes(output)
                if (pes.size < 6) return false
                val declared = ((pes[4].toInt() and 0xFF) shl 8) or (pes[5].toInt() and 0xFF)
                if (declared != 0 && declared != pes.size - 6) return false
            }
        }
        return true
    }
}
