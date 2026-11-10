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

    fun load(name: String): String? {
        val encrypted = prefs.getString("$name.secure", null)
        if (encrypted != null) {
            val decrypted = runCatching {
                val bytes = Base64.decode(encrypted, Base64.DEFAULT)
                require(bytes.size > 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
                String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
            }.getOrNull()
            if (decrypted != null) return decrypted
            // Never discard a legacy fallback when an encrypted record cannot be decrypted.
            return prefs.getString(name, null)
        }

        // Migrate early alpha plaintext keys; preserve the only copy if encryption fails.
        val legacy = prefs.getString(name, null) ?: return null
        save(name, legacy)
        return legacy
    }

    fun save(name: String, value: String): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("$name.secure", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .remove(name)
            .commit()
    }.getOrDefault(false)

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
