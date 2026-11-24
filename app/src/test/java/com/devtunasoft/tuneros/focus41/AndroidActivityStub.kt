package android.app

import android.os.Bundle

/** Minimal synthetic Activity base so MainActivity's onSaveInstanceState can run on the JVM. */
open class Activity {
    open fun onSaveInstanceState(outState: Bundle) {
        // No framework state in the synthetic base; the test inspects MainActivity's own puts.
    }
}
