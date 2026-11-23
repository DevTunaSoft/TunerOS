package com.devtunasoft.tuneros.ux02

import android.content.Context
import android.content.SharedPreferences

/** Synthetic in-memory preferences. No real app data is read or written. */
internal class FakePreferencesContext : Context() {
    private val store = LinkedHashMap<String, Any?>()
    private var stringSetPuts = 0
    private var failOnPut = -1

    /** Fault injection: throw on the [ordinal]-th putStringSet from now on. */
    fun failOnNextStringSetPut(ordinal: Int) {
        stringSetPuts = 0
        failOnPut = ordinal
    }
    val preferences: SharedPreferences = object : SharedPreferences {
        override fun contains(key: String) = store.containsKey(key)
        override fun getString(key: String, defaultValue: String?) =
            (store[key] as? String) ?: defaultValue
        override fun getInt(key: String, defaultValue: Int) =
            (store[key] as? Int) ?: defaultValue
        override fun getLong(key: String, defaultValue: Long) =
            (store[key] as? Long) ?: defaultValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defaultValue: Set<String>?) =
            (store[key] as? Set<String>) ?: defaultValue
        override fun getAll(): Map<String, Any?> = LinkedHashMap(store)
        override fun edit() = object : SharedPreferences.Editor {
            private val changes = LinkedHashMap<String, Any?>()
            private var cleared = false
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putStringSet(key: String, value: Set<String>?): SharedPreferences.Editor {
                stringSetPuts++
                if (stringSetPuts == failOnPut) throw IllegalStateException("injected preference write failure")
                changes[key] = value?.toSet()
                return this
            }
            override fun remove(key: String) = apply { changes[key] = REMOVED }
            override fun clear() = apply { cleared = true }
            override fun apply() { commit() }
            override fun commit(): Boolean {
                if (cleared) store.clear()
                for ((key, value) in changes) {
                    if (value === REMOVED) store.remove(key) else store[key] = value
                }
                changes.clear()
                cleared = false
                return true
            }
        }
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences

    private object REMOVED
}
