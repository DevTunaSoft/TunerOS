// SAF-06 synthetic android.util.Log double: discards messages, never writes to a device log.
package android.util

object Log {
    @JvmStatic
    fun i(tag: String, msg: String): Int = 0

    @JvmStatic
    fun w(tag: String, msg: String): Int = 0
}
