package android.content

import android.database.Cursor
import android.net.Uri

/** Host-only signature mirror of the small query surface used by the gate. */
abstract class ContentResolver {
    abstract fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor?
}
