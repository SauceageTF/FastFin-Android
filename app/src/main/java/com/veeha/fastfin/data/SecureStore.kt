package com.veeha.fastfin.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credential storage: values sealed with an AES-GCM key that never leaves the
 * Android Keystore, plain app storage second.
 *
 * Same policy as the iOS build: the Keystore can fail for reasons outside the
 * app (a broken vendor implementation, a key invalidated by a restore), and
 * signing the user in with a visible warning beats blocking them behind an
 * error nobody can act on. [failure] is shown in Settings. Values are never
 * logged.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("fastfin.credentials", Context.MODE_PRIVATE)

    @Volatile
    var failure: String? = null
        private set

    fun read(name: String): String? {
        val raw = prefs.getString(name, null) ?: return null
        return when {
            raw.startsWith(SEALED) -> try {
                open(raw.removePrefix(SEALED))
            } catch (e: Exception) {
                note(e)
                null
            }
            raw.startsWith(PLAIN) -> raw.removePrefix(PLAIN)
            else -> null
        }
    }

    /** Writes every pair in one atomic SharedPreferences commit; null removes. */
    fun write(values: Map<String, String?>) {
        prefs.edit {
            for ((name, value) in values) {
                if (value == null) remove(name) else putString(name, seal(value))
            }
        }
    }

    private fun seal(value: String): String = try {
        SEALED + encrypt(value)
    } catch (e: Exception) {
        note(e)
        PLAIN + value
    }

    private fun note(error: Exception) {
        val reason = error.javaClass.simpleName + (error.message?.let { ": $it" } ?: "")
        if (failure != reason) {
            failure = reason
            Log.w(TAG, "Keystore unavailable, falling back to app storage ($reason)")
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun open(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "FastFin"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "fastfin.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val SEALED = "k:"
        const val PLAIN = "p:"
    }
}
