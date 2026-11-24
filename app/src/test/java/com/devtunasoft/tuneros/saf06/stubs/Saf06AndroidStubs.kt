// SAF-06 deterministic JVM platform stubs. Synthetic values only: no real provider URL,
// credential, media payload or device path is read or written by these doubles.
// Each stub mirrors only the JVM descriptors the three owned production files call.

package android.net

import java.io.File

/** Minimal Uri double with the accessors used by the SAF-06 owned sources. */
class Uri private constructor(
    private val encoded: String,
    val scheme: String?,
    val authority: String?,
    val path: String?,
) {
    override fun toString(): String = encoded

    override fun equals(other: Any?): Boolean = other is Uri && encoded == other.encoded
    override fun hashCode(): Int = encoded.hashCode()

    companion object {
        @JvmStatic
        fun parse(value: String): Uri {
            val marker = value.indexOf("://")
            if (marker < 0) {
                return Uri(value, null, null, value)
            }
            val scheme = value.substring(0, marker)
            val remainder = value.substring(marker + 3)
            val slash = remainder.indexOf('/')
            val authority = if (slash < 0) remainder else remainder.substring(0, slash)
            val path = if (slash < 0) "" else remainder.substring(slash)
            require(scheme.isNotEmpty()) { "synthetic Uri has empty scheme" }
            return Uri(value, scheme, authority, path)
        }

        @JvmStatic
        fun fromFile(file: File): Uri {
            val absolute = file.absolutePath
            return Uri("file://$absolute", "file", "", absolute)
        }
    }
}
