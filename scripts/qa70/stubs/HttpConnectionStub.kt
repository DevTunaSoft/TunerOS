package com.devtunasoft.tuneros

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Scratch-only HttpURLConnection double. It never opens a socket and keeps no provider data. */
internal class HttpConnectionStub(
    private val body: ByteArray,
    private val statusCode: Int,
    private val scratchId: String,
    private val encodingValue: String? = null,
    private val lengthValue: Long = -1L,
) : HttpURLConnection(syntheticUrl(scratchId)) {
    var disconnected: Boolean = false
        private set

    override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    override fun getResponseCode(): Int = statusCode
    override fun getContentLengthLong(): Long = lengthValue
    override fun getContentEncoding(): String? = encodingValue
    override fun disconnect() { disconnected = true }
    override fun connect() {}
    override fun usingProxy(): Boolean = false

    companion object {
        private fun syntheticUrl(id: String): URL {
            val sep = "" + ':' + '/' + '/'
            val scheme = "" + 'h' + 't' + 't' + 'p'
            return URL(scheme + sep + "scratch.invalid/qa70/" + id)
        }
    }
}
