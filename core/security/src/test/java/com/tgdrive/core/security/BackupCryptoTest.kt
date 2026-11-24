package com.tgdrive.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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

    @Test
    fun `passphrase TGE2 stream encrypts and decrypts across multiple chunks`() {
        val passphrase = "correct-horse-battery-staple".toCharArray()
        val payload = ByteArray(140 * 1024).also { SecureRandom(byteArrayOf(7)).nextBytes(it) }

        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encryptStreamWithPassphrase(
            passphrase = passphrase,
            input = ByteArrayInputStream(payload),
            output = encrypted,
            chunkSize = 16 * 1024,
            iterations = 2_000,
        )
        val cipherBytes = encrypted.toByteArray()
        assertThat(BackupCrypto.detectMagicFormat(cipherBytes)).isEqualTo(EncryptedFileFormat.PASSPHRASE_TGE2)

        val decrypted = ByteArrayOutputStream()
        BackupCrypto.decryptStreamWithPassphrase(
            passphrase = passphrase,
            input = ByteArrayInputStream(cipherBytes),
            output = decrypted,
        )
        assertThat(decrypted.toByteArray()).isEqualTo(payload)
    }

    @Test
    fun `passphrase TGE2 rejects wrong passphrase and tampered bytes`() {
        val payload = "confidential passphrase data ".repeat(150).toByteArray()
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encryptStreamWithPassphrase(
            passphrase = "right-password".toCharArray(),
            input = ByteArrayInputStream(payload),
            output = encrypted,
            chunkSize = 1024,
            iterations = 2_000,
        )
        val cipherBytes = encrypted.toByteArray()

        assertThrows<AEADBadTagException> {
            BackupCrypto.decryptStreamWithPassphrase(
                passphrase = "wrong-password".toCharArray(),
                input = ByteArrayInputStream(cipherBytes),
                output = ByteArrayOutputStream(),
            )
        }

        val tampered = cipherBytes.copyOf()
        tampered[tampered.size - 8] = (tampered[tampered.size - 8].toInt() xor 0xFF).toByte()
        assertThrows<AEADBadTagException> {
            BackupCrypto.decryptStreamWithPassphrase(
                passphrase = "right-password".toCharArray(),
                input = ByteArrayInputStream(tampered),
                output = ByteArrayOutputStream(),
            )
        }
    }

    @Test
    fun `detectMagicFormat and filename helpers handle TGE1 TGE2 unknown and tgenc extension`(@TempDir tmp: File) {
        val v1 = ByteArrayOutputStream()
        BackupCrypto.encryptStream(ByteArray(32) { 3 }, ByteArrayInputStream("abc".toByteArray()), v1)
        assertThat(BackupCrypto.detectMagicFormat(v1.toByteArray())).isEqualTo(EncryptedFileFormat.VAULT_TGE1)

        val v2 = ByteArrayOutputStream()
        BackupCrypto.encryptStreamWithPassphrase(
            passphrase = "secret".toCharArray(),
            input = ByteArrayInputStream("abc".toByteArray()),
            output = v2,
            iterations = 2_000,
        )
        assertThat(BackupCrypto.detectMagicFormat(v2.toByteArray())).isEqualTo(EncryptedFileFormat.PASSPHRASE_TGE2)
        assertThat(BackupCrypto.detectMagicFormat("plain".toByteArray())).isEqualTo(EncryptedFileFormat.UNKNOWN)
        assertThat(BackupCrypto.detectMagicFormat(ByteArray(2))).isEqualTo(EncryptedFileFormat.UNKNOWN)

        assertThat(BackupCrypto.isEncryptedFileName("photo.jpg.tgenc")).isTrue()
        assertThat(BackupCrypto.isEncryptedFileName("DOC.PDF.TGENC")).isTrue()
        assertThat(BackupCrypto.isEncryptedFileName("archive.zip")).isFalse()

        assertThat(BackupCrypto.stripEncryptedExtension("photo.jpg.tgenc")).isEqualTo("photo.jpg")
        assertThat(BackupCrypto.stripEncryptedExtension("DOC.PDF.TGENC")).isEqualTo("DOC.PDF")
        assertThat(BackupCrypto.stripEncryptedExtension(".tgenc")).isEqualTo("decrypted_file")
        assertThat(BackupCrypto.stripEncryptedExtension("plain.txt")).isEqualTo("plain.txt")
        assertThat(tmp.exists()).isTrue()
    }
}
