// QA-04 JVM harness stubs: receiver, OS build and power manager. Synthetic only.
package android.content

open class BroadcastReceiver {
    open fun onReceive(context: Context, intent: Intent?) = Unit

    fun goAsync(): PendingResult = PendingResult()

    class PendingResult {
        private var finished = false
        fun finish() { finished = true }
        val isFinished: Boolean get() = finished
    }
}
