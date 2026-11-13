package com.rendyhd.vicu.util

import android.util.Log

actual object Logger {
    // Debug and info lines are diagnostics for developers only. They are dropped in release
    // builds (R8 also strips the calls, see app/proguard-rules.pro). Warnings and errors stay, but
    // release builds cut every request address in them down to its host (LogScrub).
    actual fun d(tag: String, msg: String) {
        if (BuildInfo.isDebug) Log.d(tag, msg)
    }

    actual fun i(tag: String, msg: String) {
        if (BuildInfo.isDebug) Log.i(tag, msg)
    }

    actual fun w(tag: String, msg: String) {
        Log.w(tag, if (BuildInfo.isDebug) msg else LogScrub.addresses(msg))
    }

    actual fun e(tag: String, msg: String, tr: Throwable?) {
        when {
            tr != null && BuildInfo.isDebug -> Log.e(tag, msg, tr)
            tr != null -> Log.e(tag, LogScrub.addresses(msg) + LogScrub.throwable(tr))
            BuildInfo.isDebug -> Log.e(tag, msg)
            else -> Log.e(tag, LogScrub.addresses(msg))
        }
    }
}
