package com.rendyhd.vicu.util

private const val MAX_FILE_NAME_LENGTH = 120
private val FORBIDDEN_FILE_NAME_CHARS = "\\/:*?\"<>|".toSet()

/**
 * A name that is safe to create in the app's cache: no folders (so a server-supplied name cannot
 * climb out with `..`), no characters a file system refuses, never empty, and not unreasonably
 * long (the extension is kept when it is cut).
 */
fun safeFileName(name: String): String {
    val base = name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .map { if (it.code < 0x20 || it in FORBIDDEN_FILE_NAME_CHARS) '_' else it }
        .joinToString("")
        .trim()
        .trim('.')
    if (base.isEmpty()) return "attachment"
    if (base.length <= MAX_FILE_NAME_LENGTH) return base
    val dot = base.lastIndexOf('.')
    val extension = if (dot > 0 && base.length - dot <= 12) base.substring(dot) else ""
    return base.take(MAX_FILE_NAME_LENGTH - extension.length) + extension
}
