package com.rendyhd.vicu.util

/**
 * The `filename` part of a multipart `Content-Disposition` header for [fileName].
 *
 * The name goes inside a quoted string, so quotes and backslashes are escaped and control
 * characters (a line break would end the header) are dropped. A name with characters outside
 * ASCII also gets the RFC 5987 `filename*` form, which servers prefer, next to a plain fallback.
 */
fun contentDispositionFileParameter(fileName: String): String {
    val cleaned = fileName
        .filterNot { it.code < 0x20 || it.code == 0x7f }
        .ifBlank { "file" }
    val ascii = cleaned.map { if (it.code in 0x20..0x7e) it else '_' }.joinToString("")
    val fallback = "filename=\"${escapeQuoted(ascii)}\""
    if (cleaned.all { it.code in 0x20..0x7e }) return fallback
    return "$fallback; filename*=UTF-8''${percentEncodeUtf8(cleaned)}"
}

private fun escapeQuoted(text: String): String = buildString {
    text.forEach { c ->
        if (c == '"' || c == '\\') append('\\')
        append(c)
    }
}

private const val ATTR_CHARS = "!#$&+-.^_`|~"

private fun percentEncodeUtf8(text: String): String = buildString {
    for (byte in text.encodeToByteArray()) {
        val code = byte.toInt() and 0xff
        val c = code.toChar()
        if (code < 0x80 && (c.isLetterOrDigit() || c in ATTR_CHARS)) {
            append(c)
        } else {
            append('%')
            append(HEX[code shr 4])
            append(HEX[code and 0x0f])
        }
    }
}

private const val HEX = "0123456789ABCDEF"
