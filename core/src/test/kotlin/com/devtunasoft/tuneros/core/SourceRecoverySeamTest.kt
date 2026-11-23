package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic network fixtures only: no real provider URL, credential or media is used. */
private class SeamStep(
    val status: Int = HttpURLConnection.HTTP_OK,
    val body: ByteArray = ByteArray(0),
    val location: String? = null,
    val encoding: String? = null,
    val inputOverride: InputStream? = null,
)

private class SeamConnection(
    url: URL,
    private val step: SeamStep,
    private val onDisconnect: () -> Unit,
) : HttpURLConnection(url) {
    override fun connect() {}
    override fun disconnect() { onDisconnect() }
    override fun usingProxy() = false
    override fun getResponseCode(): Int = step.status
    override fun getInputStream(): InputStream = step.inputOverride ?: ByteArrayInputStream(step.body)
    override fun getHeaderField(name: String?): String? =
        if (name.equals("Location", true)) step.location else null
    override fun getContentEncoding(): String? = step.encoding
}

private class SeamHarness(steps: List<SeamStep>) {
    private val pending = steps.toMutableList()
    val requested = mutableListOf<String>()
    var disconnects = 0
        private set
    val threads = mutableSetOf<Long>()

    val downloader = SourceDownloader { url ->
        requested += url.toString()
        threads += Thread.currentThread().id
        val step = pending.removeFirstOrNull() ?: error("Unexpected connection")
        SeamConnection(url, step) { disconnects++ }
    }
}

private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
    GZIPOutputStream(out).use { it.write(bytes) }
}.toByteArray()

private fun String.assertNoDisclosure() {
    for (needle in listOf("fixture.invalid", "SYNTHETIC_CANARY", "example", "token=")) {
        assertFalse(contains(needle, ignoreCase = true), "Diagnostic leaked '$needle': $this")
    }
}

class SourceRecoverySeamTest {
    @Test fun redirectChainIsBoundedAndEveryConnectionIsClosed() {
        val ok = SeamStep(body = "payload".toByteArray())
        val redirects = List(SourceDownloader.MAX_REDIRECTS) { index ->
            SeamStep(HttpURLConnection.HTTP_MOVED_TEMP, location = "/hop-$index")
        }
        val harness = SeamHarness(redirects + ok)
        val text = harness.downloader
            .open("https://fixture.invalid/start?token=SYNTHETIC_CANARY", 256)
            .use { it.bufferedReader().readText() }
        assertEquals("payload", text)
        assertEquals(SourceDownloader.MAX_REDIRECTS + 1, harness.requested.size)
        assertEquals(SourceDownloader.MAX_REDIRECTS + 1, harness.disconnects)
        assertEquals(setOf(Thread.currentThread().id), harness.threads)
    }

    @Test fun redirectBeyondCapIsRefusedWithoutConnectingFurther() {
        val tooMany = List(SourceDownloader.MAX_REDIRECTS + 2) { index ->
            SeamStep(HttpURLConnection.HTTP_MOVED_TEMP, location = "/loop-$index")
        }
        val harness = SeamHarness(tooMany)
        val error = assertFailsWith<IllegalArgumentException> {
            harness.downloader.open("https://fixture.invalid/loop?token=SYNTHETIC_CANARY", 256)
        }
        assertEquals("Too many redirects", error.message)
        error.toString().assertNoDisclosure()
        assertEquals(SourceDownloader.MAX_REDIRECTS + 1, harness.requested.size)
        assertEquals(harness.requested.size, harness.disconnects)
    }

    @Test fun redirectWithoutLocationIsRefusedAndClosed() {
        val harness = SeamHarness(listOf(SeamStep(HttpURLConnection.HTTP_MOVED_TEMP)))
        val error = assertFailsWith<IllegalArgumentException> {
            harness.downloader.open("https://fixture.invalid/noloc?token=SYNTHETIC_CANARY", 256)
        }
        assertEquals("Redirect without location", error.message)
        error.toString().assertNoDisclosure()
        assertEquals(1, harness.disconnects)
    }

    @Test fun httpsDowngradeIsBlockedBeforeTheInsecureHopIsOpened() {
        val harness = SeamHarness(listOf(
            SeamStep(HttpURLConnection.HTTP_MOVED_TEMP, location = "http://fixture.invalid/insecure"),
        ))
        val error = assertFailsWith<IllegalArgumentException> {
            harness.downloader.open("https://fixture.invalid/secure?token=SYNTHETIC_CANARY", 256)
        }
        assertEquals("HTTPS source redirected to insecure HTTP", error.message)
        error.toString().assertNoDisclosure()
        assertEquals(1, harness.requested.size)
        assertTrue(harness.requested.single().startsWith("https://"))
        assertEquals(1, harness.disconnects)
    }

    @Test fun nonSuccessStatusIsAStructuredErrorWithoutUrlOrCanary() {
        val harness = SeamHarness(listOf(SeamStep(HttpURLConnection.HTTP_UNAVAILABLE)))
        val error = assertFailsWith<IllegalArgumentException> {
            harness.downloader.open("https://fixture.invalid/down?token=SYNTHETIC_CANARY", 256)
        }
        assertEquals("Source returned HTTP 503", error.message)
        error.toString().assertNoDisclosure()
        assertEquals(1, harness.disconnects)
    }

    @Test fun malformedSourcesAreRejectedBeforeAnyConnection() {
        val harness = SeamHarness(emptyList())
        val bad = listOf(
            "",
            "file:///fixture.invalid/feed",
            "ftp://fixture.invalid/feed",
            "https:///feed",
            "https://fixture.invalid/feed\u000Dtoken",
            "https://fixture.invalid/feed\u007F",
        )
        for (source in bad) {
            val error = assertFailsWith<IllegalArgumentException>(source.replace("\u000D", "CR")) {
                SourceDownloader.checkedUrl(source)
            }
            error.toString().assertNoDisclosure()
        }
        assertTrue(harness.requested.isEmpty())
        assertTrue(SourceDownloader.checkedUrl("HTTPS://fixture.invalid/feed").protocol.equals("https", true))
        assertEquals("http", SourceDownloader.checkedUrl("http://fixture.invalid/feed").protocol)
    }

    @Test fun gzipBoundaryIsInclusiveAndOneByteOverFailsClosed() {
        val plain = ByteArray(120) { (it % 26 + 65).toByte() }
        val compressed = gzip(plain)
        val exact = SeamHarness(listOf(SeamStep(body = compressed)))
        val output = exact.downloader
            .open("https://fixture.invalid/feed?token=SYNTHETIC_CANARY", plain.size.toLong())
            .use { it.readBytes() }
        assertTrue(output.contentEquals(plain))

        val over = SeamHarness(listOf(SeamStep(body = compressed)))
        val error = assertFailsWith<IllegalArgumentException> {
            over.downloader
                .open("https://fixture.invalid/feed?token=SYNTHETIC_CANARY", plain.size.toLong() - 1)
                .use { it.readBytes() }
        }
        assertEquals("Source exceeds decompressed size limit", error.message)
        error.toString().assertNoDisclosure()
        assertEquals(1, over.disconnects)
    }

    @Test fun falseGzipHeaderFailsClosedAndReleasesConnection() {
        val harness = SeamHarness(listOf(SeamStep(
            body = "plain".toByteArray(),
            encoding = "gzip",
        )))
        val error = assertFailsWith<java.io.IOException> {
            harness.downloader.open("https://fixture.invalid/wrong?token=SYNTHETIC_CANARY", 128)
        }
        error.toString().assertNoDisclosure()
        assertEquals(1, harness.disconnects)
    }

    @Test fun closeOfReturnedStreamDisconnectsExactlyOnce() {
        val harness = SeamHarness(listOf(SeamStep(body = "abc".toByteArray())))
        val stream = harness.downloader.open("https://fixture.invalid/ok?token=SYNTHETIC_CANARY", 8)
        assertEquals(0, harness.disconnects)
        stream.close()
        assertEquals(1, harness.disconnects)
    }

    @Test fun zeroLengthBulkReadIsLegalAndDoesNotConsumeBudget() {
        val harness = SeamHarness(listOf(SeamStep(body = "abc".toByteArray())))
        harness.downloader.open("https://fixture.invalid/zero?token=SYNTHETIC_CANARY", 3).use { stream ->
            assertEquals(0, stream.read(ByteArray(4), 0, 0))
            assertEquals("abc", String(stream.readBytes()))
        }
    }

    @Test fun stalledSingleByteReadCannotSpinTheCaller() {
        var zeroReads = 0
        val stalling = object : InputStream() {
            override fun read(): Int { zeroReads++; return 0 }
        }
        val harness = SeamHarness(listOf(SeamStep(inputOverride = stalling)))
        val error = assertFailsWith<java.io.IOException> {
            harness.downloader
                .open("https://fixture.invalid/stall-single?token=SYNTHETIC_CANARY", 128)
                .use { stream -> repeat(64) { stream.read() } }
        }
        assertEquals("Source made no progress", error.message)
        error.toString().assertNoDisclosure()
        assertEquals(16, zeroReads)
        assertEquals(1, harness.disconnects)
    }

    @Test fun recoveryRequiresMatchingBlankSafeSavedSourceAndIdleState() {
        val saved = "https://fixture.invalid/playlist"
        assertTrue(SourceRecoveryPolicy.shouldRestore(saved, saved, false, false))
        assertFalse(SourceRecoveryPolicy.shouldRestore(null, saved, false, false))
        assertFalse(SourceRecoveryPolicy.shouldRestore("", "", false, false))
        assertFalse(SourceRecoveryPolicy.shouldRestore("  ", "  ", false, false))
        assertFalse(SourceRecoveryPolicy.shouldRestore(saved, "", false, false))
        assertFalse(SourceRecoveryPolicy.shouldRestore(saved, "$saved/other", false, false))
        assertFalse(SourceRecoveryPolicy.shouldRestore(saved, saved, true, false))
        assertFalse(SourceRecoveryPolicy.shouldRestore(saved, saved, false, true))
    }

    @Test fun staleCallbackResultCannotCommitOverNewerRequest() {
        assertTrue(SourceRecoveryPolicy.mayCommit(true, 3, 3))
        assertFalse(SourceRecoveryPolicy.mayCommit(false, 3, 3))
        assertFalse(SourceRecoveryPolicy.mayCommit(true, 3, 4))
        assertFalse(SourceRecoveryPolicy.mayCommit(true, Int.MAX_VALUE, Int.MIN_VALUE))
    }
}
