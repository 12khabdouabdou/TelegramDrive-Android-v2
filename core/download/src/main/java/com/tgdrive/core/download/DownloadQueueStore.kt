package com.tgdrive.core.download

import android.content.Context
import com.tgdrive.core.common.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * Lightweight durable JSON persistence for [DownloadManager]'s download queue
 * (`filesDir/download_queue.json`). Uses a pure-Kotlin encoder/decoder so queue
 * state survives process death and can be unit-tested cleanly on the JVM.
 */
@Singleton
class DownloadQueueStore private constructor(
    private val file: File,
) {
    @Inject
    constructor(@ApplicationContext ctx: Context) : this(File(ctx.filesDir, FILE_NAME))

    private val lock = ReentrantLock()

    fun load(): List<DownloadItem> = lock.withLock {
        if (!file.isFile || file.length() <= 0L) return@withLock emptyList()
        return@withLock runCatching {
            decode(file.readText(Charsets.UTF_8))
        }.onFailure { t ->
            Log.w("DownloadQueueStore: failed loading queue", t)
        }.getOrDefault(emptyList())
    }

    fun save(items: List<DownloadItem>) = lock.withLock {
        runCatching {
            file.parentFile?.mkdirs()
            val encoded = encode(items)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(encoded, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure { t ->
            Log.w("DownloadQueueStore: failed saving queue", t)
        }
    }

    internal companion object {
        private const val FILE_NAME = "download_queue.json"

        fun createForTest(file: File): DownloadQueueStore = DownloadQueueStore(file)

        internal fun encode(items: List<DownloadItem>): String = buildString {
            append('[')
            items.forEachIndexed { idx, item ->
                if (idx > 0) append(',')
                append('{')
                append("\"id\":").append(item.id)
                append(",\"remoteId\":").append(jsonStr(item.remoteId))
                append(",\"fileName\":").append(jsonStr(item.fileName))
                append(",\"chatId\":").append(item.chatId)
                append(",\"totalBytes\":").append(item.totalBytes)
                append(",\"downloadedBytes\":").append(item.downloadedBytes)
                append(",\"progress\":").append(item.progress.takeIf { it.isFinite() } ?: 0f)
                append(",\"localPath\":").append(item.localPath?.let(::jsonStr) ?: "null")
                append(",\"status\":").append(jsonStr(item.status.name))
                append(",\"errorMessage\":").append(item.errorMessage?.let(::jsonStr) ?: "null")
                append(",\"pinOfflineWhenDone\":").append(item.pinOfflineWhenDone)
                append(",\"autoPausedForWifi\":").append(item.autoPausedForWifi)
                append('}')
            }
            append(']')
        }

        internal fun decode(raw: String): List<DownloadItem> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
            val parser = MiniJsonParser(trimmed)
            val arr = parser.parseArray() ?: return emptyList()
            val out = ArrayList<DownloadItem>(arr.size)
            for (el in arr) {
                val o = el as? Map<*, *> ?: continue
                val id = o.longVal("id") ?: continue
                val remoteId = o.strVal("remoteId") ?: continue
                val fileName = o.strVal("fileName") ?: continue
                val statusName = o.strVal("status") ?: DownloadStatus.QUEUED.name
                val status = runCatching { DownloadStatus.valueOf(statusName) }
                    .getOrDefault(DownloadStatus.QUEUED)
                // Any transfer that was actively running when the OS killed the app resumes in QUEUED.
                val normalizedStatus = if (status == DownloadStatus.DOWNLOADING) {
                    DownloadStatus.QUEUED
                } else {
                    status
                }
                val localPath = o.strVal("localPath")
                out.add(
                    DownloadItem(
                        id = id,
                        remoteId = remoteId,
                        fileName = fileName,
                        chatId = o.longVal("chatId") ?: 0L,
                        totalBytes = o.longVal("totalBytes") ?: 0L,
                        downloadedBytes = o.longVal("downloadedBytes") ?: 0L,
                        progress = o.floatVal("progress") ?: 0f,
                        speedBytesPerSec = 0L,
                        etaSeconds = null,
                        localPath = localPath,
                        status = normalizedStatus,
                        errorMessage = o.strVal("errorMessage"),
                        pinOfflineWhenDone = o.boolVal("pinOfflineWhenDone") ?: false,
                        autoPausedForWifi = o.boolVal("autoPausedForWifi") ?: false,
                    ),
                )
            }
            return out
        }

        private fun jsonStr(s: String): String = buildString(s.length + 4) {
            append('"')
            for (ch in s) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) {
                        append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
            append('"')
        }

        private fun Map<*, *>.strVal(k: String): String? = this[k] as? String
        private fun Map<*, *>.longVal(k: String): Long? = (this[k] as? Number)?.toLong()
        private fun Map<*, *>.floatVal(k: String): Float? = (this[k] as? Number)?.toFloat()
        private fun Map<*, *>.boolVal(k: String): Boolean? = this[k] as? Boolean

        private class MiniJsonParser(private val src: String) {
            private var pos = 0

            fun parseArray(): List<Any?>? {
                skipWs()
                if (pos >= src.length || src[pos] != '[') return null
                pos++
                val list = ArrayList<Any?>()
                skipWs()
                if (pos < src.length && src[pos] == ']') {
                    pos++
                    return list
                }
                while (pos < src.length) {
                    val v = parseValue() ?: return null
                    list.add(v)
                    skipWs()
                    if (pos < src.length && src[pos] == ',') {
                        pos++
                        continue
                    }
                    if (pos < src.length && src[pos] == ']') {
                        pos++
                        return list
                    }
                    return null
                }
                return null
            }

            private fun parseObject(): Map<String, Any?>? {
                skipWs()
                if (pos >= src.length || src[pos] != '{') return null
                pos++
                val map = LinkedHashMap<String, Any?>()
                skipWs()
                if (pos < src.length && src[pos] == '}') {
                    pos++
                    return map
                }
                while (pos < src.length) {
                    skipWs()
                    val k = parseString() ?: return null
                    skipWs()
                    if (pos >= src.length || src[pos] != ':') return null
                    pos++
                    val v = parseValue()
                    map[k] = v
                    skipWs()
                    if (pos < src.length && src[pos] == ',') {
                        pos++
                        continue
                    }
                    if (pos < src.length && src[pos] == '}') {
                        pos++
                        return map
                    }
                    return null
                }
                return null
            }

            private fun parseValue(): Any? {
                skipWs()
                if (pos >= src.length) return null
                return when (src[pos]) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    't', 'f' -> parseBool()
                    'n' -> {
                        if (src.startsWith("null", pos)) {
                            pos += 4
                            null
                        } else null
                    }
                    else -> parseNumber()
                }
            }

            private fun parseString(): String? {
                if (pos >= src.length || src[pos] != '"') return null
                pos++
                val sb = StringBuilder()
                while (pos < src.length) {
                    val c = src[pos++]
                    if (c == '"') return sb.toString()
                    if (c == '\\' && pos < src.length) {
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 <= src.length) {
                                    val code = src.substring(pos, pos + 4).toIntOrNull(16) ?: return null
                                    sb.append(code.toChar())
                                    pos += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(c)
                    }
                }
                return null
            }

            private fun parseBool(): Boolean? = when {
                src.startsWith("true", pos) -> { pos += 4; true }
                src.startsWith("false", pos) -> { pos += 5; false }
                else -> null
            }

            private fun parseNumber(): Number? {
                val start = pos
                while (pos < src.length && (src[pos].isDigit() || src[pos] in "-+.eE")) pos++
                val tok = src.substring(start, pos)
                return tok.toLongOrNull() ?: tok.toDoubleOrNull()
            }

            private fun skipWs() {
                while (pos < src.length && src[pos].isWhitespace()) pos++
            }
        }
    }
}
