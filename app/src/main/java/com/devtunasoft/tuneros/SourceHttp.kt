package com.devtunasoft.tuneros

import com.devtunasoft.tuneros.core.AbortableSlot
import com.devtunasoft.tuneros.core.HlsByteRange
import com.devtunasoft.tuneros.core.HlsContentRange
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opens one HTTP(S) request for a recording or timeshift source. Redirects are followed by hand so an HTTPS source can
 * never fall back to HTTP, and every connection is registered in [connections] so a cancel can abort it while it is
 * still connecting or reading. A range request must be answered with the exact bytes it asked for.
 */
internal fun openSourceConnection(
    value: String,
    userAgent: String?,
    referer: String?,
    byteRange: HlsByteRange?,
    connections: AbortableSlot<HttpURLConnection>,
): HttpURLConnection {
    var current = URL(value)
    repeat(6) {
        val connection = current.openConnection() as HttpURLConnection
        if (!connections.register(connection)) error("Recording cancelled")
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", userAgent ?: "TunerOS/0.1")
        referer?.let { connection.setRequestProperty("Referer", it) }
        byteRange?.let {
            val rangeEnd = Math.addExact(it.offset, it.length - 1)
            connection.setRequestProperty("Range", "bytes=" + it.offset + "-" + rangeEnd)
        }

        val code = connection.responseCode
        if (code in 300..399) {
            val location = connection.getHeaderField("Location")
                ?: run {
                    connection.disconnect()
                    error("Redirect without Location")
                }
            val next = URL(current, location)
            if (current.protocol == "https" && next.protocol != "https") {
                connection.disconnect()
                error("HTTPS downgrade blocked")
            }
            connection.disconnect()
            connections.release(connection)
            current = next
            return@repeat
        }

        if (code !in 200..299) {
            connection.disconnect()
            connections.release(connection)
            throw HttpStatusException(code)
        }
        if (byteRange != null && code != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            connections.release(connection)
            error("Server ignored HLS byte range")
        }
        if (byteRange != null && !HlsContentRange.matches(
                connection.getHeaderField("Content-Range"), byteRange
            )
        ) {
            connection.disconnect()
            connections.release(connection)
            error("HLS Content-Range did not match requested bytes")
        }
        return connection
    }
    error("Too many redirects")
}
