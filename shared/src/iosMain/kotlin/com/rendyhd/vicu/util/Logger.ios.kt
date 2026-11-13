package com.rendyhd.vicu.util

import platform.Foundation.NSLog

// Debug and info lines only in debug builds, like Android (where R8 also strips them).
actual object Logger {
    actual fun d(tag: String, msg: String) {
        if (BuildInfo.isDebug) log("[$tag] DEBUG: $msg")
    }

    actual fun i(tag: String, msg: String) {
        if (BuildInfo.isDebug) log("[$tag] INFO: $msg")
    }

    actual fun w(tag: String, msg: String) {
        log("[$tag] WARN: ${if (BuildInfo.isDebug) msg else LogScrub.addresses(msg)}")
    }

    actual fun e(tag: String, msg: String, tr: Throwable?) {
        val detail = when {
            tr == null -> ""
            BuildInfo.isDebug -> " - ${tr.message}\n${tr.stackTraceToString()}"
            else -> " - ${tr.message}"
        }
        log("[$tag] ERROR: $msg$detail")
    }

    // The line is the format string itself (a Kotlin string cannot be passed through NSLog's
    // variadic arguments), so its % signs are escaped.
    private fun log(line: String) = NSLog(line.replace("%", "%%"))
}
