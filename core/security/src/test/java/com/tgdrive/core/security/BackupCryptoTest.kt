package com.tgdrive.core.security

import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CancellationException
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

    private fun inMemoryKeystore(): KeystoreHelper {
        val map = mutableMapOf<String, String>()
        val ctx = object : ContextWrapper(null) {}
        return object : KeystoreHelper(ctx) {
            override fun getString(key: String, default: String?): String? = map[key] ?: default
            override fun putString(key: String, value: String): Boolean {
                map[key] = value
                return true
            }
            override fun remove(key: String): Boolean {
                map.remove(key)
                return true
            }
        }
    }

    @Test
    fun `exportVaultKeyBase64 and importVaultKeyBase64 restore TGE1 decryption across instances`(@TempDir tmp: File) {
        val crypto1 = BackupCrypto(inMemoryKeystore())
        val crypto2 = BackupCrypto(inMemoryKeystore())
        val plain = File(tmp, "video.mp4").apply { writeBytes("fake-mp4-header-and-frames".repeat(64).toByteArray()) }
        val enc = File(tmp, "video.mp4.tgenc")
        crypto1.encryptFile(plain, enc)

        val exported = crypto1.exportVaultKeyBase64()
        assertThat(crypto2.importVaultKeyBase64("not-base64!")).isFalse()
        assertThat(crypto2.importVaultKeyBase64(java.util.Base64.getEncoder().encodeToString(ByteArray(8)))).isFalse()
        assertThat(crypto2.importVaultKeyBase64(exported)).isTrue()
        assertThat(crypto2.vaultKeyFingerprint()).isEqualTo(crypto1.vaultKeyFingerprint())

        val dec = File(tmp, "restored.mp4")
        crypto2.decryptFile(enc, dec)
        assertThat(dec.readBytes()).isEqualTo(plain.readBytes())
    }

    @Test
    fun `cancelCheck aborts encryptFile mid-stream and deletes partial output`(@TempDir tmp: File) {
        val crypto = BackupCrypto(inMemoryKeystore())
        val plain = File(tmp, "big.bin").apply { writeBytes(ByteArray(256 * 1024) { it.toByte() }) }
        val out = File(tmp, "big.bin.tgenc")

        var checks = 0
        assertThrows<CancellationException> {
            crypto.encryptFile(plain, out, cancelCheck = { checks++ >= 1 })
        }
        assertThat(out.exists()).isFalse()
    }

    @Test
    fun `wrapVaultKeyWithPassphrase and unwrapAndImportVaultKey recover vault across devices`(@TempDir tmp: File) {
        val src = BackupCrypto(inMemoryKeystore())
        val dst = BackupCrypto(inMemoryKeystore())
        val secretFile = File(tmp, "doc.txt").apply { writeBytes("top-secret-vault-data".repeat(20).toByteArray()) }
        val encFile = File(tmp, "doc.txt.tgenc")
        src.encryptFile(secretFile, encFile)

        val wrapped = src.wrapVaultKeyWithPassphrase("recovery-pass-2026")
        assertThat(BackupCrypto.detectMagicFormat(wrapped)).isEqualTo(EncryptedFileFormat.PASSPHRASE_TGE2)

        assertThrows<WrongPassphraseException> {
            dst.unwrapAndImportVaultKey(wrapped, "wrong-recovery-pass")
        }
        assertThat(dst.unwrapAndImportVaultKey(wrapped, "recovery-pass-2026")).isTrue()
        assertThat(dst.vaultKeyFingerprint()).isEqualTo(src.vaultKeyFingerprint())

        val outFile = File(tmp, "doc.txt")
        dst.decryptFile(encFile, outFile)
        assertThat(outFile.readBytes()).isEqualTo(secretFile.readBytes())
    }

    @Test
    fun `reused GCM cipher encrypts and decrypts many odd-sized chunks reliably`() {
        val key = ByteArray(32).also { SecureRandom(byteArrayOf(42)).nextBytes(it) }
        val payload = ByteArray(100 * 1024 + 317).also { SecureRandom(byteArrayOf(99)).nextBytes(it) }

        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encryptStream(key, ByteArrayInputStream(payload), encrypted, chunkSize = 4096)

        val decrypted = ByteArrayOutputStream()
        BackupCrypto.decryptStream(key, ByteArrayInputStream(encrypted.toByteArray()), decrypted)

        assertThat(decrypted.toByteArray()).isEqualTo(payload)
    }

    @Test
    fun `legacy 120k-iteration TGE2 payload remains decryptable`() {
        val passphrase = "legacy-vault-passphrase".toCharArray()
        val payload = "legacy encrypted backup stream ".repeat(300).toByteArray()

        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encryptStreamWithPassphrase(
            passphrase = passphrase,
            input = ByteArrayInputStream(payload),
            output = encrypted,
            chunkSize = 4096,
            iterations = 120_000,
        )

        val decrypted = ByteArrayOutputStream()
        BackupCrypto.decryptStreamWithPassphrase(
            passphrase = passphrase,
            input = ByteArrayInputStream(encrypted.toByteArray()),
            output = decrypted,
        )
        assertThat(decrypted.toByteArray()).isEqualTo(payload)
    }
}
