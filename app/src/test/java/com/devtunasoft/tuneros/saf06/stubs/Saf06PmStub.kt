// SAF-06 synthetic android.content.pm double. Debug logging is disabled in every fixture.
package android.content.pm

class ApplicationInfo {
    /** Real Android exposes this as a public field; production bytecode reads the field directly. */
    @JvmField
    var flags: Int = 0

    companion object {
        const val FLAG_DEBUGGABLE = 2
    }
}
