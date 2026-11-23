package com.devtunasoft.tuneros.http07

import com.devtunasoft.tuneros.HttpStatusException
import com.devtunasoft.tuneros.core.AbortableSlot
import com.devtunasoft.tuneros.openSourceConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket

/**
 * HTTP-07 regressions. Every address and marker here is synthetic: no provider URL, credential, DRM key,
 * media payload or private path appears. The assertion is about what the failure *message* carries, because
 * exception text is the part that generic diagnostics, logs and crash reports can copy verbatim.
 */
class SourceHttpHygieneTest {
    companion object {
        private const val SOURCE_CANARY = "SYNTHETIC_HTTP07_SOURCE_CANARY"
        private const val TOKEN_CANARY = "SYNTHETIC_HTTP07_TOKEN_CANARY"
        private const val MALFORMED_SOURCE_MESSAGE = "Source URL is malformed"
        private const val MESSAGE_LIMIT = 128
    }

    private fun connections() = AbortableSlot<HttpURLConnection> { it.disconnect() }

    private fun failureOf(block: () -> Unit): Throwable {
        var failure: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            failure = error
        }
        assertNotNull("a hostile source must be refused", failure)
        return failure!!
    }

    @Test
    fun malformedSourceUrlDoesNotEchoTheAddressOrItsQueryCredentials() {
        // "no protocol: <raw spec>" is a real java.net.URL failure and it copies the whole spec.
        val hostileSource = "$SOURCE_CANARY?token=$TOKEN_CANARY"

        val error = failureOf { openSourceConnection(hostileSource, null, null, null, connections()) }
        val message = error.message ?: ""

        assertFalse("source address leaked into the failure message: $message", message.contains(SOURCE_CANARY))
        assertFalse("credential marker leaked into the failure message: $message", message.contains(TOKEN_CANARY))
        assertEquals("fixed refusal message expected", MALFORMED_SOURCE_MESSAGE, message)
        assertTrue("failure message is unbounded: ${message.length}", message.length <= MESSAGE_LIMIT)
    }

    @Test
    fun malformedRedirectLocationDoesNotEchoTheAddressOrItsCredentials() {
        // An invalid authority in a signed Location is echoed by java.net.URL as "Invalid authority field: ...".
        val addressCanary = "SYNTHETIC_HTTP07_REDIRECT_CANARY"
        val tokenCanary = "SYNTHETIC_HTTP07_REDIRECT_TOKEN"
        val hostileLocation = "http://[$addressCanary?token=$tokenCanary"
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10_000 }
        val responder = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val request = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    while (request.readLine()?.isNotEmpty() == true) Unit
                    val response = "HTTP/1.1 302 Found\r\n" +
                        "Location: $hostileLocation\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().use { it.write(response.toByteArray(Charsets.ISO_8859_1)) }
                }
            } catch (_: Exception) {
                // The main thread asserts the refusal below.
            }
        }.apply { isDaemon = true }

        val error = try {
            responder.start()
            failureOf {
                openSourceConnection("http://127.0.0.1:${server.localPort}/fixture", null, null, null, connections())
            }
        } finally {
            server.close()
            responder.join(2_000)
        }
        val message = error.message ?: ""

        assertFalse("redirect address leaked into the failure message: $message", message.contains(addressCanary))
        assertFalse("redirect credential leaked into the failure message: $message", message.contains(tokenCanary))
        assertEquals("fixed refusal message expected", MALFORMED_SOURCE_MESSAGE, message)
        assertTrue("failure message is unbounded: ${message.length}", message.length <= MESSAGE_LIMIT)
    }

    @Test
    fun httpStatusFailureCarriesOnlyTheStatusCode() {
        val error = HttpStatusException(503)

        assertEquals(503, error.code)
        assertEquals("HTTP status 503", error.message)
        assertTrue("status message is unbounded", error.message!!.length <= MESSAGE_LIMIT)
    }
}
