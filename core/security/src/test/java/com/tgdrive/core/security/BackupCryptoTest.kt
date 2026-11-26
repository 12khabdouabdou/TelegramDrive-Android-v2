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
    fun `portable TGE2 encryption decrypts across fresh instances without device key`(@TempDir tmp: File) {
        val crypto1 = BackupCrypto(inMemoryKeystore())
        val crypto2 = BackupCrypto(inMemoryKeystore())
        crypto1.setVaultPassphrase("cross-device-secret-99")
        val plain = File(tmp, "video.mp4").apply { writeBytes("fake-mp4-header-and-frames".repeat(64).toByteArray()) }
        val enc = File(tmp, "video.mp4.tgenc")
        crypto1.encryptFile(plain, enc)

        assertThat(crypto1.detectFormat(enc)).isEqualTo(EncryptedFileFormat.PASSPHRASE_TGE2)
        assertThat(crypto2.passphraseFingerprint()).isNull()

        crypto2.setVaultPassphrase("cross-device-secret-99")
        assertThat(crypto2.passphraseFingerprint()).isEqualTo(crypto1.passphraseFingerprint())

        val dec = File(tmp, "restored.mp4")
        crypto2.decryptFile(enc, dec)
        assertThat(dec.readBytes()).isEqualTo(plain.readBytes())
    }

    @Test
    fun `cancelCheck aborts encryptFile mid-stream and deletes partial output`(@TempDir tmp: File) {
        val crypto = BackupCrypto(inMemoryKeystore())
        crypto.setVaultPassphrase("cancel-check-pass")
        val plain = File(tmp, "big.bin").apply { writeBytes(ByteArray(256 * 1024) { it.toByte() }) }
        val out = File(tmp, "big.bin.tgenc")

        var checks = 0
        assertThrows<CancellationException> {
            crypto.encryptFile(plain, out, cancelCheck = { checks++ >= 1 })
        }
        assertThat(out.exists()).isFalse()
    }

    @Test
    fun `legacy TGE1 file is rejected cleanly and thumbnails + verifiers work cross-device`(@TempDir tmp: File) {
        val src = BackupCrypto(inMemoryKeystore())
        val dst = BackupCrypto(inMemoryKeystore())
        src.setVaultPassphrase("portable-thumb-pass")
        dst.setVaultPassphrase("portable-thumb-pass")

        // Portable thumbnail key works across installs
        val thumbPlain = "jpeg-thumbnail-payload".toByteArray()
        val encThumb = src.encryptThumbnailBytes(thumbPlain)
        assertThat(encThumb).isNotNull()
        assertThat(dst.decryptThumbnailBytes(encThumb!!)).isEqualTo(thumbPlain)

        // Changing passphrase invalidates cached thumbnail subkey
        dst.setVaultPassphrase("wrong-passphrase")
        assertThat(dst.decryptThumbnailBytes(encThumb)).isNull()

        // Cloud passphrase verifier verifies without exposing plaintext
        val verifier = src.createPassphraseVerifier("portable-thumb-pass")
        assertThat(BackupCrypto.detectMagicFormat(verifier)).isEqualTo(EncryptedFileFormat.PASSPHRASE_TGE2)
        assertThat(dst.verifyPassphraseAgainstVerifier(verifier, "portable-thumb-pass")).isTrue()
        assertThat(dst.verifyPassphraseAgainstVerifier(verifier, "wrong-passphrase")).isFalse()

        // Legacy TGE1 files fail cleanly with WrongPassphraseException
        val legacyFile = File(tmp, "legacy.jpg.tgenc")
        legacyFile.outputStream().use { out ->
            BackupCrypto.encryptStream(ByteArray(32) { 7 }, ByteArrayInputStream("old-data".toByteArray()), out)
        }
        assertThrows<WrongPassphraseException> {
            dst.decryptFile(legacyFile, File(tmp, "legacy.jpg"), passphrase = "portable-thumb-pass")
        }
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

    @Test
    fun `createPassphraseVerifier requires at least 8 characters`() {
        val crypto = BackupCrypto(inMemoryKeystore())
        assertThrows<IllegalArgumentException> {
            crypto.createPassphraseVerifier("1234567")
        }
        val wrapped = crypto.createPassphraseVerifier("12345678")
        assertThat(BackupCrypto.detectMagicFormat(wrapped)).isEqualTo(EncryptedFileFormat.PASSPHRASE_TGE2)
    }

    @Test
    fun `encryptFileWithBackupSettings throws instead of downgrading when passphrase is missing`(@TempDir tmp: File) {
        val crypto = BackupCrypto(inMemoryKeystore())
        val src = File(tmp, "secret.txt").apply { writeText("sensitive") }
        val dst = File(tmp, "secret.txt.tgenc")

        assertThrows<IllegalStateException> {
            crypto.encryptFileWithBackupSettings(src, dst)
        }
    }

    @Test
    fun `decryptStream rejects trailing data appended after EOF sentinel`() {
        val key = ByteArray(32) { 5 }
        val payload = "hello".toByteArray()
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encryptStream(key, ByteArrayInputStream(payload), encrypted)
        val extended = encrypted.toByteArray() + byteArrayOf(0x01, 0x02, 0x03)

        assertThrows<AEADBadTagException> {
            BackupCrypto.decryptStream(key, ByteArrayInputStream(extended), ByteArrayOutputStream())
        }
    }

    @Test
    fun `encryptInputStream and encryptInputStreamWithBackupSettings round trip and clean up on cancel`(@TempDir tmp: File) {
        val crypto = BackupCrypto(inMemoryKeystore())
        crypto.setVaultPassphrase("direct-stream-pass")
        val payload = "Direct encrypted stream avoids 2x disk copy ".repeat(200).toByteArray()

        val directOut = File(tmp, "direct_stream.tgenc")
        crypto.encryptInputStream(
            input = ByteArrayInputStream(payload),
            outputFile = directOut,
            totalBytes = payload.size.toLong(),
        )
        assertThat(BackupCrypto.detectMagicFormat(directOut.readBytes())).isEqualTo(EncryptedFileFormat.PASSPHRASE_TGE2)
        val directDec = File(tmp, "direct_dec.bin")
        crypto.decryptFile(directOut, directDec)
        assertThat(directDec.readBytes()).isEqualTo(payload)

        val customOut = File(tmp, "custom_stream.tgenc")
        crypto.encryptInputStreamWithBackupSettings(
            input = ByteArrayInputStream(payload),
            outputFile = customOut,
            totalBytes = payload.size.toLong(),
        )
        assertThat(BackupCrypto.detectMagicFormat(customOut.readBytes())).isEqualTo(EncryptedFileFormat.PASSPHRASE_TGE2)
        val customDec = File(tmp, "custom_dec.bin")
        crypto.decryptFile(customOut, customDec)
        assertThat(customDec.readBytes()).isEqualTo(payload)

        val cancelledOut = File(tmp, "cancelled_stream.tgenc")
        assertThrows<CancellationException> {
            crypto.encryptInputStream(
                input = ByteArrayInputStream(ByteArray(256 * 1024) { 7 }),
                outputFile = cancelledOut,
                cancelCheck = { true },
            )
        }
        assertThat(cancelledOut.exists()).isFalse()
    }

    @Test
    fun `encryptVaultBlob and decryptVaultBlob round trip cross-device and fail on wrong passphrase`() {
        val src = BackupCrypto(inMemoryKeystore())
        val dst = BackupCrypto(inMemoryKeystore())
        val payload = "{\"entries\":[{\"chatId\":10,\"ocrText\":\"PASSPORT\"}]}".toByteArray()

        // Fails cleanly when passphrase is not set
        assertThat(src.encryptVaultBlob(payload)).isNull()

        src.setVaultPassphrase("vault-index-pass")
        dst.setVaultPassphrase("vault-index-pass")
        val cipher = src.encryptVaultBlob(payload)
        assertThat(cipher).isNotNull()
        assertThat(BackupCrypto.detectMagicFormat(cipher!!)).isEqualTo(EncryptedFileFormat.PASSPHRASE_TGE2)
        assertThat(dst.decryptVaultBlob(cipher)).isEqualTo(payload)

        dst.setVaultPassphrase("wrong-vault-pass")
        assertThat(dst.decryptVaultBlob(cipher)).isNull()
    }
}
