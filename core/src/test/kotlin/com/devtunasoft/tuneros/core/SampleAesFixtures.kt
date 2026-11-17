package com.devtunasoft.tuneros.core

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Synthetic transport streams for the SAMPLE-AES tests. No provider data is involved. */
internal object SampleAesFixtures {
    const val VIDEO_PID = 0x100
    const val AUDIO_PID = 0x101
    private const val PMT_PID = 0x1000
    private const val EXTRA_PID = 0x102
    private const val PACKET = 188
    private const val PACKET_BODY = 184

    val key = ByteArray(16) { (it * 7 + 3).toByte() }
    val iv = ByteArray(16) { (it * 11 + 5).toByte() }
    private val pcr = byteArrayOf(0, 0, 0, 0, 0x7E, 0x01)

    fun nal(header: Int, body: ByteArray): ByteArray = byteArrayOf(header.toByte()) + body

    /** Annex B byte stream with a four-byte start code before each NAL unit. */
    fun annexB(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(nal)
        }
        return out.toByteArray()
    }

    /** Escapes a NAL unit the way an encoder does: 0x03 before a byte of 0x00..0x03 that follows two zeros. */
    fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
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
        if (rbsp.isNotEmpty() && rbsp[rbsp.size - 1] == 0.toByte()) out.write(3)
        return out.toByteArray()
    }

    /** One ADTS frame: a 7-byte header without CRC, then the payload. */
    fun adts(payload: ByteArray): ByteArray {
        val length = 7 + payload.size
        val header = byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            0x50,
            (0x80 or (length ushr 11)).toByte(),
            (length ushr 3).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
        return header + payload
    }

    /** A PES packet with no optional fields. Video may be unbounded (length 0), as encoders usually write it. */
    fun pes(streamId: Int, es: ByteArray, bounded: Boolean): ByteArray {
        val length = if (bounded) 3 + es.size else 0
        val header = byteArrayOf(
            0, 0, 1, streamId.toByte(), (length ushr 8).toByte(), length.toByte(), 0x80.toByte(), 0, 0,
        )
        return header + es
    }

    /** PAT, PMT, an H.264 PES with a PCR on its first packet and, when given, an AAC PES. [extraType] lists one more stream. */
    fun segment(video: ByteArray, audio: ByteArray? = null, extraType: Int? = null): ByteArray {
        val mux = Muxer()
        mux.unit(0, byteArrayOf(0) + pat(), withPcr = false)
        mux.unit(PMT_PID, byteArrayOf(0) + pmt(extraType), withPcr = false)
        mux.unit(VIDEO_PID, pes(0xE0, video, bounded = false), withPcr = true)
        if (audio != null) mux.unit(AUDIO_PID, pes(0xC0, audio, bounded = true), withPcr = false)
        return mux.out.toByteArray()
    }

    /** Program tables only, with no media. */
    fun tablesOnly(): ByteArray {
        val mux = Muxer()
        mux.unit(0, byteArrayOf(0) + pat(), withPcr = false)
        mux.unit(PMT_PID, byteArrayOf(0) + pmt(null), withPcr = false)
        return mux.out.toByteArray()
    }

    /** The payload bytes of [pid] in [segment], in order. */
    fun payloadOf(segment: ByteArray, pid: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < segment.size) {
            val packetPid = ((segment[offset + 1].toInt() and 0x1F) shl 8) or (segment[offset + 2].toInt() and 0xFF)
            val control = (segment[offset + 3].toInt() shr 4) and 0x03
            var start = offset + 4
            if ((control and 0x02) != 0) start += 1 + (segment[offset + 4].toInt() and 0xFF)
            if (packetPid == pid && (control and 0x01) != 0) out.write(segment, start, offset + PACKET - start)
            offset += PACKET
        }
        return out.toByteArray()
    }

    /** The byte offset of the first packet of [pid], or -1. */
    fun firstPacketOf(segment: ByteArray, pid: Int): Int {
        var offset = 0
        while (offset < segment.size) {
            val packetPid = ((segment[offset + 1].toInt() and 0x1F) shl 8) or (segment[offset + 2].toInt() and 0xFF)
            if (packetPid == pid) return offset
            offset += PACKET
        }
        return -1
    }

    fun startCodeCount(es: ByteArray): Int {
        var count = 0
        for (index in 0 until es.size - 2) {
            if (es[index] == 0.toByte() && es[index + 1] == 0.toByte() && es[index + 2] == 1.toByte()) count++
        }
        return count
    }

    /** Independent reference: the JDK's own CBC mode over whole blocks. */
    fun cbc(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    private fun pat(): ByteArray = byteArrayOf(
        0x00, 0xB0.toByte(), 0x0D,
        0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
        0x00, 0x01,
        (0xE0 or (PMT_PID ushr 8)).toByte(), PMT_PID.toByte(),
        0, 0, 0, 0,
    )

    private fun pmt(extraType: Int?): ByteArray {
        val entries = ByteArrayOutputStream()
        fun entry(type: Int, pid: Int) {
            entries.write(type)
            entries.write(0xE0 or (pid ushr 8))
            entries.write(pid)
            entries.write(0xF0)
            entries.write(0x00)
        }
        entry(0x1B, VIDEO_PID)
        entry(0x0F, AUDIO_PID)
        if (extraType != null) entry(extraType, EXTRA_PID)
        val list = entries.toByteArray()
        val head = byteArrayOf(
            0x02, 0xB0.toByte(), (13 + list.size).toByte(),
            0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            (0xE0 or (VIDEO_PID ushr 8)).toByte(), VIDEO_PID.toByte(),
            0xF0.toByte(), 0x00,
        )
        return head + list + byteArrayOf(0, 0, 0, 0)
    }

    /** Writes one unit of table or PES data as transport packets. Only the first packet has the payload-unit-start flag. */
    private class Muxer {
        val out = ByteArrayOutputStream()
        private val counters = HashMap<Int, Int>()

        fun unit(pid: Int, data: ByteArray, withPcr: Boolean) {
            var offset = 0
            var first = true
            while (offset < data.size) {
                val pcrHere = first && withPcr
                val fixed = if (pcrHere) 8 else 0
                val remaining = data.size - offset
                val payload: Int
                val adaptation: Int
                if (remaining >= PACKET_BODY - fixed) {
                    payload = PACKET_BODY - fixed
                    adaptation = fixed
                } else {
                    payload = remaining
                    adaptation = PACKET_BODY - remaining
                }
                val counter = counters[pid] ?: 0
                counters[pid] = (counter + 1) and 0x0F
                val packet = ByteArray(PACKET) { 0xFF.toByte() }
                packet[0] = 0x47
                packet[1] = ((if (first) 0x40 else 0x00) or (pid ushr 8)).toByte()
                packet[2] = pid.toByte()
                packet[3] = ((if (adaptation > 0) 0x30 else 0x10) or counter).toByte()
                if (adaptation > 0) {
                    packet[4] = (adaptation - 1).toByte()
                    if (adaptation >= 2) {
                        packet[5] = (if (pcrHere) 0x10 else 0x00).toByte()
                        if (pcrHere) pcr.copyInto(packet, 6)
                    }
                }
                data.copyInto(packet, 4 + adaptation, offset, offset + payload)
                out.write(packet)
                offset += payload
                first = false
            }
        }
    }
}
