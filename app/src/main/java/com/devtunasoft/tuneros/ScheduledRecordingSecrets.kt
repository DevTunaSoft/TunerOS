package com.devtunasoft.tuneros

internal data class ScheduledRecordingSource(
    val url: String,
    val userAgent: String?,
    val referer: String?,
)

internal object ScheduledRecordingSecrets {
    private const val USER_AGENT_SUFFIX = ".userAgent"
    private const val REFERER_SUFFIX = ".referer"

    fun save(
        store: SecureSourceStore,
        key: String,
        source: ScheduledRecordingSource,
    ): Boolean {
        if (!store.save(key, source.url)) return false

        val userAgentSaved = if (source.userAgent == null) {
            store.delete(key + USER_AGENT_SUFFIX)
        } else {
            store.save(key + USER_AGENT_SUFFIX, source.userAgent)
        }
        if (!userAgentSaved) {
            delete(store, key)
            return false
        }

        val refererSaved = if (source.referer == null) {
            store.delete(key + REFERER_SUFFIX)
        } else {
            store.save(key + REFERER_SUFFIX, source.referer)
        }
        if (!refererSaved) {
            delete(store, key)
            return false
        }
        return true
    }

    fun load(store: SecureSourceStore, key: String): ScheduledRecordingSource? {
        val url = store.load(key) ?: return null
        return ScheduledRecordingSource(
            url = url,
            userAgent = store.load(key + USER_AGENT_SUFFIX),
            referer = store.load(key + REFERER_SUFFIX),
        )
    }

    fun delete(store: SecureSourceStore, key: String) {
        store.delete(key)
        store.delete(key + USER_AGENT_SUFFIX)
        store.delete(key + REFERER_SUFFIX)
    }
}
