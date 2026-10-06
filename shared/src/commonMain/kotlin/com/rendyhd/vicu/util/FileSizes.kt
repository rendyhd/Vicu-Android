package com.rendyhd.vicu.util

/** What Vikunja allows by default (`files.maxsize` = 20MB) when the server does not say. */
const val DEFAULT_MAX_UPLOAD_BYTES = 20_000_000L

private val BYTE_SIZE_PATTERN = Regex("""^(\d+(?:\.\d+)?)\s*([A-Za-z]*)$""")

/**
 * Parses a size such as "20MB", "20 MB", "512KB" or "1GiB" the way Vikunja does (it uses
 * go-humanize): KB, MB and GB are powers of 1000, KiB, MiB and GiB powers of 1024, a bare number
 * is bytes. Returns null for anything else, and for zero.
 */
fun parseByteSize(text: String?): Long? {
    val match = BYTE_SIZE_PATTERN.matchEntire(text?.trim().orEmpty()) ?: return null
    val number = match.groupValues[1].toDoubleOrNull() ?: return null
    val unit: Long = when (match.groupValues[2].lowercase()) {
        "", "b" -> 1L
        "k", "kb" -> 1_000L
        "m", "mb" -> 1_000_000L
        "g", "gb" -> 1_000_000_000L
        "t", "tb" -> 1_000_000_000_000L
        "ki", "kib" -> 1L shl 10
        "mi", "mib" -> 1L shl 20
        "gi", "gib" -> 1L shl 30
        "ti", "tib" -> 1L shl 40
        else -> return null
    }
    val bytes = number * unit
    return if (bytes >= 1.0 && bytes < Long.MAX_VALUE.toDouble()) bytes.toLong() else null
}

/** "512 B", "1.5 KB", "20 MB" (powers of 1000, like the server's limit). */
fun formatByteSize(bytes: Long): String {
    if (bytes < 1_000L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1_000.0
    var index = 0
    while (value >= 1_000.0 && index < units.lastIndex) {
        value /= 1_000.0
        index++
    }
    val rounded = (value * 10).toLong() / 10.0
    val text = if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    return "$text ${units[index]}"
}
