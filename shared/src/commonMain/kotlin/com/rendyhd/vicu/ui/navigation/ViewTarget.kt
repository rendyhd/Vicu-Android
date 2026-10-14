package com.rendyhd.vicu.ui.navigation

/**
 * Where a widget or a notification asks the app to go: a closed set instead of a type string and
 * an id that every receiver had to interpret.
 *
 * The wire form ([typeName] and [idOrEmpty], under [EXTRA_TYPE] and [EXTRA_ID]) is what an Intent
 * carries. It is frozen: widgets already on the home screen and notifications already posted hold
 * intents in this form.
 */
sealed interface ViewTarget {
    data object Today : ViewTarget
    data object Inbox : ViewTarget
    data object Upcoming : ViewTarget
    data object Anytime : ViewTarget
    data object Routines : ViewTarget
    data class Project(val projectId: Long) : ViewTarget
    data class CustomList(val listId: String) : ViewTarget

    /** The name written to [EXTRA_TYPE]. */
    val typeName: String
        get() = when (this) {
            Today -> "TODAY"
            Inbox -> "INBOX"
            Upcoming -> "UPCOMING"
            Anytime -> "ANYTIME"
            Routines -> "ROUTINES"
            is Project -> "PROJECT"
            is CustomList -> "CUSTOM_LIST"
        }

    /** The text written to [EXTRA_ID]: the project or list id, empty for the smart lists. */
    val idOrEmpty: String
        get() = when (this) {
            is Project -> projectId.toString()
            is CustomList -> listId
            else -> ""
        }

    companion object {
        const val EXTRA_TYPE = "navigate_to_view_type"
        const val EXTRA_ID = "navigate_to_view_id"

        /** The target [type] and [id] name, or null when they name nothing the app can open. */
        fun parse(type: String?, id: String?): ViewTarget? = when (type) {
            "TODAY" -> Today
            "INBOX" -> Inbox
            "UPCOMING" -> Upcoming
            "ANYTIME" -> Anytime
            "ROUTINES" -> Routines
            "PROJECT" -> id?.toLongOrNull()?.let { Project(it) }
            "CUSTOM_LIST" -> id?.takeIf { it.isNotBlank() }?.let { CustomList(it) }
            else -> null
        }
    }
}

/** The destination that shows this target. */
internal fun ViewTarget.toRoute(): Any = when (this) {
    ViewTarget.Today -> TodayRoute
    ViewTarget.Inbox -> InboxRoute
    ViewTarget.Upcoming -> UpcomingRoute
    ViewTarget.Anytime -> AnytimeRoute
    ViewTarget.Routines -> RoutinesRoute
    is ViewTarget.Project -> ProjectRoute(projectId)
    is ViewTarget.CustomList -> CustomListRoute(listId)
}
