package com.yinling.hotline

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
 * The API key, encrypted at rest with a key that lives in the Android Keystore.
 *
 * Why it is worth storing at all: the person who sets this app up is the family, once. A key that
 * only lives in memory means the assistant silently stops working after every restart — and an
 * elderly user cannot retype a 35-character secret.
 *
 * Kept deliberately small: AES-GCM with one Keystore key, no rotation, no biometrics. If the
 * Keystore key is gone (lock screen changed, data restored onto another phone) the stored value is
 * dropped rather than guessed at.
 */
object SecretStore {

    private const val PREFS = "hotline"
    private const val KEY_CIPHERTEXT = "api_key_enc"
    private const val KEY_IV = "api_key_iv"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "hotline_api_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun save(context: Context, plain: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (plain.isBlank()) {
            prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val bytes = cipher.doFinal(plain.toByteArray())
            prefs.edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(bytes, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()
        }.onFailure { LoopLog.event("[key] 保存失败，本次不落盘：${it.message}") }
    }

    fun load(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val data = prefs.getString(KEY_CIPHERTEXT, null) ?: return ""
        val iv = prefs.getString(KEY_IV, null) ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
        }.getOrElse {
            prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
            LoopLog.event("[key] 解密失败，已清除（需要重新填写）：${it.message}")
            ""
        }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }
}
