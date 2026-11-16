package com.devtunasoft.tuneros.core

import java.security.MessageDigest
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The parental PIN. Only a salted PBKDF2 hash is ever kept; the PIN itself is never stored, logged or compared as text.
 */
object ParentalPin {
    const val MIN_DIGITS = 4
    const val MAX_DIGITS = 8
    const val SALT_BYTES = 16
    const val HASH_BYTES = 32
    const val ITERATIONS = 120_000

    fun isValid(pin: String): Boolean = pin.length in MIN_DIGITS..MAX_DIGITS && pin.all { it in '0'..'9' }

    fun hash(pin: String, salt: ByteArray): ByteArray {
        require(isValid(pin)) { "a PIN is $MIN_DIGITS to $MAX_DIGITS digits" }
        require(salt.size == SALT_BYTES) { "the salt is $SALT_BYTES bytes" }
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, HASH_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** True only for a valid PIN whose hash equals [expected]. The comparison takes the same time whatever the result. */
    fun matches(pin: String, salt: ByteArray, expected: ByteArray): Boolean {
        if (!isValid(pin)) return false
        return MessageDigest.isEqual(hash(pin, salt), expected)
    }
}

/**
 * Counts wrong PIN entries. After [maxFailures] in a row the gate locks for [lockMillis], and it refuses every entry while
 * locked, including the right one. A correct entry clears the count. The caller supplies the clock, so the rule is testable.
 */
class ParentalAttemptLimit(
    private val maxFailures: Int = 5,
    private val lockMillis: Long = 60_000L,
    failures: Int = 0,
    lockedUntilMillis: Long = 0L,
) {
    init {
        require(maxFailures > 0)
        require(lockMillis > 0)
        require(failures in 0 until maxFailures)
        require(lockedUntilMillis >= 0)
    }

    sealed class Outcome {
        object Accepted : Outcome()
        data class Rejected(val attemptsLeft: Int) : Outcome()
        data class Locked(val untilMillis: Long) : Outcome()
    }

    /** Wrong entries since the last correct one or the last lock. Restored from storage, so a restart cannot clear it. */
    var failures: Int = failures
        private set

    /** The time the current lock ends, or 0 when not locked. */
    var lockedUntilMillis: Long = lockedUntilMillis
        private set

    fun isLocked(nowMillis: Long): Boolean = nowMillis < lockedUntilMillis

    fun attempt(correct: Boolean, nowMillis: Long): Outcome {
        if (isLocked(nowMillis)) return Outcome.Locked(lockedUntilMillis)
        if (correct) {
            failures = 0
            return Outcome.Accepted
        }
        failures++
        if (failures >= maxFailures) {
            failures = 0
            lockedUntilMillis = nowMillis + lockMillis
            return Outcome.Locked(lockedUntilMillis)
        }
        return Outcome.Rejected(maxFailures - failures)
    }
}

/** Which channel groups need the PIN before they play. Group names are compared without case or surrounding spaces. */
object ParentalPolicy {
    const val MAX_RESTRICTED_GROUPS = 100

    fun normalize(group: String): String = group.trim().lowercase(Locale.ROOT)

    fun requiresPin(group: String?, restrictedGroups: Set<String>, unlocked: Boolean): Boolean {
        if (unlocked || group == null) return false
        val key = normalize(group)
        return restrictedGroups.any { normalize(it) == key }
    }

    /** Returns [current] with [group] added or removed, stored normalized. Refuses an empty name or too many groups. */
    fun restrict(current: Set<String>, group: String, restricted: Boolean): Set<String> {
        val key = normalize(group)
        require(key.isNotEmpty()) { "a group name is not empty" }
        val normalized = current.map { normalize(it) }.toSet()
        val next = if (restricted) normalized + key else normalized - key
        require(next.size <= MAX_RESTRICTED_GROUPS) { "at most $MAX_RESTRICTED_GROUPS groups can be restricted" }
        return next
    }
}
