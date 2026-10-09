package com.rendyhd.vicu.permission

/** What made the app think about asking for the notification permission. */
enum class NotificationPermissionTrigger {
    /** The user just finished setting the app up. Asked about once, never again on its own. */
    AFTER_SETUP,

    /** The user turned on something that needs notifications: a reminder or a daily summary. */
    FEATURE_ENABLED,
}

/** What the screen should do next. */
enum class NotificationPermissionStep {
    /** Nothing: the permission is not needed, already given, or it is not the time to ask. */
    NONE,

    /** Explain in one sentence why, then let the user go on to the system prompt or not. */
    SHOW_RATIONALE,

    /** The system will not ask again; the only way left is the system settings page. */
    OPEN_SYSTEM_SETTINGS,
}

/** The facts the policy decides from. */
data class NotificationPermissionState(
    /** False where there is no runtime permission (before Android 13). */
    val needsRuntimePermission: Boolean,
    val granted: Boolean,
    /** How many times the system prompt has been launched from this app. */
    val requestsMade: Int,
    /** Whether the one-time prompt after setup was already shown. */
    val afterSetupHandled: Boolean,
)

/**
 * When to ask for the notification permission. Pure, so the rules are tested without a device.
 *
 * Android shows the system prompt at most twice; after that a request does nothing. The app
 * counts its own requests and stops launching the prompt at [MAX_SYSTEM_REQUESTS].
 */
object NotificationPermissionPolicy {

    const val MAX_SYSTEM_REQUESTS = 2

    fun requestsLeft(state: NotificationPermissionState): Int =
        (MAX_SYSTEM_REQUESTS - state.requestsMade).coerceAtLeast(0)

    fun next(
        state: NotificationPermissionState,
        trigger: NotificationPermissionTrigger,
    ): NotificationPermissionStep {
        if (!state.needsRuntimePermission || state.granted) return NotificationPermissionStep.NONE
        return when (trigger) {
            NotificationPermissionTrigger.AFTER_SETUP ->
                if (state.afterSetupHandled || requestsLeft(state) == 0) {
                    NotificationPermissionStep.NONE
                } else {
                    NotificationPermissionStep.SHOW_RATIONALE
                }
            NotificationPermissionTrigger.FEATURE_ENABLED ->
                if (requestsLeft(state) == 0) {
                    NotificationPermissionStep.OPEN_SYSTEM_SETTINGS
                } else {
                    NotificationPermissionStep.SHOW_RATIONALE
                }
        }
    }

    /**
     * What the "Allow" button in Settings does, where the user asked for it themselves: the system
     * prompt while requests are left (reported as [NotificationPermissionStep.SHOW_RATIONALE], but
     * without the sheet), else the settings page.
     */
    fun nextFromSettings(state: NotificationPermissionState): NotificationPermissionStep =
        if (state.needsRuntimePermission && !state.granted && requestsLeft(state) > 0) {
            NotificationPermissionStep.SHOW_RATIONALE
        } else {
            NotificationPermissionStep.OPEN_SYSTEM_SETTINGS
        }
}
