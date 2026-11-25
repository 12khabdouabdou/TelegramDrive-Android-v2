package com.tgdrive.feature.autobackup

/**
 * Per-file failure entry persisted as compact JSON on `sync_log.failed_details_json`
 * so Sync Log Details can display exact failing files/reasons and trigger Retry Failed.
 */
data class BackupFailureRecord(
    val fileName: String,
    val localPath: String,
    val reason: String,
    val folderUri: String = "",
) {
    companion object {
        fun encode(records: List<BackupFailureRecord>, maxCount: Int = 100): String? {
            if (records.isEmpty()) return null
            val clipped = records.take(maxCount)
            return buildString {
                append('[')
                clipped.forEachIndexed { idx, rec ->
                    if (idx > 0) append(',')
                    append("{\"fileName\":")
                    appendJsonString(rec.fileName)
                    append(",\"localPath\":")
                    appendJsonString(rec.localPath)
                    append(",\"reason\":")
                    appendJsonString(rec.reason)
                    append(",\"folderUri\":")
                    appendJsonString(rec.folderUri)
                    append('}')
                }
                append(']')
            }
        }

        fun decode(json: String?): List<BackupFailureRecord> {
            val raw = json?.trim() ?: return emptyList()
            if (raw.length < 2 || !raw.startsWith('[') || !raw.endsWith(']')) return emptyList()
            val out = ArrayList<BackupFailureRecord>()
            var i = 1
            while (i < raw.length - 1) {
                while (i < raw.length - 1 && raw[i] != '{') i++
                if (i >= raw.length - 1) break
                i++ // skip '{'
                var fileName = ""
                var localPath = ""
                var reason = ""
                var folderUri = ""
                while (i < raw.length && raw[i] != '}') {
                    while (i < raw.length && (raw[i] == ',' || raw[i].isWhitespace())) i++
                    if (i >= raw.length || raw[i] == '}') break
                    if (raw[i] != '"') {
                        i++
                        continue
                    }
                    val key = parseJsonString(raw, i) ?: break
                    i = key.nextIndex
                    while (i < raw.length && (raw[i] == ':' || raw[i].isWhitespace())) i++
                    if (i < raw.length && raw[i] == '"') {
                        val value = parseJsonString(raw, i) ?: break
                        i = value.nextIndex
                        when (key.text) {
                            "fileName" -> fileName = value.text
                            "localPath" -> localPath = value.text
                            "reason" -> reason = value.text
                            "folderUri" -> folderUri = value.text
                        }
                    } else {
                        while (i < raw.length && raw[i] != ',' && raw[i] != '}') i++
                    }
                }
                if (i < raw.length && raw[i] == '}') i++
                if (fileName.isNotEmpty() || localPath.isNotEmpty()) {
                    out += BackupFailureRecord(
                        fileName = fileName,
                        localPath = localPath,
                        reason = reason,
                        folderUri = folderUri,
                    )
                }
            }
            return out
        }

        fun retryablePaths(records: List<BackupFailureRecord>): List<String> = records
            .asSequence()
            .filter { it.localPath.isNotBlank() && it.localPath != it.folderUri }
            .map { it.localPath }
            .distinct()
            .toList()

        private data class ParsedString(val text: String, val nextIndex: Int)

        private fun parseJsonString(src: String, quoteStart: Int): ParsedString? {
            if (quoteStart >= src.length || src[quoteStart] != '"') return null
            val sb = StringBuilder()
            var i = quoteStart + 1
            while (i < src.length) {
                val c = src[i++]
                when {
                    c == '"' -> return ParsedString(sb.toString(), i)
                    c == '\\' && i < src.length -> {
                        when (val esc = src[i++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 <= src.length) {
                                    val code = src.substring(i, i + 4).toIntOrNull(16)
                                    if (code != null) {
                                        sb.append(code.toChar())
                                        i += 4
                                    }
                                }
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return null
        }

        private fun StringBuilder.appendJsonString(value: String) {
            append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> if (ch.code < 0x20) {
                        append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
            append('"')
        }
    }
}
