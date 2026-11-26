package com.tgdrive.core.security

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CancellationException
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
 * Uses the portable passphrase-based container format:
 * - `"TGE2"`: passphrase-derived key via PBKDF2WithHmacSHA256 (cross-device portable)
 *
 * [EncryptedFileFormat.VAULT_TGE1] is retained strictly for read-only header detection of
 * legacy files. Every chunk and a final EOF sentinel chunk are authenticated so truncation or
 * reordering fails with [AEADBadTagException].
 */
@Singleton
class BackupCrypto @Inject constructor(
    private val keystoreHelper: KeystoreHelper,
) {
    private var cachedThumbPassSha: ByteArray? = null
    private var cachedThumbKey: ByteArray? = null

    fun getVaultPassphrase(): String? =
        keystoreHelper.getString(KEY_BACKUP_PASSPHRASE_V1)?.takeIf { it.isNotBlank() }

    fun hasVaultPassphrase(): Boolean = getVaultPassphrase() != null

    fun requireVaultPassphrase(): String =
        getVaultPassphrase()
            ?: throw IllegalStateException(
                "PassVault passphrase is not set. Configure it in Settings → PassVault & Encryption.",
            )

    @Synchronized
    fun setVaultPassphrase(passphrase: String?) {
        val clean = passphrase?.trim()
        clearThumbKeyCacheLocked()
        if (clean.isNullOrBlank()) {
            keystoreHelper.remove(KEY_BACKUP_PASSPHRASE_V1)
        } else {
            keystoreHelper.putString(KEY_BACKUP_PASSPHRASE_V1, clean)
        }
    }

    @Synchronized
    private fun clearThumbKeyCacheLocked() {
        cachedThumbKey?.fill(0)
        cachedThumbKey = null
        cachedThumbPassSha?.fill(0)
        cachedThumbPassSha = null
    }

    /**
     * Deterministically derives (and caches in-memory) the 256-bit thumbnail subkey from the
     * active PassVault passphrase using [THUMB_DOMAIN_SALT], making encrypted cloud thumbnails
     * portable across devices and reinstalls. Returns `null` when no passphrase is saved.
     */
    @Synchronized
    fun deriveThumbnailKey(): ByteArray? {
        val pass = getVaultPassphrase() ?: run {
            clearThumbKeyCacheLocked()
            return null
        }
        val passBytes = pass.toByteArray(Charsets.UTF_8)
        val passSha = try {
            MessageDigest.getInstance("SHA-256").digest(passBytes)
        } finally {
            passBytes.fill(0)
        }
        val existingKey = cachedThumbKey
        val existingSha = cachedThumbPassSha
        if (existingKey != null && existingSha != null && existingSha.contentEquals(passSha)) {
            passSha.fill(0)
            return existingKey.copyOf()
        }
        val passChars = pass.toCharArray()
        val derived = try {
            deriveKeyFromPassphrase(passChars, THUMB_DOMAIN_SALT, PBKDF2_ITERATIONS)
        } finally {
            passChars.fill('\u0000')
        }
        clearThumbKeyCacheLocked()
        cachedThumbPassSha = passSha
        cachedThumbKey = derived
        return derived.copyOf()
    }

    /** Short verification fingerprint (e.g. `A1B2-C3D4`) derived deterministically from the PassVault passphrase. */
    fun passphraseFingerprint(): String? {
        val key = deriveThumbnailKey() ?: return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(key)
            "%02X%02X-%02X%02X".format(digest[0], digest[1], digest[2], digest[3])
        } finally {
            key.fill(0)
        }
    }

    fun encryptThumbnailBytes(plainBytes: ByteArray): ByteArray? {
        if (plainBytes.isEmpty()) return null
        val key = deriveThumbnailKey() ?: return null
        return try {
            val out = ByteArrayOutputStream()
            encryptStream(key, ByteArrayInputStream(plainBytes), out)
            out.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            key.fill(0)
        }
    }

    fun decryptThumbnailBytes(encryptedBytes: ByteArray): ByteArray? {
        if (encryptedBytes.isEmpty()) return null
        val key = deriveThumbnailKey() ?: return null
        return try {
            val out = ByteArrayOutputStream()
            decryptStream(key, ByteArrayInputStream(encryptedBytes), out)
            out.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            key.fill(0)
        }
    }

    /**
     * Encrypts an arbitrary byte payload (such as the gzipped PassVault AI index) using the active
     * cross-device portable PassVault passphrase in `TGE2` format. Returns `null` if no passphrase is saved.
     */
    fun encryptVaultBlob(plainBytes: ByteArray): ByteArray? {
        if (plainBytes.isEmpty()) return null
        val pass = getVaultPassphrase() ?: return null
        val passChars = pass.toCharArray()
        return try {
            val out = ByteArrayOutputStream()
            encryptStreamWithPassphrase(
                passphrase = passChars,
                input = ByteArrayInputStream(plainBytes),
                output = out,
            )
            out.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            passChars.fill('\u0000')
        }
    }

    /**
     * Decrypts a `TGE2` blob previously produced by [encryptVaultBlob] using the saved PassVault passphrase.
     * Returns `null` if no passphrase is saved or the passphrase does not match.
     */
    fun decryptVaultBlob(encryptedBytes: ByteArray): ByteArray? {
        if (encryptedBytes.isEmpty()) return null
        val pass = getVaultPassphrase() ?: return null
        val passChars = pass.toCharArray()
        return try {
            val out = ByteArrayOutputStream()
            decryptStreamWithPassphrase(
                passphrase = passChars,
                input = ByteArrayInputStream(encryptedBytes),
                output = out,
            )
            out.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            passChars.fill('\u0000')
        }
    }

    /** Creates a zero-knowledge `TGE2` verifier blob containing only a known constant sentinel. */
    fun createPassphraseVerifier(passphrase: String): ByteArray {
        val clean = passphrase.trim()
        require(clean.length >= 8) { "Passphrase must be at least 8 characters" }
        val passChars = clean.toCharArray()
        return try {
            val out = ByteArrayOutputStream()
            encryptStreamWithPassphrase(
                passphrase = passChars,
                input = ByteArrayInputStream(VERIFIER_PAYLOAD),
                output = out,
            )
            out.toByteArray()
        } finally {
            passChars.fill('\u0000')
        }
    }

    /** Verifies [candidatePassphrase] against a cloud verifier blob created by [createPassphraseVerifier]. */
    fun verifyPassphraseAgainstVerifier(
        verifierBytes: ByteArray,
        candidatePassphrase: String,
    ): Boolean {
        val clean = candidatePassphrase.trim()
        if (clean.isEmpty() || verifierBytes.size < MAGIC_V2.size) return false
        val passChars = clean.toCharArray()
        return try {
            val out = ByteArrayOutputStream()
            decryptStreamWithPassphrase(
                passphrase = passChars,
                input = ByteArrayInputStream(verifierBytes),
                output = out,
            )
            out.toByteArray().contentEquals(VERIFIER_PAYLOAD)
        } catch (_: Throwable) {
            false
        } finally {
            passChars.fill('\u0000')
        }
    }

    fun encryptLocalQueueSecret(raw: String): String? = runCatching {
        val key = keystoreHelper.localQueueKey()
        try {
            val out = ByteArrayOutputStream()
            encryptStream(
                keyBytes = key,
                input = ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)),
                output = out,
            )
            out.toByteArray().joinToString("") { "%02x".format(it) }
        } finally {
            key.fill(0)
        }
    }.getOrNull()

    fun decryptLocalQueueSecret(hex: String): String? = runCatching {
        if (hex.length % 2 != 0 || hex.isEmpty()) return@runCatching null
        val bytes = ByteArray(hex.length / 2) { idx ->
            hex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
        val key = keystoreHelper.localQueueKey()
        try {
            val out = ByteArrayOutputStream()
            decryptStream(
                keyBytes = key,
                input = ByteArrayInputStream(bytes),
                output = out,
            )
            out.toByteArray().toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
        } finally {
            key.fill(0)
        }
    }.getOrNull()

    fun activeBackupZipPassword(): String = requireVaultPassphrase()

    fun encryptFileWithBackupSettings(
        inputFile: File,
        outputFile: File,
        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        encryptFile(
            inputFile = inputFile,
            outputFile = outputFile,
            passphrase = requireVaultPassphrase(),
            onProgress = onProgress,
        )
    }

    fun encryptInputStreamWithBackupSettings(
        input: InputStream,
        outputFile: File,
        totalBytes: Long = -1L,
        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
        cancelCheck: (() -> Boolean)? = null,
    ) {
        encryptInputStream(
            input = input,
            outputFile = outputFile,
            passphrase = requireVaultPassphrase(),
            totalBytes = totalBytes,
            onProgress = onProgress,
            cancelCheck = cancelCheck,
        )
    }

    fun encryptInputStream(
        input: InputStream,
        outputFile: File,
        passphrase: String? = null,
        totalBytes: Long = -1L,
        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
        cancelCheck: (() -> Boolean)? = null,
    ) {
        val resolvedPass = passphrase?.takeIf { it.isNotBlank() } ?: requireVaultPassphrase()
        val total = totalBytes.coerceAtLeast(0L)
        outputFile.parentFile?.mkdirs()
        val progressCb = onProgress?.let { cb -> { bytes: Long -> cb(bytes, total) } }
        try {
            val bufferedInput = if (input is BufferedInputStream) {
                input
            } else {
                BufferedInputStream(input, DEFAULT_CHUNK_SIZE)
            }
            bufferedInput.use { src ->
                BufferedOutputStream(FileOutputStream(outputFile), DEFAULT_CHUNK_SIZE).use { output ->
                    val passChars = resolvedPass.toCharArray()
                    try {
                        encryptStreamWithPassphrase(
                            passphrase = passChars,
                            input = src,
                            output = output,
                            onBytesProcessed = progressCb,
                            cancelCheck = cancelCheck,
                        )
                    } finally {
                        passChars.fill('\u0000')
                    }
                }
            }
        } catch (t: Throwable) {
            runCatching { outputFile.delete() }
            throw t
        }
    }

    fun encryptFile(
        inputFile: File,
        outputFile: File,
        passphrase: String? = null,
        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
        cancelCheck: (() -> Boolean)? = null,
    ) {
        encryptInputStream(
            input = FileInputStream(inputFile),
            outputFile = outputFile,
            passphrase = passphrase,
            totalBytes = inputFile.length().coerceAtLeast(0L),
            onProgress = onProgress,
            cancelCheck = cancelCheck,
        )
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
        cancelCheck: (() -> Boolean)? = null,
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
                            throw WrongPassphraseException(
                                "This file uses the removed legacy Device Vault Key (TGE1) format and cannot be decrypted with a passphrase",
                            )
                        }
                        EncryptedFileFormat.PASSPHRASE_TGE2 -> {
                            val resolved = passphrase?.takeIf { it.isNotBlank() } ?: getVaultPassphrase()
                                ?: throw WrongPassphraseException("Enter your PassVault passphrase to decrypt this file")
                            val passChars = resolved.toCharArray()
                            try {
                                decryptStreamWithPassphrase(
                                    passphrase = passChars,
                                    input = input,
                                    output = output,
                                    onBytesProcessed = progressCb,
                                    cancelCheck = cancelCheck,
                                )
                            } finally {
                                passChars.fill('\u0000')
                            }
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
        private const val KEY_BACKUP_PASSPHRASE_V1 = "tgdrive_backup_custom_passphrase_v1"
        private val THUMB_DOMAIN_SALT = "tgdrive-thumb-v1".toByteArray(Charsets.UTF_8)
        private val VERIFIER_PAYLOAD = "TGDRIVE_PASSVAULT_VERIFIER_V1".toByteArray(Charsets.UTF_8)
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val SALT_BYTES = 16
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
        const val PBKDF2_ITERATIONS = 310_000
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
            cancelCheck: (() -> Boolean)? = null,
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
                    cancelCheck = cancelCheck,
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
            cancelCheck: (() -> Boolean)? = null,
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
                    cancelCheck = cancelCheck,
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
            cancelCheck: (() -> Boolean)? = null,
        ) {
            val dos = DataOutputStream(output)
            dos.write(MAGIC)
            writeEncryptedChunks(
                keyBytes = keyBytes,
                input = input,
                dos = dos,
                chunkSize = chunkSize,
                onBytesProcessed = onBytesProcessed,
                cancelCheck = cancelCheck,
            )
            dos.flush()
        }

        fun decryptStream(
            keyBytes: ByteArray,
            input: InputStream,
            output: OutputStream,
            onBytesProcessed: ((Long) -> Unit)? = null,
            cancelCheck: (() -> Boolean)? = null,
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
                cancelCheck = cancelCheck,
            )
        }

        private fun writeEncryptedChunks(
            keyBytes: ByteArray,
            input: InputStream,
            dos: DataOutputStream,
            chunkSize: Int,
            onBytesProcessed: ((Long) -> Unit)?,
            cancelCheck: (() -> Boolean)? = null,
        ) {
            require(keyBytes.size == KEY_BYTES) { "Expected $KEY_BYTES-byte AES-256 key" }
            require(chunkSize in 1024..(4 * 1024 * 1024)) { "Invalid chunkSize: $chunkSize" }

            val baseIv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            dos.write(baseIv)
            dos.writeInt(chunkSize)

            val plainBuf = ByteArray(chunkSize)
            var chunkIdx = 0L
            var totalRead = 0L
            try {
                while (true) {
                    if (cancelCheck?.invoke() == true) throw CancellationException("Encryption cancelled")
                    val read = readFullyUpTo(input, plainBuf)
                    if (read <= 0) break
                    writeChunk(cipher, dos, keySpec, baseIv, chunkIdx, FLAG_DATA, plainBuf, read)
                    chunkIdx++
                    totalRead += read
                    onBytesProcessed?.invoke(totalRead)
                }
                // Final empty sentinel chunk authenticates exact stream end against truncation.
                writeChunk(cipher, dos, keySpec, baseIv, chunkIdx, FLAG_EOF, ByteArray(0), 0)
            } finally {
                plainBuf.fill(0)
            }
        }

        private fun readEncryptedChunks(
            keyBytes: ByteArray,
            dis: DataInputStream,
            output: OutputStream,
            onBytesProcessed: ((Long) -> Unit)?,
            cancelCheck: (() -> Boolean)? = null,
        ) {
            require(keyBytes.size == KEY_BYTES) { "Expected $KEY_BYTES-byte AES-256 key" }
            val baseIv = ByteArray(IV_BYTES)
            dis.readFully(baseIv)
            val chunkSize = dis.readInt()
            if (chunkSize <= 0 || chunkSize > 8 * 1024 * 1024) {
                throw IllegalArgumentException("Invalid encrypted chunk size: $chunkSize")
            }

            val keySpec = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            var chunkIdx = 0L
            var totalWritten = 0L
            var sawEof = false
            while (!sawEof) {
                if (cancelCheck?.invoke() == true) throw CancellationException("Decryption cancelled")
                val flag = dis.readByte()
                val cipherLen = dis.readInt()
                if (cipherLen < TAG_BYTES || cipherLen > chunkSize + TAG_BYTES) {
                    throw AEADBadTagException("Corrupted chunk length: $cipherLen")
                }
                val cipherBytes = ByteArray(cipherLen)
                dis.readFully(cipherBytes)

                val nonce = deriveNonce(baseIv, chunkIdx)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(buildAad(chunkIdx, flag))
                val plain = cipher.doFinal(cipherBytes)
                try {
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
                } finally {
                    plain.fill(0)
                }
                chunkIdx++
            }
            if (dis.read() != -1) {
                throw AEADBadTagException("Unexpected trailing data after EOF")
            }
            output.flush()
        }

        private fun writeChunk(
            cipher: Cipher,
            dos: DataOutputStream,
            keySpec: SecretKeySpec,
            baseIv: ByteArray,
            chunkIdx: Long,
            flag: Byte,
            plainBuf: ByteArray,
            plainLen: Int,
        ) {
            val nonce = deriveNonce(baseIv, chunkIdx)
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
