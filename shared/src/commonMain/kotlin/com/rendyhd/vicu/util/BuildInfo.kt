package com.rendyhd.vicu.util

import kotlin.concurrent.Volatile

/**
 * Build-flavour flags supplied by the host application at startup.
 *
 * The shared module cannot see the app's `BuildConfig`, so the host calls [configure] once
 * from `Application.onCreate`, before DI starts. Until then the flags describe a release
 * build, so anything that runs early stays quiet rather than leaking diagnostics.
 */
object BuildInfo {
    @Volatile
    var isDebug: Boolean = false
        private set

    fun configure(isDebug: Boolean) {
        this.isDebug = isDebug
    }
}
