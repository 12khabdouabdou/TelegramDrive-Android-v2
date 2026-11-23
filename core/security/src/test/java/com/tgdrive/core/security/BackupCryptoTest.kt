package com.tgdrive.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

class BackupCryptoTest {

    @Test
    fun `encryptStream and decryptStream round trip across multiple chunks`() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val payload = ByteArray(210 * 1024).also { SecureRandom().nextBytes(it) }

        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encryptStream(key, ByteArrayInputStream(payload), encrypted, chunkSize = 16 * 1024)

        val decrypted = ByteArrayOutputStream()
        BackupCrypto.decryptStream(key, ByteArrayInputStream(encrypted.toByteArray()), decrypted)

        assertThat(decrypted.toByteArray()).isEqualTo(payload)
    }

    @Test
    fun `empty payload encrypts and decrypts cleanly`() {
        val key = ByteArray(32) { it.toByte() }
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encryptStream(key, ByteArrayInputStream(ByteArray(0)), encrypted)

        val decrypted = ByteArrayOutputStream()
        BackupCrypto.decryptStream(key, ByteArrayInputStream(encrypted.toByteArray()), decrypted)

        assertThat(decrypted.toByteArray()).isEmpty()
    }

    @Test
    fun `wrong key or tampered or truncated stream throws AEADBadTagException`() {
        val key = ByteArray(32) { 1 }
        val wrongKey = ByteArray(32) { 2 }
        val payload = "sensitive tax document bytes ".repeat(400).toByteArray()

        val encryptedOut = ByteArrayOutputStream()
        BackupCrypto.encryptStream(key, ByteArrayInputStream(payload), encryptedOut, chunkSize = 1024)
        val cipherBytes = encryptedOut.toByteArray()

        assertThrows<AEADBadTagException> {
            BackupCrypto.decryptStream(wrongKey, ByteArrayInputStream(cipherBytes), ByteArrayOutputStream())
        }

        val tampered = cipherBytes.copyOf()
        tampered[tampered.size - 10] = (tampered[tampered.size - 10].toInt() xor 0xFF).toByte()
        assertThrows<AEADBadTagException> {
            BackupCrypto.decryptStream(key, ByteArrayInputStream(tampered), ByteArrayOutputStream())
        }

        val truncated = cipherBytes.copyOf(cipherBytes.size - 25)
        assertThrows<Exception> {
            BackupCrypto.decryptStream(key, ByteArrayInputStream(truncated), ByteArrayOutputStream())
        }
    }
}
