package com.rendyhd.vicu.util

import android.util.Log

actual object Logger {
    // Debug and info lines are diagnostics for developers only. They are dropped in release
    // builds (R8 also strips the calls, see app/proguard-rules.pro). Warnings and errors stay.
    actual fun d(tag: String, msg: String) {
        if (BuildInfo.isDebug) Log.d(tag, msg)
    }

    actual fun i(tag: String, msg: String) {
        if (BuildInfo.isDebug) Log.i(tag, msg)
    }

    actual fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }

    actual fun e(tag: String, msg: String, tr: Throwable?) {
        if (tr != null) {
            Log.e(tag, msg, tr)
        } else {
            Log.e(tag, msg)
        }
    }
}
