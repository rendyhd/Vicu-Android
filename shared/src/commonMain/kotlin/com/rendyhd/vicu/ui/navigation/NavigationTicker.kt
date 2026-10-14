package com.rendyhd.vicu.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Counts the times the app moved from one destination to another, so state that belongs to one
 * screen (a multi-selection) can end when the user leaves it. A screen's view model outlives its
 * composition (a rotation, a saved tab), so the composition leaving is not the signal; this is.
 */
class NavigationTicker {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    fun navigated() {
        _count.update { it + 1 }
    }
}
