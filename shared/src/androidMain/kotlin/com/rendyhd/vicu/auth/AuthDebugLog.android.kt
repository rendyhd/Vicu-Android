package com.rendyhd.vicu.auth

import android.content.Context
import android.util.Log
import com.rendyhd.vicu.util.BuildInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * On-device diagnostics for the auth flow (token refresh, state changes, lifecycle).
 *
 * Debug builds only. Release builds log nothing, write no file, and delete the file left behind
 * by older releases. Callers never block: [log] formats a line and queues it, and a single
 * writer coroutine on [Dispatchers.IO] appends it to `auth_debug.log`. Call [init] after
 * [BuildInfo.configure] (the application does both in `onCreate`).
 */
actual object AuthDebugLog {

    private const val TAG = "AuthDebugLog"
    private const val FILE_NAME = "auth_debug.log"
    private const val QUEUE_CAPACITY = 512

    private sealed interface Op {
        data class Append(val line: String) : Op
        data object Clear : Op
    }

    // Immutable and thread-safe, unlike SimpleDateFormat, so any thread can format a line.
    private val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var enabled = false

    private var initialized = false

    private val queue = Channel<Op>(capacity = QUEUE_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Only touched by the writer coroutine.
    private var lineCount = -1

    actual fun init(context: Any?) {
        if (context !is Context) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        val file = File(context.filesDir, FILE_NAME)
        if (!BuildInfo.isDebug) {
            ioScope.launch { runCatching { file.delete() } }
            return
        }
        logFile = file
        enabled = true
        ioScope.launch { drainQueue(file) }
    }

    actual fun log(event: String) = record(event)

    actual fun log(event: String, detail: String) = record("$event | $detail")

    actual fun logError(event: String, error: Throwable) {
        if (!enabled) return
        val line = "${timestamp()} [${Thread.currentThread().name}] ERROR $event | " +
            "${error::class.simpleName}: ${error.message}"
        Log.w(TAG, line, error)
        queue.trySend(Op.Append(line))
    }

    /** Reads the whole file: call it off the main thread. */
    actual fun readLog(): String {
        val file = logFile
            ?: return if (BuildInfo.isDebug) "(logger not initialized)" else "(auth debug log is disabled in release builds)"
        return try {
            if (file.exists()) file.readText() else "(no log entries yet)"
        } catch (e: Exception) {
            "(error reading log: ${e.message})"
        }
    }

    actual fun clear() {
        if (!enabled) return
        queue.trySend(Op.Clear)
    }

    actual fun tokenState(jwt: Boolean, jwtExpired: Boolean, apiToken: Boolean, refreshToken: Boolean, isV2: Boolean) {
        log("TOKEN_STATE", "jwt=$jwt jwtExpired=$jwtExpired apiToken=$apiToken refreshToken=$refreshToken isV2=$isV2")
    }

    actual fun authStateChanged(old: String, new: String, reason: String) {
        log("AUTH_STATE_CHANGE", "$old → $new ($reason)")
    }

    actual fun jwtExpiry(expiryEpoch: Long) {
        if (!enabled) return
        val now = System.currentTimeMillis() / 1000
        val remaining = expiryEpoch - now
        val expiryTime = LocalDateTime
            .ofInstant(Instant.ofEpochSecond(expiryEpoch), ZoneId.systemDefault())
            .format(formatter)
        log("JWT_EXPIRY", "expires=$expiryTime remaining=${remaining}s (${remaining / 60}min)")
    }

    actual fun refreshAttempt(method: String) {
        log("REFRESH_ATTEMPT", method)
    }

    actual fun refreshResult(method: String, success: Boolean, detail: String) {
        log("REFRESH_RESULT", "$method success=$success $detail".trim())
    }

    actual fun lifecycle(event: String) {
        log("LIFECYCLE", event)
    }

    actual fun interceptor(event: String, path: String) {
        log("INTERCEPTOR", "$event path=$path")
    }

    private fun record(message: String) {
        if (!enabled) return
        val line = "${timestamp()} [${Thread.currentThread().name}] $message"
        Log.d(TAG, line)
        queue.trySend(Op.Append(line))
    }

    private fun timestamp(): String = LocalDateTime.now().format(formatter)

    private suspend fun drainQueue(file: File) {
        for (op in queue) {
            try {
                when (op) {
                    is Op.Append -> append(file, op.line)
                    Op.Clear -> {
                        file.writeText("")
                        lineCount = 0
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write auth debug log", e)
            }
        }
    }

    private fun append(file: File, line: String) {
        if (lineCount < 0) {
            lineCount = if (file.exists()) file.useLines { lines -> lines.count() } else 0
        }
        file.appendText(line + "\n")
        lineCount++
        // Rewrite the file only once it has outgrown the limit by the slack, not on every line.
        if (AuthDebugLogRetention.shouldTrim(lineCount)) {
            val kept = AuthDebugLogRetention.trim(file.readLines())
            file.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
            lineCount = kept.size
        }
    }
}
