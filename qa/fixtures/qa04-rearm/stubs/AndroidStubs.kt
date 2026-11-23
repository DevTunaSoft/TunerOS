// QA-04 JVM harness stubs. Synthetic only: no provider URLs, credentials or media.
// These stubs replace the Android platform at compile/runtime so the real production
// schedule store and scheduler can be exercised deterministically on the JVM.
package android.content

import android.net.Uri

open class Context {
    open val applicationContext: Context get() = this

    private val services = HashMap<String, Any>()

    fun <T : Any> registerService(service: Class<T>, implementation: T) {
        services[service.name] = implementation
    }

    fun startForegroundService(intent: Intent) = Unit

    @Suppress("UNCHECKED_CAST")
    fun <T> getSystemService(service: Class<T>): T =
        services[service.name] as? T
            ?: throw IllegalStateException("No QA stub registered for ${service.simpleName}")
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
        action = component.name
    }

    var action: String? = null
    var data: Uri? = null
    private val extras = LinkedHashMap<String, Any?>()

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
