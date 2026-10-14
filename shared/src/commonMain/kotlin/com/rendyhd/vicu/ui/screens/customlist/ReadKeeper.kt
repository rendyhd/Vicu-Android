package com.rendyhd.vicu.ui.screens.customlist

import com.rendyhd.vicu.domain.model.TaskSignal
import com.rendyhd.vicu.util.CustomListContext

/**
 * Keeps the rows of an "unread comments" list in place while you read them (cross-app semantics
 * 3.3): a task that was unread while the list was open still counts as unread for that list until
 * you leave it, so opening its thread does not pull the row away. One per open list.
 */
internal class ReadKeeper {
    private val seenUnread = mutableSetOf<Long>()

    /** [context] as the list with the [comments] condition should read it. */
    fun contextFor(context: CustomListContext, comments: String?): CustomListContext {
        if (comments != "unread") return context
        context.signals.forEach { (id, signal) -> if (signal.isUnread) seenUnread += id }
        val kept = seenUnread.filter { context.signals[it]?.isUnread != true }
        if (kept.isEmpty()) return context
        return context.copy(
            signals = context.signals + kept.associateWith { (context.signals[it] ?: TaskSignal()).copy(isUnread = true) },
        )
    }
}
