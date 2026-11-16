package com.example.data.sync

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Everything the cloud sync needs is derived from the sync password, on the phone:
 *  - [space]: which mailbox on the server (same password = same mailbox on every phone)
 *  - [token]: proves to the server that this phone knows the password
 *  - [key]: encrypts everything before it leaves the phone (AES-256-GCM)
 * The password itself is never stored or sent.
 */
class SyncCrypto private constructor(val space: String, val token: String, private val key: ByteArray) {

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all, 0, 12))
        return String(cipher.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }

    /** Saved in app-private storage so the password needn't be typed again. */
    fun export(): String = listOf(space, token, Base64.encodeToString(key, Base64.NO_WRAP)).joinToString("|")

    companion object {
        private const val ITERATIONS = 120_000

        fun fromPassword(password: String): SyncCrypto {
            val spec = PBEKeySpec(password.trim().toCharArray(), "inkvault-sync-v1".toByteArray(), ITERATIONS, 512)
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
            return SyncCrypto(
                space = hex(bytes.copyOfRange(32, 48)),
                token = hex(bytes.copyOfRange(48, 64)),
                key = bytes.copyOfRange(0, 32)
            )
        }

        fun import(saved: String?): SyncCrypto? = runCatching {
            val (s, t, k) = saved!!.split("|")
            SyncCrypto(s, t, Base64.decode(k, Base64.NO_WRAP))
        }.getOrNull()
    }
}
