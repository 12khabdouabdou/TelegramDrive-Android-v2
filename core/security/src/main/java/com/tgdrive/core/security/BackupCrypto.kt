package com.tgdrive.core.security

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streaming chunked AES-256-GCM helper used by AutoBackup's per-folder encryption
 * option. Uses constant O(1) memory even for multi-GB backups and authenticates
 * every chunk plus a final EOF sentinel chunk so truncation or reordering fails
 * with [AEADBadTagException].
 */
@Singleton
class BackupCrypto @Inject constructor(
    private val keystoreHelper: KeystoreHelper,
) {

    @Synchronized
    fun vaultKey(): ByteArray {
        val stored = keystoreHelper.getString(KEY_BACKUP_VAULT_V1)
        if (!stored.isNullOrBlank()) {
            val decoded = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull()
            if (decoded != null && decoded.size == KEY_BYTES) {
                return decoded
            }
        }
        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        keystoreHelper.putString(KEY_BACKUP_VAULT_V1, Base64.encodeToString(key, Base64.NO_WRAP))
        return key
    }

    /** Password string derived from [vaultKey] for AES-256 encrypted ZIP archives. */
    fun zipVaultPassword(): String = Base64.encodeToString(vaultKey(), Base64.NO_WRAP)

    fun encryptFile(inputFile: File, outputFile: File) {
        val key = vaultKey()
        BufferedInputStream(FileInputStream(inputFile), DEFAULT_CHUNK_SIZE).use { input ->
            BufferedOutputStream(FileOutputStream(outputFile), DEFAULT_CHUNK_SIZE).use { output ->
                encryptStream(key, input, output)
            }
        }
    }

    fun decryptFile(inputFile: File, outputFile: File) {
        val key = vaultKey()
        BufferedInputStream(FileInputStream(inputFile), DEFAULT_CHUNK_SIZE).use { input ->
            BufferedOutputStream(FileOutputStream(outputFile), DEFAULT_CHUNK_SIZE).use { output ->
                decryptStream(key, input, output)
            }
        }
    }

    companion object {
        private const val KEY_BACKUP_VAULT_V1 = "tgdrive_backup_vault_key_v1"
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
        private val MAGIC = byteArrayOf('T'.code.toByte(), 'G'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
        private const val FLAG_DATA: Byte = 0
        private const val FLAG_EOF: Byte = 1

        fun encryptStream(
            keyBytes: ByteArray,
            input: InputStream,
            output: OutputStream,
            chunkSize: Int = DEFAULT_CHUNK_SIZE,
        ) {
            require(keyBytes.size == KEY_BYTES) { "Expected $KEY_BYTES-byte AES-256 key" }
            require(chunkSize in 1024..(4 * 1024 * 1024)) { "Invalid chunkSize: $chunkSize" }

            val baseIv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val dos = DataOutputStream(output)
            dos.write(MAGIC)
            dos.write(baseIv)
            dos.writeInt(chunkSize)

            val plainBuf = ByteArray(chunkSize)
            var chunkIdx = 0L
            while (true) {
                val read = readFullyUpTo(input, plainBuf)
                if (read <= 0) break
                writeChunk(dos, keySpec, baseIv, chunkIdx, FLAG_DATA, plainBuf, read)
                chunkIdx++
            }
            // Final empty sentinel chunk authenticates exact stream end against truncation.
            writeChunk(dos, keySpec, baseIv, chunkIdx, FLAG_EOF, ByteArray(0), 0)
            dos.flush()
        }

        fun decryptStream(
            keyBytes: ByteArray,
            input: InputStream,
            output: OutputStream,
        ) {
            require(keyBytes.size == KEY_BYTES) { "Expected $KEY_BYTES-byte AES-256 key" }
            val dis = DataInputStream(input)
            val magic = ByteArray(MAGIC.size)
            dis.readFully(magic)
            if (!magic.contentEquals(MAGIC)) {
                throw IllegalArgumentException("Invalid encrypted backup stream header")
            }
            val baseIv = ByteArray(IV_BYTES)
            dis.readFully(baseIv)
            val chunkSize = dis.readInt()
            if (chunkSize <= 0 || chunkSize > 8 * 1024 * 1024) {
                throw IllegalArgumentException("Invalid encrypted chunk size: $chunkSize")
            }

            val keySpec = SecretKeySpec(keyBytes, "AES")
            var chunkIdx = 0L
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
                    FLAG_DATA -> if (plain.isNotEmpty()) output.write(plain)
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
