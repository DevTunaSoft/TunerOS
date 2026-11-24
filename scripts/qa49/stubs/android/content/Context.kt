package android.content

import android.content.pm.ApplicationInfo
import java.io.File

/** Host-only signature mirror; the device build uses the real framework class. */
abstract class Context {
    abstract val contentResolver: ContentResolver
    abstract val applicationInfo: ApplicationInfo
    abstract fun getExternalFilesDirs(type: String?): Array<File?>
}
