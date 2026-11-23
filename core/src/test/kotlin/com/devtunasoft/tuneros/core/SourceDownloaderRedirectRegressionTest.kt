package com.devtunasoft.tuneros.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic adversarial coverage of the SourceDownloader redirect policy and connection
 * lifecycle — the one core seam the dossier's "bounded downloader and redirect policy" domain
 * names that has no dedicated JVM test. CoreTest covers the body seams (gzip magic, byte cap,
 * stalled reads, close-on-failure) and the downgrade/upgrade happy paths, but not redirect
 * classification, the hop bound, cleanup of intermediate connections, or diagnostic hygiene on
 * malformed Location headers. All fixtures are synthetic; no provider URL or credential is used.
 */
class SourceDownloaderRedirectRegressionTest {
    private class Response(
        val status: Int = HttpURLConnection.HTTP_OK,
        val location: String? = null,
        val body: ByteArray = byteArrayOf(),
    )

    private class FakeConnection(url: URL, private val response: Response) : HttpURLConnection(url) {
        var disconnected = false
            private set
        var followRedirects: Boolean? = null
            private set
        var connectTimeoutMs: Int? = null
            private set
        var readTimeoutMs: Int? = null
            private set
        var acceptEncoding: String? = null
            private set

        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = response.status
        override fun getInputStream(): InputStream = ByteArrayInputStream(response.body)
        override fun getHeaderField(name: String?): String? = if (name == "Location") response.location else null
        override fun setInstanceFollowRedirects(follow: Boolean) {
            followRedirects = follow
            super.setInstanceFollowRedirects(follow)
        }
        override fun setConnectTimeout(timeoutMs: Int) {
            connectTimeoutMs = timeoutMs
            super.setConnectTimeout(timeoutMs)
        }
        override fun setReadTimeout(timeoutMs: Int) {
            readTimeoutMs = timeoutMs
            super.setReadTimeout(timeoutMs)
        }
        override fun setRequestProperty(key: String?, value: String?) {
            if (key == "Accept-Encoding") acceptEncoding = value
            super.setRequestProperty(key, value)
        }
    }

    private class Harness(responses: List<Response>) {
        val connections = mutableListOf<FakeConnection>()
        val urls = mutableListOf<String>()
        val downloader = SourceDownloader { url ->
            urls += url.toString()
            val response = responses[minOf(connections.size, responses.lastIndex)]
            FakeConnection(url, response).also { connections += it }
        }
    }

    @Test fun malformedRedirectLocationNeverEchoesHeaderMaterialInDiagnostics() {
        val marker = "SYNTHETIC-CANARY-7481"
        val harness = Harness(listOf(Response(status = 302, location = "http://[$marker.invalid/x")))
        val failure = assertFailsWith<Exception> {
            harness.downloader.open("https://origin.invalid/feed", 100).close()
        }
        assertFalse(
            failure.message?.contains(marker) == true,
            "Redirect refusal must not echo Location header material: ${failure.message}",
        )
        assertTrue(
            failure is IllegalArgumentException,
            "Malformed redirect must be a structured core refusal, was ${failure::class.simpleName}",
        )
    }

    @Test fun redirectChainIsBoundedAndEveryIntermediateConnectionIsDisconnected() {
        val harness = Harness(listOf(Response(status = 302, location = "/next")))
        val failure = assertFailsWith<IllegalArgumentException> {
            harness.downloader.open("https://origin.invalid/feed", 100)
        }
        assertEquals("Too many redirects", failure.message)
        assertEquals(SourceDownloader.MAX_REDIRECTS + 1, harness.connections.size)
        assertTrue(harness.connections.all { it.disconnected })
    }

    @Test fun redirectWithoutLocationRefusesAndDisconnects() {
        val harness = Harness(listOf(Response(status = 302)))
        val failure = assertFailsWith<IllegalArgumentException> {
            harness.downloader.open("https://origin.invalid/feed", 100)
        }
        assertEquals("Redirect without location", failure.message)
        assertEquals(1, harness.connections.size)
        assertTrue(harness.connections.single().disconnected)
    }

    @Test fun failedStatusRefusesWithoutEchoingTheSourceUrl() {
        val marker = "SYNTHETIC-CANARY-3390"
        val harness = Harness(listOf(Response(status = 503)))
        val failure = assertFailsWith<IllegalArgumentException> {
            harness.downloader.open("https://origin.invalid/$marker", 100)
        }
        assertEquals("Source returned HTTP 503", failure.message)
        assertFalse(failure.message!!.contains(marker))
        assertTrue(harness.connections.single().disconnected)
    }

    @Test fun successfulRedirectDisconnectsIntermediateAndKeepsFinalUntilClose() {
        val harness = Harness(
            listOf(
                Response(status = 302, location = "https://edge.invalid/feed"),
                Response(body = "ok".toByteArray()),
            ),
        )
        val stream = harness.downloader.open("https://origin.invalid/feed", 100)
        assertEquals("ok", stream.bufferedReader().use { it.readText() })
        assertEquals(2, harness.connections.size)
        assertTrue(harness.connections[0].disconnected)
        assertTrue(harness.connections[1].disconnected)
        assertEquals(listOf("https://origin.invalid/feed", "https://edge.invalid/feed"), harness.urls)
    }

    @Test fun nonPositiveByteLimitRefusesBeforeOpeningAConnection() {
        val harness = Harness(listOf(Response()))
        assertFailsWith<IllegalArgumentException> { harness.downloader.open("https://origin.invalid/feed", 0) }
        assertFailsWith<IllegalArgumentException> { harness.downloader.open("https://origin.invalid/feed", -1) }
        assertTrue(harness.connections.isEmpty())
    }

    @Test fun redirectToNonHttpSchemeIsRefusedWithGenericDiagnostic() {
        val marker = "SYNTHETIC-CANARY-5521"
        val harness = Harness(listOf(Response(status = 302, location = "ftp://origin.invalid/$marker")))
        val failure = assertFailsWith<IllegalArgumentException> {
            harness.downloader.open("https://origin.invalid/feed", 100)
        }
        assertEquals("Only HTTP(S) sources are supported", failure.message)
        assertFalse(failure.message!!.contains(marker))
        assertTrue(harness.connections.single().disconnected)
    }

    @Test fun everyConnectionDisablesFollowRedirectsAndSetsBoundedTimeouts() {
        val harness = Harness(listOf(Response(status = 302, location = "/next")))
        assertFailsWith<IllegalArgumentException> { harness.downloader.open("https://origin.invalid/feed", 100) }
        assertTrue(harness.connections.isNotEmpty())
        harness.connections.forEach {
            assertEquals(false, it.followRedirects)
            assertEquals(10_000, it.connectTimeoutMs)
            assertEquals(15_000, it.readTimeoutMs)
            assertEquals("gzip", it.acceptEncoding)
        }
    }
}
