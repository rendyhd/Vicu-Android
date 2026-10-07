package com.rendyhd.vicu.worker

import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.created
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.emptyPage
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncEngineConcurrencyTest {

    private val createdTask = """{"id":501,"title":"Buy milk","project_id":7}"""

    private fun harness(createDelayMs: Long = 0, gate: CompletableDeferred<Unit>? = null) =
        SyncEngineHarness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/projects/7/tasks" -> {
                    gate?.await()
                    if (createDelayMs > 0) delay(createDelayMs)
                    created(createdTask)
                }
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected request ${request.method.value} ${request.url.encodedPath}")
            }
        }

    @Test
    fun `two concurrent syncs create a queued task once`() = runTest {
        val h = harness(createDelayMs = 200)
        h.pendingActionDao.insert(queuedCreate(tempId = -1, title = "Buy milk"))

        withContext(Dispatchers.Default) {
            val first = async { h.engine.performSync() }
            // Start the second run while the first is in the middle of the create request.
            awaitUntil { h.count(HttpMethod.Post, "/projects/7/tasks") >= 1 }
            val second = async { h.newEngine().performSync() }
            awaitAll(first, second)
        }

        assertEquals(1, h.count(HttpMethod.Post, "/projects/7/tasks"))
        assertTrue(h.pendingActionDao.snapshot().isEmpty(), "the action finished and was cleaned up")
        h.close()
    }

    @Test
    fun `a second sync never resets an action another run has in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = harness(gate = gate)
        h.pendingActionDao.insert(queuedCreate(tempId = -1, title = "Buy milk"))

        withContext(Dispatchers.Default) {
            val first = async { h.engine.performSync() }
            awaitUntil { h.count(HttpMethod.Post, "/projects/7/tasks") >= 1 }
            assertEquals("processing", h.pendingActionDao.snapshot().single().status)

            val second = async { h.newEngine().performSync() }
            // The second run must be waiting on the lock, not resetting the row to pending.
            delay(150)
            assertEquals("processing", h.pendingActionDao.snapshot().single().status)

            gate.complete(Unit)
            awaitAll(first, second)
        }

        assertEquals(1, h.count(HttpMethod.Post, "/projects/7/tasks"))
        h.close()
    }

    @Test
    fun `a cancelled sync leaves its action pending for the next run`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = harness(gate = gate)
        h.pendingActionDao.insert(queuedCreate(tempId = -1, title = "Buy milk"))

        withContext(Dispatchers.Default) {
            val first = async { h.engine.performSync() }
            awaitUntil { h.count(HttpMethod.Post, "/projects/7/tasks") >= 1 }
            first.cancelAndJoin()
        }

        val action = h.pendingActionDao.snapshot().single()
        assertEquals("pending", action.status)
        assertEquals(0, action.retryCount)
        h.close()
    }
}
