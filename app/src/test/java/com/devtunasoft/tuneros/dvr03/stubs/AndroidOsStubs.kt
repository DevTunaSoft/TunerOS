// DVR-03 OS platform stubs. Synthetic only.
package android.os

object Build {
    object VERSION_CODES {
        const val S = 31
    }

    class VERSION {
        companion object {
            const val SDK_INT: Int = 35
        }
    }
}

open class PowerManager {
    val isDeviceIdleMode: Boolean get() = false
}

object SystemClock {
    @JvmStatic
    fun elapsedRealtime(): Long = 0L
}

object Debug {
    @JvmStatic
    fun getPss(): Long = 0L
}
