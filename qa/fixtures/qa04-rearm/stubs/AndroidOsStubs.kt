// QA-04 JVM harness stubs: Build and PowerManager. Synthetic only.
package android.os

object Build {
    val VERSION: Version = Version()
    object VERSION_CODES {
        const val S = 31
    }
    class Version {
        val SDK_INT: Int = 35
    }
}

open class PowerManager {
    val isDeviceIdleMode: Boolean get() = false
}
