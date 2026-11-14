package com.rendyhd.vicu.ui.navigation

import com.rendyhd.vicu.data.local.DrawerView
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.BottomBarSlotType

/** One of the drawer's blocks of rows under a header: the projects, the custom lists, the labels. */
enum class DrawerBlock { PROJECTS, LISTS, LABELS }

/** What the user arranges in the drawer (Settings, General, Drawer): a view or a block. */
sealed interface DrawerEntry {
    /** The name it is stored under in the drawer's order. */
    val key: String

    data class View(val view: DrawerView) : DrawerEntry {
        override val key: String get() = view.name
    }

    data class Block(val block: DrawerBlock) : DrawerEntry {
        override val key: String get() = block.name
    }
}

/** A view the drawer can list now, and whether the bottom bar holds it as well. */
data class DrawerViewOffer(val view: DrawerView, val inBar: Boolean)

/**
 * How the drawer is arranged: which views it lists and in what order, with the projects, lists and
 * labels as blocks among them. A device setting (`BehaviorPrefsStore`).
 *
 * Nothing moves until the user changes it: the default order is the views and then the blocks, and
 * a view the bottom bar holds is not listed until it is turned on.
 */
object DrawerLayout {
    /** The views in the order of [DrawerView], then the projects, the lists and the labels. */
    val DEFAULT: List<DrawerEntry> =
        DrawerView.entries.map { DrawerEntry.View(it) } + DrawerBlock.entries.map { DrawerEntry.Block(it) }

    /**
     * Every entry in the user's order. [stored] is the order as it was saved; an entry it does not
     * name (a view added since) comes right after the entry before it in [DEFAULT], and a key this
     * version does not know is left out.
     */
    fun order(stored: List<String>): List<DrawerEntry> {
        val byKey = DEFAULT.associateBy { it.key }
        val result = stored.mapNotNull { byKey[it] }.distinct().toMutableList()
        DEFAULT.forEachIndexed { index, entry ->
            if (entry in result) return@forEachIndexed
            val before = DEFAULT.subList(0, index).lastOrNull { it in result }
            result.add(if (before == null) 0 else result.indexOf(before) + 1, entry)
        }
        return result
    }

    /**
     * Whether the drawer lists the view of [offer]: one the bottom bar holds only when it was turned
     * on for the drawer too ([barViews]), any other unless it was hidden ([hidden]).
     */
    fun isShown(offer: DrawerViewOffer, hidden: Set<DrawerView>, barViews: Set<DrawerView>): Boolean =
        if (offer.inBar) offer.view in barViews else offer.view !in hidden

    /**
     * What Settings lists, in order: every view of [offers], listed in the drawer or not, and the
     * three blocks.
     */
    fun arrangeable(stored: List<String>, offers: List<DrawerViewOffer>): List<DrawerEntry> {
        val offered = offers.mapTo(HashSet()) { it.view }
        return order(stored).filter { it !is DrawerEntry.View || it.view in offered }
    }

    /** What the drawer draws, in order: the views it lists and the three blocks. */
    fun shown(
        stored: List<String>,
        offers: List<DrawerViewOffer>,
        hidden: Set<DrawerView>,
        barViews: Set<DrawerView>,
    ): List<DrawerEntry> {
        val listed = offers.filter { isShown(it, hidden, barViews) }.mapTo(HashSet()) { it.view }
        return order(stored).filter { it !is DrawerEntry.View || it.view in listed }
    }

    /**
     * The whole order after the entries Settings lists were put in [arrangedInNewOrder]: they trade
     * places among themselves, and an entry that is not listed there now (Plan while the planner is
     * off) stays where it was. Returned as the keys to store.
     */
    fun keysAfterArranging(stored: List<String>, arrangedInNewOrder: List<DrawerEntry>): List<String> {
        val moving = arrangedInNewOrder.toSet()
        val next = arrangedInNewOrder.iterator()
        return order(stored).map { entry -> if (entry in moving && next.hasNext()) next.next().key else entry.key }
    }

    /** [list] with the entry at [from] moved to [to]; unchanged when either is out of range. */
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }
}

/**
 * Every view the drawer can list, in its default order, with whether the bottom bar holds it too:
 * the Inbox (always in the bar), Today, Upcoming and Anytime, Plan while the planner is on, the
 * Logbook, Routines and Review while they are turned on, Ask once it is set up, and Pro's views
 * while their layer is on.
 */
internal fun drawerViewOffers(
    slots: List<BottomBarSlot>,
    routinesEnabled: Boolean,
    reviewEnabled: Boolean,
    askReady: Boolean,
    plannerEnabled: Boolean = false,
    proAvailability: Boolean = false,
    proPromises: Boolean = false,
): List<DrawerViewOffer> {
    val inBar = slots.mapTo(HashSet()) { it.type }
    return buildList {
        add(DrawerViewOffer(DrawerView.INBOX, inBar = true))
        add(DrawerViewOffer(DrawerView.TODAY, BottomBarSlotType.TODAY in inBar))
        if (plannerEnabled) add(DrawerViewOffer(DrawerView.PLAN, BottomBarSlotType.PLAN in inBar))
        add(DrawerViewOffer(DrawerView.UPCOMING, BottomBarSlotType.UPCOMING in inBar))
        add(DrawerViewOffer(DrawerView.ANYTIME, BottomBarSlotType.ANYTIME in inBar))
        add(DrawerViewOffer(DrawerView.LOGBOOK, inBar = false))
        if (routinesEnabled) add(DrawerViewOffer(DrawerView.ROUTINES, inBar = false))
        if (reviewEnabled) add(DrawerViewOffer(DrawerView.REVIEW, inBar = false))
        if (askReady) add(DrawerViewOffer(DrawerView.ASK, inBar = false))
        // Pro's views, between the smart lists and the projects (design system 9).
        if (proAvailability) add(DrawerViewOffer(DrawerView.FLAGGED, inBar = false))
        if (proPromises) {
            add(DrawerViewOffer(DrawerView.WAITING_ON, inBar = false))
            add(DrawerViewOffer(DrawerView.PEOPLE, inBar = false))
        }
    }
}
