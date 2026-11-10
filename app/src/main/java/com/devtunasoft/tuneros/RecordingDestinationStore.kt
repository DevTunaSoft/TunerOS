package com.devtunasoft.tuneros

import android.content.Context
import android.net.Uri

internal class RecordingDestinationStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("tuneros_recording_storage", Context.MODE_PRIVATE)

    fun selectedTreeUri(): Uri? =
        prefs.getString(KEY_TREE_URI, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme == "content" && !it.authority.isNullOrBlank() }

    fun saveTreeUri(uri: Uri): Boolean =
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).commit()

    fun clear(): Boolean =
        prefs.edit().remove(KEY_TREE_URI).commit()

    companion object {
        private const val KEY_TREE_URI = "recording_tree_uri"
    }
}
