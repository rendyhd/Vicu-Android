package com.rendyhd.vicu.ui.screens.shared

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** How long the search text must rest before the server is asked about it. */
const val SEARCH_REFRESH_DEBOUNCE_MS = 250L

/**
 * Asks the server about what the user types, in the background (A-UI-16).
 *
 * The screen shows what is cached at once; this only brings the cache up to date. For every
 * text that has rested for [debounceMs] it runs [refresh], one at a time. A new text cancels the
 * wait or the request that is running for the old one, so a slow answer to "ab" can never land
 * after the one for "abc" and typing never queues up requests. A blank text cancels too and
 * calls [onBlank] instead. Texts are trimmed, and the same text twice in a row asks once.
 *
 * Collect it in a coroutine of the screen; it never completes.
 */
suspend fun Flow<String>.collectSearchRefresh(
    debounceMs: Long = SEARCH_REFRESH_DEBOUNCE_MS,
    onBlank: suspend () -> Unit = {},
    refresh: suspend (String) -> Unit,
) {
    map { it.trim() }
        .distinctUntilChanged()
        .collectLatest { text ->
            if (text.isEmpty()) {
                onBlank()
                return@collectLatest
            }
            delay(debounceMs)
            refresh(text)
        }
}
