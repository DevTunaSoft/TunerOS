// DVR-03 deterministic JVM stubs. Synthetic scratch fixtures only: no provider
// URL, credential, DRM key or recording payload is ever created or read.
package android.content

import android.net.Uri
import java.io.File

open class Context {
    open val applicationContext: Context get() = this

    private val services = HashMap<String, Any>()

    fun <T : Any> registerService(service: Class<T>, implementation: T) {
        services[service.name] = implementation
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getSystemService(service: Class<T>): T =
        services[service.name] as? T
            ?: error("No DVR-03 stub registered for ${service.simpleName}")

    /** Telemetry writes are deliberately unsupported: rearm wraps them in runCatching. */
    val filesDir: File get() = error("DVR-03 tests never touch the filesystem")

    fun startForegroundService(intent: Intent) = Unit
}

class ContentValues {
    private val values = LinkedHashMap<String, Any?>()

    fun put(key: String, value: String?) { values[key] = value }
    fun put(key: String, value: Long?) { values[key] = value }
    fun put(key: String, value: Int?) { values[key] = value }
    fun putNull(key: String) { values[key] = null }

    internal fun snapshot(): Map<String, Any?> = LinkedHashMap(values)
}

class Intent {
    constructor()
    constructor(context: Context, component: Class<*>) {
        setAction(component.name)
    }

    private var actionValue: String? = null
    private var dataValue: Uri? = null
    private val extras = LinkedHashMap<String, Any?>()

    /** JVM shape of the platform setters used by production bytecode. */
    fun setAction(value: String?): Intent { actionValue = value; return this }
    fun setData(value: Uri?): Intent { dataValue = value; return this }

    fun putExtra(name: String, value: String?): Intent { extras[name] = value; return this }
    fun putExtra(name: String, value: Int): Intent { extras[name] = value; return this }
    fun putExtra(name: String, value: Long): Intent { extras[name] = value; return this }

    fun getStringExtra(name: String): String? = extras[name] as? String
    fun getIntExtra(name: String, default: Int): Int = extras[name] as? Int ?: default
    fun getLongExtra(name: String, default: Long): Long = extras[name] as? Long ?: default

    companion object {
        const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    }
}

open class BroadcastReceiver {
    open fun onReceive(context: Context, intent: Intent?) = Unit

    fun goAsync(): PendingResult = PendingResult()

    class PendingResult {
        fun finish() = Unit
    }
}
