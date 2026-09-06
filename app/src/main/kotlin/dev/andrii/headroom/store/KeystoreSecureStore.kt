package dev.andrii.headroom.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption with a non-exportable key held in the Android Keystore.
 *
 * Written directly against Keystore rather than using EncryptedSharedPreferences,
 * which is deprecated. The ciphertext is stored in ordinary SharedPreferences —
 * safe, because the key never leaves the hardware-backed store.
 */
class KeystoreSecureStore(context: Context) : SecureStore {

    private val prefs = context.getSharedPreferences("headroom.secure", Context.MODE_PRIVATE)

    override fun put(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val ciphertext = cipher.doFinal(value.toByteArray())
        // IV is generated per write and stored alongside; it is not secret.
        val packed = cipher.iv + ciphertext
        prefs.edit().putString(key, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    override fun get(key: String): String? {
        val packed = prefs.getString(key, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        if (packed.size <= IV_BYTES) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES),
                )
            }
            String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES))
        } catch (_: Exception) {
            // Key rotated or data tampered with: treat as absent so the app
            // prompts for a re-link rather than crashing (spec §7). The cipher
            // init is inside the try because a rotated key throws there, not at
            // doFinal - outside it, the crash this guard exists to prevent
            // happens anyway.
            null
        }
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "headroom.credential"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
