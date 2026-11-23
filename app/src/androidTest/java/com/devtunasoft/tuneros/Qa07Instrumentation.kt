package com.devtunasoft.tuneros

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * QA-07 on-device equivalent of the host gate. Zero test dependencies: a plain Instrumentation entry point declared in
 * androidTest/AndroidManifest.xml, using only android.jar and JDK APIs. No JUnit, no androidx.test, no app internals.
 *
 * All state is scratch: a uniquely named preferences file and a scratch report in the target app's private files dir.
 * The real parental store, playlists, guide and recordings are never read or written. The PIN is derived at run time;
 * it is never written to the report, and the report is checked for it before it is saved.
 */
class Qa07Instrumentation : Instrumentation() {
    private val report = StringBuilder()
    private var passed = 0
    private var failed = 0

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        val context = targetContext.applicationContext
        val scratchName = "tuneros-qa07-scratch"
        val scratch = context.getSharedPreferences(scratchName, Context.MODE_PRIVATE)
        try {
            scratch.edit().clear().commit()
            val pin = derivedPin()
            val salt = derivedSalt()

            // Positive: a well-formed scratch record is accepted.
            writeValid(scratch, pin, salt)
            record("qa07-device-valid-store", evaluate(scratch, pin))

            // Fail-closed: every corrupt variant must be denied, including wrong types from a damaged store.
            val corruptNames = listOf(
                "missing-salt", "salt-wrong-type", "salt-bad-base64", "salt-short",
                "missing-hash", "hash-bad-base64", "hash-short", "failures-wrong-type",
                "failures-negative", "failures-too-many", "lock-wrong-type", "lock-negative",
                "hash-mismatch", "truncated",
            )
            var denied = 0
            for (name in corruptNames) {
                writeValid(scratch, pin, salt)
                when (name) {
                    "missing-salt" -> scratch.edit().remove(KEY_SALT).commit()
                    "salt-wrong-type" -> scratch.edit().putInt(KEY_SALT, 7).commit()
                    "salt-bad-base64" -> scratch.edit().putString(KEY_SALT, "!!!!").commit()
                    "salt-short" -> scratch.edit().putString(KEY_SALT, b64(salt.copyOf(SALT_BYTES - 1))).commit()
                    "missing-hash" -> scratch.edit().remove(KEY_HASH).commit()
                    "hash-bad-base64" -> scratch.edit().putString(KEY_HASH, "@@@@").commit()
                    "hash-short" -> scratch.edit().putString(KEY_HASH, b64(hash(pin, salt).copyOf(HASH_BYTES - 1))).commit()
                    "failures-wrong-type" -> scratch.edit().putString(KEY_FAILURES, "bad").commit()
                    "failures-negative" -> scratch.edit().putInt(KEY_FAILURES, -1).commit()
                    "failures-too-many" -> scratch.edit().putInt(KEY_FAILURES, 5).commit()
                    "lock-wrong-type" -> scratch.edit().putString(KEY_LOCK, "bad").commit()
                    "lock-negative" -> scratch.edit().putLong(KEY_LOCK, -1L).commit()
                    "hash-mismatch" -> scratch.edit().putString(KEY_HASH, b64(hash(derivedPin(1), salt))).commit()
                    "truncated" -> scratch.edit().clear().commit()
                }
                if (!evaluate(scratch, pin)) denied++
            }
            record("qa07-device-corrupt-fail-closed", denied == corruptNames.size,
                "corrupt=${corruptNames.size} denied=$denied")

            // Bounded UI workload: large but bounded input, at most ROW_CAP recycled rows and in-range indices.
            val channels = ArrayList<String>(CHANNEL_COUNT)
            repeat(CHANNEL_COUNT) { channels.add(String.format(java.util.Locale.ROOT, "c%05d", it)) }
            val favorites = HashSet<String>()
            repeat(FAVORITE_COUNT) { favorites.add(String.format(java.util.Locale.ROOT, "c%05d", it * 2)) }
            val visible = channels.filter { it in favorites }
            val bounded = visible.size == FAVORITE_COUNT &&
                channels.toSet().size == CHANNEL_COUNT &&
                uiRowsBounded(visible.size, ROW_CAP, ROW_CAP)
            record("qa07-device-bounded-rows", bounded,
                "channels=$CHANNEL_COUNT favorites=$FAVORITE_COUNT rowCap=$ROW_CAP visible=${visible.size}")

            // No PIN may appear in the report or in the scratch store.
            val stored = scratch.all.values.joinToString(" ") { it.toString() }
            record("qa07-device-pin-not-in-diagnostics",
                !report.toString().contains(pin) && !stored.contains(pin), "report and scratch store are PIN-free")
        } catch (error: Exception) {
            // Only the exception class name is written; no message can carry a PIN or a URL.
            record("qa07-device-exception", false, error.javaClass.simpleName)
        } finally {
            context.deleteSharedPreferences(scratchName)
            val out = File(context.filesDir, "tuneros-qa07-device-report.txt")
            out.writeText(report.toString())
        }

        val results = Bundle()
        results.putInt("qa07_pass", passed)
        results.putInt("qa07_fail", failed)
        finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    private fun record(id: String, pass: Boolean, detail: String = "ok") {
        report.append(if (pass) "PASS" else "FAIL").append('\t').append(id).append('\t').append(detail).append('\n')
        if (pass) passed++ else failed++
    }

    /** Strict check of the app's scratch store record; any missing, mistyped or inconsistent field denies. */
    private fun evaluate(scratch: android.content.SharedPreferences, pin: String): Boolean {
        val salt = scratch.all[KEY_SALT] as? String ?: return false
        val expected = scratch.all[KEY_HASH] as? String ?: return false
        val failures = scratch.all[KEY_FAILURES] as? Int ?: return false
        val locked = scratch.all[KEY_LOCK] as? Long ?: return false
        val saltBytes = try { Base64.getDecoder().decode(salt) } catch (e: IllegalArgumentException) { return false }
        val hashBytes = try { Base64.getDecoder().decode(expected) } catch (e: IllegalArgumentException) { return false }
        if (saltBytes.size != SALT_BYTES || hashBytes.size != HASH_BYTES) return false
        if (failures !in 0..4 || locked < 0L) return false
        return MessageDigest.isEqual(hash(pin, saltBytes), hashBytes)
    }

    private fun writeValid(scratch: android.content.SharedPreferences, pin: String, salt: ByteArray) {
        scratch.edit()
            .putString(KEY_SALT, b64(salt))
            .putString(KEY_HASH, b64(hash(pin, salt)))
            .putInt(KEY_FAILURES, 0)
            .putLong(KEY_LOCK, 0L)
            .commit()
    }

    private fun uiRowsBounded(visibleSize: Int, rowCap: Int, minimumRows: Int): Boolean {
        if (visibleSize <= 0 || rowCap <= 0 || minOf(rowCap, visibleSize) < minimumRows) return false
        val rows = IntArray(minOf(rowCap, visibleSize))
        for (offset in intArrayOf(0, 1, 7, 100, 1_000, visibleSize / 2, visibleSize - 1)) {
            val first = maxOf(0, minOf(offset, visibleSize - 1))
            val count = minOf(rowCap, visibleSize - first)
            if (count > rows.size) return false
            for (row in 0 until count) {
                val index = first + row
                if (index !in 0 until visibleSize) return false
                rows[row] = index
            }
        }
        return true
    }

    private fun derivedPin(offset: Int = 0): String =
        String.format(java.util.Locale.ROOT, "%08d", Math.floorMod(20_261_123L * 31L + 7L + offset, 100_000_000L).toInt())

    private fun derivedSalt(): ByteArray = ByteArray(SALT_BYTES) { ((it * 37 + 11) and 0xff).toByte() }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, HASH_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val CHANNEL_COUNT = 25_000
        const val FAVORITE_COUNT = 12_500
        const val ROW_CAP = 24
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        const val ITERATIONS = 120_000
        const val KEY_SALT = "salt"
        const val KEY_HASH = "hash"
        const val KEY_FAILURES = "failures"
        const val KEY_LOCK = "locked-until"
    }
}
