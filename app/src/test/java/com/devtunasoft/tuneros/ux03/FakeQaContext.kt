package com.devtunasoft.tuneros.ux03

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Synthetic context and preferences; no real app data, provider URL or credential is read. */
internal class FakeQaContext(override val filesDir: File) : Context() {
    private val stores = LinkedHashMap<String, InMemoryPreferences>()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        stores.getOrPut(name) { InMemoryPreferences() }

    override fun deleteSharedPreferences(name: String) { stores.remove(name) }
    override fun deleteDatabase(name: String) { File(filesDir, name).delete() }

    private class InMemoryPreferences : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()
        override fun contains(key: String) = values.containsKey(key)
        override fun getString(key: String, defaultValue: String?) = (values[key] as? String) ?: defaultValue
        override fun getInt(key: String, defaultValue: Int) = (values[key] as? Int) ?: defaultValue
        override fun getLong(key: String, defaultValue: Long) = (values[key] as? Long) ?: defaultValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defaultValue: Set<String>?) =
            (values[key] as? Set<String>) ?: defaultValue
        override val all: Map<String, Any?> get() = LinkedHashMap(values)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private var cleared = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putStringSet(key: String, value: Set<String>?) = apply { pending[key] = value?.toSet() }
            override fun remove(key: String) = apply { pending[key] = REMOVED }
            override fun clear() = apply { cleared = true }
            override fun apply() { commit() }
            override fun commit(): Boolean {
                if (cleared) values.clear()
                for ((key, value) in pending) {
                    if (value === REMOVED) values.remove(key) else values[key] = value
                }
                pending.clear()
                cleared = false
                return true
            }
        }
    }

    private object REMOVED
}
