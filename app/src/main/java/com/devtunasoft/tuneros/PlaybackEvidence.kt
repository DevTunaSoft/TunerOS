package com.devtunasoft.tuneros

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.devtunasoft.tuneros.core.StreamDoctor
import com.devtunasoft.tuneros.core.StreamEvidence
import com.devtunasoft.tuneros.core.TransportFault
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Passive observer of one playback session. It keeps only the media type of the first network response.
 * It never changes a request and keeps no headers or response bodies.
 */
internal class FirstResponseRecorder : TransferListener {
    @Volatile
    var contentType: String? = null
        private set
    private var recorded = false

    /** Forgets the previous session. Call before a new media item is set. */
    fun reset() {
        contentType = null
        recorded = false
    }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork || recorded) return
        recorded = true
        val headers = (source as? HttpDataSource)?.responseHeaders ?: return
        val raw = headers.entries
            .firstOrNull { entry -> entry.key?.equals("Content-Type", ignoreCase = true) == true }
            ?.value?.firstOrNull()
        contentType = StreamDoctor.safeContentType(raw)
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) = Unit

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
}

/**
 * Reads what a failed playback left in its exception chain. Only HTTP status codes, a validated media type
 * and fixed transport labels are kept. Exception messages and URLs are never read.
 */
internal fun evidenceOf(error: PlaybackException, firstResponse: FirstResponseRecorder?): StreamEvidence {
    var status: Int? = null
    var type: String? = null
    var transport: TransportFault? = null
    var cause: Throwable? = error.cause
    var depth = 0
    while (cause != null && depth < MAX_CAUSE_DEPTH) {
        when (cause) {
            is HttpDataSource.InvalidResponseCodeException -> status = status ?: cause.responseCode
            is HttpDataSource.InvalidContentTypeException -> type = type ?: cause.contentType
            // Only identified transport classes are named. A generic I/O error is not guessed at.
            is UnknownHostException -> transport = transport ?: TransportFault.DNS
            is SocketTimeoutException -> transport = transport ?: TransportFault.TIMEOUT
            is ConnectException -> transport = transport ?: TransportFault.REFUSED
            is SSLException -> transport = transport ?: TransportFault.TLS
        }
        cause = cause.cause
        depth++
    }
    return StreamEvidence(
        httpStatus = status,
        contentType = firstResponse?.contentType ?: StreamDoctor.safeContentType(type),
        transport = transport,
    )
}

private const val MAX_CAUSE_DEPTH = 8
