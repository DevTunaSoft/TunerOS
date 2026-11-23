package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Deterministic boundary coverage for the two-byte gzip magic probe in [SourceDownloader].
 * Source bodies here are one or two synthetic bytes: the range where the probe reads
 * fewer bytes than its pushback buffer holds. No provider URL, credential or media is used.
 */
private class SniffBoundaryConnection(url: URL, private val body: ByteArray) : HttpURLConnection(url) {
    var disconnects = 0
        private set

    override fun connect() {}
    override fun disconnect() { disconnects++ }
    override fun usingProxy() = false
    override fun getResponseCode(): Int = HttpURLConnection.HTTP_OK
    override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    override fun getHeaderField(name: String?): String? = null
    override fun getContentEncoding(): String? = null
}

class SourceSniffBoundaryRegressionTest {
    private fun open(body: ByteArray, maxBytes: Long = 64): Pair<InputStream, SniffBoundaryConnection> {
        val connection = SniffBoundaryConnection(URL("https://fixture.invalid/feed"), body)
        val source = SourceDownloader { connection }.open("https://fixture.invalid/feed", maxBytes)
        return source to connection
    }

    @Test fun aOneByteBodySurvivesTheTwoByteMagicProbe() {
        val (source, connection) = open(byteArrayOf(0x41), 1)
        source.use {
            assertEquals(0x41, it.read())
            assertEquals(-1, it.read())
        }
        assertEquals(1, connection.disconnects)
    }

    @Test fun aTwoByteBodyKeepsBothProbedBytesInTheirOriginalOrder() {
        val (source, _) = open(byteArrayOf(0x42, 0x43), 2)
        source.use {
            assertEquals(0x42, it.read())
            assertEquals(0x43, it.read())
            assertEquals(-1, it.read())
        }
    }

    @Test fun aSingleProbedByteIsCountedByTheFollowingBulkRead() {
        val (source, _) = open(byteArrayOf(0x44), 1)
        source.use {
            val buffer = ByteArray(8)
            assertEquals(1, it.read(buffer, 0, buffer.size))
            assertEquals(0x44, buffer[0].toInt())
            assertEquals(-1, it.read(buffer, 0, buffer.size))
        }
    }

    @Test fun aTwoByteBodyBeyondTheBudgetFailsClosedAndDisconnects() {
        val (source, connection) = open(byteArrayOf(0x45, 0x46), 1)
        val failure = assertFailsWith<IllegalArgumentException> {
            source.use { it.readBytes() }
        }
        assertEquals("Source exceeds decompressed size limit", failure.message)
        assertFalse(failure.toString().contains("fixture.invalid"))
        assertEquals(1, connection.disconnects)
    }

    @Test fun anIncompleteGzipMagicPrefixIsTreatedAsPlainData() {
        val (source, _) = open(byteArrayOf(0x1f), 1)
        source.use { assertEquals(0x1f, it.read()) }
    }
}
