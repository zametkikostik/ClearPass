package com.clearpass.app.security

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.clearpass.app.util.LogCollector
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object UriCipher {

    private const val PREFS = "clearpass_crypto"
    private const val KEY_MATERIAL = "aes_key_b64"

    fun encrypt(context: Context, plain: String): String? {
        return try {
            val key = keyBytes(context)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            val iv = cipher.iv
            val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + enc.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(enc, 0, out, iv.size, enc.size)
            Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            LogCollector.w("Crypto", "encrypt: ${e.message}")
            null
        }
    }

    fun decrypt(context: Context, blob: String): String? {
        return try {
            val all = Base64.decode(blob, Base64.NO_WRAP)
            if (all.size < 13) return null
            val iv = all.copyOfRange(0, 12)
            val data = all.copyOfRange(12, all.size)
            val key = keyBytes(context)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            LogCollector.w("Crypto", "decrypt: ${e.message}")
            null
        }
    }

    private fun keyBytes(context: Context): ByteArray {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS,
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        var b64 = prefs.getString(KEY_MATERIAL, null)
        if (b64 == null) {
            val raw = MessageDigest.getInstance("SHA-256")
                .digest((master.toString() + System.nanoTime()).toByteArray())
            b64 = Base64.encodeToString(raw, Base64.NO_WRAP)
            prefs.edit().putString(KEY_MATERIAL, b64).apply()
        }
        return Base64.decode(b64, Base64.NO_WRAP).copyOf(16)
    }
}
