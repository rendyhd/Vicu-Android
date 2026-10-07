package com.rendyhd.vicu.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The dispatchers view models use for work that must stay off the main thread (building a
 * screen's state out of several flows). Injected so a test can run it in the test's own steps.
 */
class AppDispatchers(
    val default: CoroutineDispatcher = Dispatchers.Default,
)
