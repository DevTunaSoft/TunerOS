package com.devtunasoft.tuneros

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore AES-GCM protection for playlist and guide URLs, which may contain credentials. */
internal class SecureSourceStore(context: Context) {
    private val prefs = context.getSharedPreferences("tuneros_alpha", Context.MODE_PRIVATE)
    private val alias = "tuneros_sources_v1"

    companion object {
        private const val MAX_SECRET_CHARS = 4_096
        private const val MAX_SECRET_BYTES = 8_192
        // IV (12) + ciphertext (MAX_SECRET_BYTES) + tag (16), Base64 with slack.
        private const val MAX_ENCRYPTED_CHARS = 11_000
    }

    fun load(name: String): String? {
        val encrypted = prefs.getString("$name.secure", null)
        if (encrypted != null) {
            if (encrypted.length > MAX_ENCRYPTED_CHARS) return null
            val decrypted = runCatching {
                val bytes = Base64.decode(encrypted, Base64.DEFAULT)
                require(bytes.size > 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
                String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
            }.getOrNull()
            // A protected record that cannot be decrypted is never replaced by an
            // unencrypted fallback: the source is unavailable, not disclosed.
            return decrypted
        }

        // Migrate early alpha plaintext keys; preserve the only copy if encryption fails.
        val legacy = prefs.getString(name, null) ?: return null
        if (legacy.length > MAX_SECRET_CHARS) return null
        save(name, legacy)
        return legacy
    }

    fun delete(name: String): Boolean =
        prefs.edit().remove("$name.secure").remove(name).commit()

    /** Atomically remove three encrypted + legacy source fields in one disk commit. */
    fun deleteThree(first: String, second: String, third: String): Boolean =
        prefs.edit()
            .remove("$first.secure").remove(first)
            .remove("$second.secure").remove(second)
            .remove("$third.secure").remove(third)
            .commit()

    fun save(name: String, value: String): Boolean {
        // Reject oversized provider input before allocating an encrypted copy.
        if (value.length > MAX_SECRET_CHARS) return false
        return runCatching {
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_SECRET_BYTES) {
                false
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                val encrypted = cipher.iv + cipher.doFinal(bytes)
                prefs.edit()
                    .putString("$name.secure", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .remove(name)
                    .commit()
            }
        }.getOrDefault(false)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
