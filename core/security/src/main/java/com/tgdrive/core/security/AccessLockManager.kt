package com.tgdrive.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the user's app-access password/PIN for Private Vault and chosen locked folders.
 *
 * Stored as a salted PBKDF2-HMAC-SHA256 hash in [KeystoreHelper] (separate from the PassVault
 * file encryption passphrase in [BackupCrypto]).
 */
@Singleton
class AccessLockManager @Inject constructor(
    private val keystoreHelper: KeystoreHelper,
) {
    fun hasPassword(): Boolean = !keystoreHelper.getString(KEY_ACCESS_LOCK_HASH).isNullOrBlank()

    fun setPassword(password: String): Boolean {
        val clean = password.trim()
        if (clean.length < MIN_PASSWORD_LENGTH) return false
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val chars = clean.toCharArray()
        val hash = try {
            deriveHash(chars, salt, PBKDF2_ITERATIONS)
        } finally {
            chars.fill('\u0000')
        }
        val encoded = try {
            "${salt.toHex()}:$PBKDF2_ITERATIONS:${hash.toHex()}"
        } finally {
            salt.fill(0)
            hash.fill(0)
        }
        keystoreHelper.putString(KEY_ACCESS_LOCK_HASH, encoded)
        return true
    }

    fun clearPassword() {
        keystoreHelper.remove(KEY_ACCESS_LOCK_HASH)
    }

    fun verifyPassword(candidate: String): Boolean {
        val clean = candidate.trim()
        if (clean.isEmpty()) return false
        val stored = keystoreHelper.getString(KEY_ACCESS_LOCK_HASH)?.takeIf { it.isNotBlank() } ?: return false
        val parts = stored.split(':')
        if (parts.size != 3) return false
        val salt = fromHex(parts[0]) ?: return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1_000..1_000_000 } ?: return false
        val expectedHash = fromHex(parts[2]) ?: return false
        val chars = clean.toCharArray()
        val actualHash = try {
            deriveHash(chars, salt, iterations)
        } catch (_: Throwable) {
            return false
        } finally {
            chars.fill('\u0000')
            salt.fill(0)
        }
        return try {
            MessageDigest.isEqual(expectedHash, actualHash)
        } finally {
            expectedHash.fill(0)
            actualHash.fill(0)
        }
    }

    private fun deriveHash(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun fromHex(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) { idx ->
                val hi = Character.digit(hex[idx * 2], 16)
                val lo = Character.digit(hex[idx * 2 + 1], 16)
                require(hi >= 0 && lo >= 0) { "Invalid hex" }
                ((hi shl 4) or lo).toByte()
            }
        }.getOrNull()
    }

    companion object {
        internal const val KEY_ACCESS_LOCK_HASH = "tgdrive_access_lock_hash_v1"
        const val MIN_PASSWORD_LENGTH = 4
        private const val SALT_BYTES = 16
        private const val PBKDF2_ITERATIONS = 120_000
        private const val KEY_BITS = 256
    }
}
