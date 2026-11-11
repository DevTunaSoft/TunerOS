package com.devtunasoft.tuneros.core

import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.io.PushbackInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream

/** Download source feeds with a decompressed byte limit, gzip sniffing and no HTTPS downgrade. */
class SourceDownloader(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    fun open(source: String, maxBytes: Long): InputStream {
        require(maxBytes > 0)
        var current = checkedUrl(source)
        for (hop in 0..MAX_REDIRECTS) {
            val connection = connect(current)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept-Encoding", "gzip")
                val code = connection.responseCode
                if (code in 300..399) {
                    if (hop == MAX_REDIRECTS) throw IllegalArgumentException("Too many redirects")
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalArgumentException("Redirect without location")
                    val next = checkedUrl(URL(current, location).toString())
                    if (current.protocol.equals("https", true) && !next.protocol.equals("https", true))
                        throw IllegalArgumentException("HTTPS source redirected to insecure HTTP")
                    current = next
                    connection.disconnect()
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) throw IllegalArgumentException("Source returned HTTP $code")
                val raw = PushbackInputStream(connection.inputStream, 2)
                val first = raw.read()
                val second = raw.read()
                if (second != -1) raw.unread(second)
                if (first != -1) raw.unread(first)
                val gzip = first == 0x1f && second == 0x8b
                val decoded = if (gzip || connection.contentEncoding.equals("gzip", true) ||
                    current.path.endsWith(".gz", true)) GZIPInputStream(raw) else raw
                return object : FilterInputStream(decoded) {
                    private var count = 0L
                    override fun read(): Int {
                        val value = super.read()
                        if (value != -1) checkLimit(1)
                        return value
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val n = super.read(buffer, offset, length)
                        if (n > 0) checkLimit(n)
                        return n
                    }
                    /** Consume skipped bytes through the checked read path; never bypass the size cap. */
                    override fun skip(n: Long): Long {
                        if (n <= 0) return 0
                        val scratch = ByteArray(SKIP_BUFFER_BYTES)
                        var remaining = n
                        var emptyReads = 0
                        while (remaining > 0) {
                            val amount = read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                            if (amount == -1) break
                            if (amount == 0) {
                                // Non-conforming or stalled streams must not spin a
                                // core indefinitely during a large XMLTV/M3U import.
                                if (++emptyReads >= MAX_EMPTY_READS) {
                                    throw IOException("Source stalled during skip")
                                }
                                continue
                            }
                            emptyReads = 0
                            remaining -= amount
                        }
                        return n - remaining
                    }
                    private fun checkLimit(n: Int) {
                        count += n
                        if (count > maxBytes) throw IllegalArgumentException("Source exceeds decompressed size limit")
                    }
                    override fun close() { try { super.close() } finally { connection.disconnect() } }
                }
            } catch (failure: Exception) {
                connection.disconnect()
                throw failure
            }
        }
        throw IllegalArgumentException("Too many redirects")
    }

    companion object {
        const val MAX_REDIRECTS = 5
        private const val SKIP_BUFFER_BYTES = 8 * 1024
        private const val MAX_EMPTY_READS = 16
        fun checkedUrl(value: String): URL {
            val uri = runCatching { URI(value) }.getOrNull() ?: throw IllegalArgumentException("Invalid source URL")
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() ||
                value.any { it.code < 32 || it.code == 127 })
                throw IllegalArgumentException("Only HTTP(S) sources are supported")
            return uri.toURL()
        }
    }
}
