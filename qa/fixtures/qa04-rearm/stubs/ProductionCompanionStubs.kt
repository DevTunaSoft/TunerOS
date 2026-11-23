// QA-04 JVM harness stubs for companion production classes that are owned by
// other workers. Only the exact surface used by RecordingScheduler is present.
// No network source or real credential is ever constructed.
package com.devtunasoft.tuneros

import android.content.Context

internal class SecureSourceStore(context: Context)

internal data class ScheduledRecordingSource(
    val url: String,
    val userAgent: String?,
    val referer: String?,
)

internal object ScheduledRecordingSecrets {
    fun load(store: SecureSourceStore, key: String): ScheduledRecordingSource? =
        ScheduledRecordingSource("scratch://qa04/no-media", null, null)

    fun delete(store: SecureSourceStore, key: String): Boolean = true
}

internal object OnDeviceSelfTest {
    enum class Event {
        DVR_REARM_EXACT_COUNT,
        DVR_REARM_BLOCKED_COUNT,
        DVR_REARM_FAILURE_COUNT,
        DVR_PREPARATIONS_RECOVERED,
        DVR_ALARM_DELIVERED,
        DVR_SCHEDULED_SERVICE_REQUESTED,
        DVR_SCHEDULED_SERVICE_ACCEPTED,
    }

    val observations = LinkedHashMap<String, Long>()

    fun observe(context: Context, event: Event) {
        observations[event.name] = (observations[event.name] ?: 0L) + 1L
    }

    fun observe(context: Context, event: Event, count: Long) {
        observations[event.name] = (observations[event.name] ?: 0L) + count
    }
}

class DvrRecordingService {
    companion object {
        const val ACTION_START = "com.devtunasoft.tuneros.dvr.START"
        const val EXTRA_ID = "recording_id"
        const val EXTRA_SCHEDULE_KEY = "recording_schedule_key"
        const val EXTRA_TITLE = "recording_title"
        const val EXTRA_CHANNEL_ID = "recording_channel_id"
        const val EXTRA_URL = "recording_url"
        const val EXTRA_USER_AGENT = "recording_user_agent"
        const val EXTRA_REFERER = "recording_referer"
        const val EXTRA_END_EPOCH_SECONDS = "recording_end_epoch_seconds"
    }
}
