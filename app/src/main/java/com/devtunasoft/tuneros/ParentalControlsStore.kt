package com.devtunasoft.tuneros

import android.content.Context
import com.devtunasoft.tuneros.core.ParentalAttemptLimit
import com.devtunasoft.tuneros.core.ParentalPin
import com.devtunasoft.tuneros.core.ParentalPolicy
import java.security.SecureRandom
import java.util.Base64

/**
 * The parental PIN, the restricted channel groups and the wrong-entry count, kept in the app's private preferences. Only a
 * salted hash of the PIN is stored. This is a convenience gate for a household, not protection against someone who can
 * clear the app's data.
 */
internal class ParentalControlsStore(context: Context, prefsName: String = PREFS_NAME) {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun hasPin(): Boolean = prefs.contains(KEY_SALT) && prefs.contains(KEY_HASH)

    /** Stores [pin] as a new salted hash and clears the wrong-entry count. The caller checks the PIN is valid. */
    fun setPin(pin: String) {
        require(ParentalPin.isValid(pin)) { "a PIN is ${ParentalPin.MIN_DIGITS} to ${ParentalPin.MAX_DIGITS} digits" }
        val salt = ByteArray(ParentalPin.SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = ParentalPin.hash(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, encode(salt))
            .putString(KEY_HASH, encode(hash))
            .putInt(KEY_FAILURES, 0)
            .putLong(KEY_LOCKED_UNTIL, 0L)
            .apply()
    }

    /**
     * Checks [pin] and counts a wrong entry, including an entry that is not a valid PIN. The count and any lock are saved
     * before the outcome is returned, so restarting the app does not clear them. Returns null when no PIN has been set.
     */
    fun verify(pin: String, nowMillis: Long = System.currentTimeMillis()): ParentalAttemptLimit.Outcome? {
        val salt = decode(prefs.getString(KEY_SALT, null)) ?: return null
        val expected = decode(prefs.getString(KEY_HASH, null)) ?: return null
        val limit = restoredLimit()
        if (limit.isLocked(nowMillis)) return ParentalAttemptLimit.Outcome.Locked(limit.lockedUntilMillis)
        val outcome = limit.attempt(ParentalPin.matches(pin, salt, expected), nowMillis)
        prefs.edit()
            .putInt(KEY_FAILURES, limit.failures)
            .putLong(KEY_LOCKED_UNTIL, limit.lockedUntilMillis)
            .apply()
        return outcome
    }

    fun restrictedGroups(): Set<String> = prefs.getStringSet(KEY_RESTRICTED, null).orEmpty().toSet()

    /** Adds or removes [group] from the restricted set. Refuses an empty name or too many groups. */
    fun restrict(group: String, restricted: Boolean) {
        val next = ParentalPolicy.restrict(restrictedGroups(), group, restricted)
        prefs.edit().putStringSet(KEY_RESTRICTED, next.toMutableSet()).apply()
    }

    /**
     * Replaces the restricted set with [groups] in one write. The whole list is checked first, so an empty name or too many
     * groups saves nothing.
     */
    fun replaceRestrictedGroups(groups: Set<String>) {
        prefs.edit().putStringSet(KEY_RESTRICTED, ParentalPolicy.normalizeAll(groups).toMutableSet()).apply()
    }

    fun restrictedChannels(): Set<String> = prefs.getStringSet(KEY_RESTRICTED_CHANNELS, null).orEmpty().toSet()

    /** Replaces the restricted channel names in one write. The whole list is checked first. */
    fun replaceRestrictedChannels(channels: Set<String>) {
        prefs.edit().putStringSet(KEY_RESTRICTED_CHANNELS, ParentalPolicy.normalizeAll(channels).toMutableSet()).apply()
    }

    /** Adds or removes [channel] from the restricted channel names. Refuses an empty name or too many entries. */
    fun restrictChannel(channel: String, restricted: Boolean) {
        val next = ParentalPolicy.restrict(restrictedChannels(), channel, restricted)
        prefs.edit().putStringSet(KEY_RESTRICTED_CHANNELS, next.toMutableSet()).apply()
    }

    private fun restoredLimit(): ParentalAttemptLimit = ParentalAttemptLimit(
        maxFailures = MAX_FAILURES,
        lockMillis = LOCK_MILLIS,
        failures = prefs.getInt(KEY_FAILURES, 0).coerceIn(0, MAX_FAILURES - 1),
        lockedUntilMillis = prefs.getLong(KEY_LOCKED_UNTIL, 0L).coerceAtLeast(0L),
    )

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String?): ByteArray? {
        if (value == null) return null
        return try {
            Base64.getDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            null
        }
    }

    companion object {
        const val PREFS_NAME = "tuneros-parental"
        private const val KEY_SALT = "salt"
        private const val KEY_HASH = "hash"
        private const val KEY_FAILURES = "failures"
        private const val KEY_LOCKED_UNTIL = "locked-until"
        private const val KEY_RESTRICTED = "restricted-groups"
        private const val KEY_RESTRICTED_CHANNELS = "restricted-channels"
        const val MAX_FAILURES = 5
        const val LOCK_MILLIS = 60_000L
    }
}
