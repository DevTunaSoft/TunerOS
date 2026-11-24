package androidx.media3.datasource

/** Minimal offline stand-ins used only to compile the real PlaybackEvidence.kt in the QA-68 host gate. */
interface DataSource

class DataSpec

interface TransferListener {
    fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {}
    fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
}

interface HttpDataSource : DataSource {
    val responseHeaders: Map<String, List<String>>

    class InvalidResponseCodeException(val responseCode: Int) : Exception()

    class InvalidContentTypeException(val contentType: String) : Exception()
}
