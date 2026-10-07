package com.rendyhd.vicu.ui.screens.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server is asked about a search text only after it has rested, one request at a time, and a
 * new text cancels whatever is running for the old one (A-UI-16, UI-23).
 */
class SearchRefreshTest {

    private class Run {
        val started = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        var blanks = 0
    }

    /** Collects [texts] in the test's background with a refresh that takes [takes] ms. */
    private fun kotlinx.coroutines.test.TestScope.collect(
        texts: MutableStateFlow<String>,
        run: Run,
        takes: Long = 0,
    ) {
        backgroundScope.launch {
            texts.collectSearchRefresh(onBlank = { run.blanks++ }) { text ->
                run.started += text
                try {
                    delay(takes)
                    run.completed += text
                } catch (e: CancellationException) {
                    run.cancelled += text
                    throw e
                }
            }
        }
    }

    @Test
    fun `typing quickly asks the server once, about the last text`() = runTest {
        val texts = MutableStateFlow("")
        val run = Run()
        collect(texts, run)

        texts.value = "a"
        advanceTimeBy(100)
        texts.value = "ab"
        advanceTimeBy(100)
        texts.value = "abc"
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS - 1)
        assertEquals(emptyList(), run.started, "still resting")

        advanceTimeBy(2)

        assertEquals(listOf("abc"), run.started)
    }

    @Test
    fun `a text that rests is asked, then the next one`() = runTest {
        val texts = MutableStateFlow("")
        val run = Run()
        collect(texts, run)

        texts.value = "milk"
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)
        texts.value = "milk and"
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)

        assertEquals(listOf("milk", "milk and"), run.completed)
    }

    @Test
    fun `a new text cancels the request that is in flight`() = runTest {
        val texts = MutableStateFlow("")
        val run = Run()
        collect(texts, run, takes = 5_000)

        texts.value = "ab"
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)
        assertEquals(listOf("ab"), run.started)

        texts.value = "abc"
        advanceTimeBy(1)

        assertEquals(listOf("ab"), run.cancelled, "the old request is cancelled at the keystroke, not 250 ms later")
        assertEquals(emptyList(), run.completed)

        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 5_001)
        assertEquals(listOf("abc"), run.completed)
    }

    @Test
    fun `clearing the text cancels the request and asks for nothing`() = runTest {
        val texts = MutableStateFlow("")
        val run = Run()
        collect(texts, run, takes = 5_000)
        texts.value = "ab"
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)

        texts.value = ""
        advanceTimeBy(10_000)

        assertEquals(listOf("ab"), run.cancelled)
        assertEquals(listOf("ab"), run.started)
        assertTrue(run.blanks >= 1)
    }

    @Test
    fun `blank and padded texts are trimmed, and the same text twice asks once`() = runTest {
        val texts = MutableStateFlow("")
        val run = Run()
        collect(texts, run)

        texts.value = "   "
        advanceTimeBy(1_000)
        assertEquals(emptyList(), run.started)

        texts.value = " milk"
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)
        texts.value = "milk "
        advanceTimeBy(1_000)

        assertEquals(listOf("milk"), run.started)
    }
}
