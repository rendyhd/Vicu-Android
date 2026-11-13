package com.rendyhd.vicu.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Which account the local data belongs to, as a number that moves on with every wipe (sign-out,
 * or another account signing in). A refresh notes the number when it starts and writes what it
 * fetched only while the number is the same, so a page that arrives after the wipe can neither
 * bring the old account's rows back nor arm its reminders.
 *
 * The wipe and the guarded writes share one lock: a write that passed the check finishes before
 * the wipe starts, and the wipe then removes it.
 */
class AccountEpoch {
    private val mutex = Mutex()

    @Volatile
    private var value = 0L

    fun current(): Long = value

    /** Starts a new epoch and runs [wipe] before any write of the old one can follow. */
    suspend fun <T> advance(wipe: suspend () -> T): T = mutex.withLock {
        value++
        wipe()
    }

    /** Runs [write] while [epoch] is still the current one; throws [AccountChangedException] otherwise. */
    suspend fun <T> write(epoch: Long, write: suspend () -> T): T = mutex.withLock {
        if (value != epoch) throw AccountChangedException()
        write()
    }
}

/** A refresh finished after a sign-out or an account switch; what it fetched was dropped. */
class AccountChangedException : IllegalStateException("Signed out while refreshing; the result was not kept")
