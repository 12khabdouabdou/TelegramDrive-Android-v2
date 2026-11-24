package com.tgdrive.core.security

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

enum class EncryptedFileFormat {
    VAULT_TGE1,
    PASSPHRASE_TGE2,
    UNKNOWN,
}

class WrongPassphraseException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Streaming chunked AES-256-GCM helper used by AutoBackup and manual uploads/decryptions.
 *
 * Supports two authenticated container formats:
 * - `"TGE1"`: device Vault Key stored in [KeystoreHelper]
 * - `"TGE2"`: passphrase-derived key via PBKDF2WithHmacSHA256 (cross-device portable)
 *
 * Both formats process data in bounded chunks with constant O(1) memory and authenticate
 * every chunk plus a final EOF sentinel chunk so truncation or reordering fails with
 * [AEADBadTagException].
 */
@Singleton
class BackupCrypto @Inject constructor(
    private val keystoreHelper: KeystoreHelper,
) {

    @Synchronized
    fun vaultKey(): ByteArray {
        val stored = keystoreHelper.getString(KEY_BACKUP_VAULT_V1)
        if (!stored.isNullOrBlank()) {
            val decoded = runCatching { Base64.getDecoder().decode(stored.trim()) }.getOrNull()
            if (decoded != null && decoded.size == KEY_BYTES) {
                return decoded
            }
        }
        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        keystoreHelper.putString(KEY_BACKUP_VAULT_V1, Base64.getEncoder().encodeToString(key))
        return key
    }

    /** Password string derived from [vaultKey] for AES-256 encrypted ZIP archives. */
    fun zipVaultPassword(): String = Base64.getEncoder().encodeToString(vaultKey())

    fun getCustomBackupPassphrase(): String? =
        keystoreHelper.getString(KEY_BACKUP_PASSPHRASE_V1)?.takeIf { it.isNotBlank() }

    fun hasCustomBackupPassphrase(): Boolean = getCustomBackupPassphrase() != null

    fun setCustomBackupPassphrase(passphrase: String?) {
        val clean = passphrase?.trim()
        if (clean.isNullOrBlank()) {
            keystoreHelper.remove(KEY_BACKUP_PASSPHRASE_V1)
        } else {
            keystoreHelper.putString(KEY_BACKUP_PASSPHRASE_V1, clean)
        }
    }

    fun activeBackupZipPassword(useCustomPassphrase: Boolean): String {
        if (useCustomPassphrase) {
            val custom = getCustomBackupPassphrase()
            if (!custom.isNullOrBlank()) return custom
        }
        return zipVaultPassword()
    }

    fun encryptFileWithBackupSettings(
        inputFile: File,
        outputFile: File,
        useCustomPassphrase: Boolean,
        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        val custom = if (useCustomPassphrase) getCustomBackupPassphrase() else null
        encryptFile(
            inputFile = inputFile,
            outputFile = outputFile,
            passphrase = custom,
            onProgress = onProgress,
        )
    }

    fun encryptFile(
        inputFile: File,
        outputFile: File,
        passphrase: String? = null,
        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        val total = inputFile.length().coerceAtLeast(0L)
        outputFile.parentFile?.mkdirs()
        try {
            BufferedInputStream(FileInputStream(inputFile), DEFAULT_CHUNK_SIZE).use { input ->
                BufferedOutputStream(FileOutputStream(outputFile), DEFAULT_CHUNK_SIZE).use { output ->
                    val cleanPass = passphrase?.takeIf { it.isNotBlank() }
                    if (cleanPass != null) {
                        encryptStreamWithPassphrase(
                            passphrase = cleanPass.toCharArray(),
                            input = input,
                            output = output,
                            onBytesProcessed = onProgress?.let { cb -> { bytes -> cb(bytes, total) } },
                        )
                    } else {
                        val key = vaultKey()
                        encryptStream(
                            keyBytes = key,
                            input = input,
                            output = output,
                            onBytesProcessed = onProgress?.let { cb -> { bytes -> cb(bytes, total) } },
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            runCatching { outputFile.delete() }
            throw t
        }
    }

    fun detectFormat(file: File): EncryptedFileFormat {
        if (!file.isFile || file.length() < MAGIC.size) return EncryptedFileFormat.UNKNOWN
        return runCatching {
            FileInputStream(file).use { fis ->
                val header = ByteArray(MAGIC.size)
                if (fis.read(header) != MAGIC.size) return@runCatching EncryptedFileFormat.UNKNOWN
                detectMagicFormat(header)
            }
        }.getOrDefault(EncryptedFileFormat.UNKNOWN)
    }

    fun decryptFile(
        inputFile: File,
        outputFile: File,
        passphrase: String? = null,
        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        val total = inputFile.length().coerceAtLeast(0L)
        outputFile.parentFile?.mkdirs()
        val format = detectFormat(inputFile)
        try {
            BufferedInputStream(FileInputStream(inputFile), DEFAULT_CHUNK_SIZE).use { input ->
                BufferedOutputStream(FileOutputStream(outputFile), DEFAULT_CHUNK_SIZE).use { output ->
                    val progressCb = onProgress?.let { cb -> { bytes: Long -> cb(bytes, total) } }
                    when (format) {
                        EncryptedFileFormat.VAULT_TGE1 -> {
                            decryptStream(
                                keyBytes = vaultKey(),
                                input = input,
                                output = output,
                                onBytesProcessed = progressCb,
                            )
                        }
                        EncryptedFileFormat.PASSPHRASE_TGE2 -> {
                            val resolved = passphrase?.takeIf { it.isNotBlank() } ?: getCustomBackupPassphrase()
                                ?: throw WrongPassphraseException("Passphrase is required to decrypt this file")
                            decryptStreamWithPassphrase(
                                passphrase = resolved.toCharArray(),
                                input = input,
                                output = output,
                                onBytesProcessed = progressCb,
                            )
                        }
                        EncryptedFileFormat.UNKNOWN -> {
                            throw IllegalArgumentException("Unrecognized encrypted file header")
                        }
                    }
                }
            }
        } catch (e: AEADBadTagException) {
            runCatching { outputFile.delete() }
            throw WrongPassphraseException("Incorrect key or passphrase, or file is corrupted", e)
        } catch (t: Throwable) {
            runCatching { outputFile.delete() }
            throw t
        }
    }

    companion object {
        const val ENCRYPTED_EXT = ".tgenc"
        private const val KEY_BACKUP_VAULT_V1 = "tgdrive_backup_vault_key_v1"
        private const val KEY_BACKUP_PASSPHRASE_V1 = "tgdrive_backup_custom_passphrase_v1"
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val SALT_BYTES = 16
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
        const val PBKDF2_ITERATIONS = 120_000
        private val MAGIC = byteArrayOf('T'.code.toByte(), 'G'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
        private val MAGIC_V2 = byteArrayOf('T'.code.toByte(), 'G'.code.toByte(), 'E'.code.toByte(), '2'.code.toByte())
        private const val FLAG_DATA: Byte = 0
        private const val FLAG_EOF: Byte = 1

        fun isEncryptedFileName(fileName: String): Boolean =
            fileName.endsWith(ENCRYPTED_EXT, ignoreCase = true)

        fun stripEncryptedExtension(fileName: String): String {
            if (!isEncryptedFileName(fileName)) return fileName
            val stripped = fileName.dropLast(ENCRYPTED_EXT.length)
            return stripped.ifBlank { "decrypted_file" }
        }

        fun detectMagicFormat(headerBytes: ByteArray): EncryptedFileFormat {
            if (headerBytes.size < MAGIC.size) return EncryptedFileFormat.UNKNOWN
            val prefix = headerBytes.copyOfRange(0, MAGIC.size)
            return when {
                prefix.contentEquals(MAGIC) -> EncryptedFileFormat.VAULT_TGE1
                prefix.contentEquals(MAGIC_V2) -> EncryptedFileFormat.PASSPHRASE_TGE2
                else -> EncryptedFileFormat.UNKNOWN
            }
        }

        fun deriveKeyFromPassphrase(
            passphrase: CharArray,
            salt: ByteArray,
            iterations: Int = PBKDF2_ITERATIONS,
        ): ByteArray {
            require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
            require(salt.size == SALT_BYTES) { "Expected $SALT_BYTES-byte salt" }
            require(iterations in 1_000..1_000_000) { "Invalid PBKDF2 iteration count: $iterations" }
            val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BYTES * 8)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        fun encryptStreamWithPassphrase(
            passphrase: CharArray,
            input: InputStream,
            output: OutputStream,
            chunkSize: Int = DEFAULT_CHUNK_SIZE,
            iterations: Int = PBKDF2_ITERATIONS,
            onBytesProcessed: ((Long) -> Unit)? = null,
        ) {
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val derivedKey = deriveKeyFromPassphrase(passphrase, salt, iterations)
            try {
                val dos = DataOutputStream(output)
                dos.write(MAGIC_V2)
                dos.write(salt)
                dos.writeInt(iterations)
                writeEncryptedChunks(
                    keyBytes = derivedKey,
                    input = input,
                    dos = dos,
                    chunkSize = chunkSize,
                    onBytesProcessed = onBytesProcessed,
                )
                dos.flush()
            } finally {
                derivedKey.fill(0)
            }
        }

        fun decryptStreamWithPassphrase(
            passphrase: CharArray,
            input: InputStream,
            output: OutputStream,
            onBytesProcessed: ((Long) -> Unit)? = null,
        ) {
            val dis = DataInputStream(input)
            val magic = ByteArray(MAGIC_V2.size)
            dis.readFully(magic)
            if (!magic.contentEquals(MAGIC_V2)) {
                throw IllegalArgumentException("Invalid passphrase-encrypted stream header")
            }
            val salt = ByteArray(SALT_BYTES)
            dis.readFully(salt)
            val iterations = dis.readInt()
            val derivedKey = deriveKeyFromPassphrase(passphrase, salt, iterations)
            try {
                readEncryptedChunks(
                    keyBytes = derivedKey,
                    dis = dis,
                    output = output,
                    onBytesProcessed = onBytesProcessed,
                )
            } finally {
                derivedKey.fill(0)
            }
        }

        fun encryptStream(
            keyBytes: ByteArray,
            input: InputStream,
            output: OutputStream,
            chunkSize: Int = DEFAULT_CHUNK_SIZE,
            onBytesProcessed: ((Long) -> Unit)? = null,
        ) {
            val dos = DataOutputStream(output)
            dos.write(MAGIC)
            writeEncryptedChunks(
                keyBytes = keyBytes,
                input = input,
                dos = dos,
                chunkSize = chunkSize,
                onBytesProcessed = onBytesProcessed,
            )
            dos.flush()
        }

        fun decryptStream(
            keyBytes: ByteArray,
            input: InputStream,
            output: OutputStream,
            onBytesProcessed: ((Long) -> Unit)? = null,
        ) {
            val dis = DataInputStream(input)
            val magic = ByteArray(MAGIC.size)
            dis.readFully(magic)
            if (!magic.contentEquals(MAGIC)) {
                throw IllegalArgumentException("Invalid encrypted backup stream header")
            }
            readEncryptedChunks(
                keyBytes = keyBytes,
                dis = dis,
                output = output,
                onBytesProcessed = onBytesProcessed,
            )
        }

        private fun writeEncryptedChunks(
            keyBytes: ByteArray,
            input: InputStream,
            dos: DataOutputStream,
            chunkSize: Int,
            onBytesProcessed: ((Long) -> Unit)?,
        ) {
            require(keyBytes.size == KEY_BYTES) { "Expected $KEY_BYTES-byte AES-256 key" }
            require(chunkSize in 1024..(4 * 1024 * 1024)) { "Invalid chunkSize: $chunkSize" }

            val baseIv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
            val keySpec = SecretKeySpec(keyBytes, "AES")
            dos.write(baseIv)
            dos.writeInt(chunkSize)

            val plainBuf = ByteArray(chunkSize)
            var chunkIdx = 0L
            var totalRead = 0L
            while (true) {
                val read = readFullyUpTo(input, plainBuf)
                if (read <= 0) break
                writeChunk(dos, keySpec, baseIv, chunkIdx, FLAG_DATA, plainBuf, read)
                chunkIdx++
                totalRead += read
                onBytesProcessed?.invoke(totalRead)
            }
            // Final empty sentinel chunk authenticates exact stream end against truncation.
            writeChunk(dos, keySpec, baseIv, chunkIdx, FLAG_EOF, ByteArray(0), 0)
        }

        private fun readEncryptedChunks(
            keyBytes: ByteArray,
            dis: DataInputStream,
            output: OutputStream,
            onBytesProcessed: ((Long) -> Unit)?,
        ) {
            require(keyBytes.size == KEY_BYTES) { "Expected $KEY_BYTES-byte AES-256 key" }
            val baseIv = ByteArray(IV_BYTES)
            dis.readFully(baseIv)
            val chunkSize = dis.readInt()
            if (chunkSize <= 0 || chunkSize > 8 * 1024 * 1024) {
                throw IllegalArgumentException("Invalid encrypted chunk size: $chunkSize")
            }

            val keySpec = SecretKeySpec(keyBytes, "AES")
            var chunkIdx = 0L
            var totalWritten = 0L
            var sawEof = false
            while (!sawEof) {
                val flag = dis.readByte()
                val cipherLen = dis.readInt()
                if (cipherLen < TAG_BYTES || cipherLen > chunkSize + TAG_BYTES) {
                    throw AEADBadTagException("Corrupted chunk length: $cipherLen")
                }
                val cipherBytes = ByteArray(cipherLen)
                dis.readFully(cipherBytes)

                val nonce = deriveNonce(baseIv, chunkIdx)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(buildAad(chunkIdx, flag))
                val plain = cipher.doFinal(cipherBytes)

                when (flag) {
                    FLAG_DATA -> {
                        if (plain.isNotEmpty()) {
                            output.write(plain)
                            totalWritten += plain.size
                            onBytesProcessed?.invoke(totalWritten)
                        }
                    }
                    FLAG_EOF -> {
                        if (plain.isNotEmpty()) throw AEADBadTagException("Unexpected EOF payload")
                        sawEof = true
                    }
                    else -> throw AEADBadTagException("Unknown chunk flag: $flag")
                }
                chunkIdx++
            }
            output.flush()
        }

        private fun writeChunk(
            dos: DataOutputStream,
            keySpec: SecretKeySpec,
            baseIv: ByteArray,
            chunkIdx: Long,
            flag: Byte,
            plainBuf: ByteArray,
            plainLen: Int,
        ) {
            val nonce = deriveNonce(baseIv, chunkIdx)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(buildAad(chunkIdx, flag))
            val encrypted = cipher.doFinal(plainBuf, 0, plainLen)
            dos.writeByte(flag.toInt())
            dos.writeInt(encrypted.size)
            dos.write(encrypted)
        }

        private fun deriveNonce(baseIv: ByteArray, chunkIdx: Long): ByteArray {
            val nonce = baseIv.copyOf()
            for (i in 0 until 8) {
                val shift = (7 - i) * 8
                nonce[4 + i] = (nonce[4 + i].toInt() xor ((chunkIdx ushr shift) and 0xFFL).toInt()).toByte()
            }
            return nonce
        }

        private fun buildAad(chunkIdx: Long, flag: Byte): ByteArray =
            ByteBuffer.allocate(9).putLong(chunkIdx).put(flag).array()

        private fun readFullyUpTo(input: InputStream, buf: ByteArray): Int {
            var offset = 0
            while (offset < buf.size) {
                val n = input.read(buf, offset, buf.size - offset)
                if (n < 0) break
                offset += n
            }
            return offset
        }
    }
}
