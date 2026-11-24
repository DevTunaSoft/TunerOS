// SAF-06 synthetic android.content doubles. No real SharedPreferences or resolver is touched.
package android.content

import android.content.pm.ApplicationInfo
import java.io.File

open class Context {
    companion object {
        const val MODE_PRIVATE = 0
    }

    open val applicationContext: Context get() = this
    open val contentResolver: ContentResolver = ContentResolver()
    open val applicationInfo: ApplicationInfo = ApplicationInfo()
    open val filesDir: File get() = File("/synthetic/saf06/no-device/files")

    /** Overridden per test; never backed by a real filesystem. */
    open fun getExternalFilesDirs(type: String?): Array<File?> = emptyArray()
    open fun getExternalFilesDir(type: String?): File? = null

    private val preferences = LinkedHashMap<String, SharedPreferences>()

    open fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        preferences.getOrPut(name ?: "") { Saf06Preferences() }
}

open class UriPermission(
    open val uri: android.net.Uri,
    open val isReadPermission: Boolean,
    open val isWritePermission: Boolean,
)

open class ContentResolver {
    open val persistedUriPermissions: List<UriPermission> get() = emptyList()

    open fun query(
        uri: android.net.Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): android.database.Cursor? = null

    open fun delete(uri: android.net.Uri, where: String?, selectionArgs: Array<String>?): Int = 1

    open fun openOutputStream(uri: android.net.Uri, mode: String?): java.io.OutputStream? = null
}

interface SharedPreferences {
    fun getString(key: String, defaultValue: String?): String?
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun remove(key: String): Editor
        fun commit(): Boolean
    }
}

private class Saf06Preferences : SharedPreferences {
    private val values = LinkedHashMap<String, String>()

    override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue
    override fun edit(): SharedPreferences.Editor = EditorImpl(this)

    private class EditorImpl(private val owner: Saf06Preferences) : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, String?>()
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            changes[key] = value
            return this
        }
        override fun remove(key: String): SharedPreferences.Editor {
            changes[key] = null
            return this
        }
        override fun commit(): Boolean {
            for ((key, value) in changes) {
                if (value == null) owner.values.remove(key) else owner.values[key] = value
            }
            return true
        }
    }
}
