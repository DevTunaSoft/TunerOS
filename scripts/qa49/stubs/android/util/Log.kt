package android.util

/** Bounded host-only capture of debug diagnostics; no address or payload is stored. */
object Log {
    private const val CAP = 512
    private val lines = ArrayList<String>()

    fun clear() = lines.clear()

    fun i(tag: String, message: String) {
        if (lines.size < CAP) lines.add("$tag: $message")
    }

    fun snapshot(): List<String> = lines.toList()
}
