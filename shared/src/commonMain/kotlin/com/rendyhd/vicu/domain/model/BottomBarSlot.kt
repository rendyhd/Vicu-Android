package com.rendyhd.vicu.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class BottomBarSlotType {
    TODAY,
    UPCOMING,
    ANYTIME,
    PROJECT,
    CUSTOM_LIST,
}

@Serializable
data class BottomBarSlot(
    val type: BottomBarSlotType,
    val referenceId: String = "",
    val iconKey: String = "",
    /** The icon colour of a project or custom list slot (one of `PRESET_COLORS`); empty for none. */
    val colorHex: String = "",
) {
    companion object {
        val DEFAULT_SLOTS = listOf(
            BottomBarSlot(BottomBarSlotType.TODAY),
            BottomBarSlot(BottomBarSlotType.UPCOMING),
            BottomBarSlot(BottomBarSlotType.ANYTIME),
        )
    }
}

/** The bottom bar as the user set it up in Settings. */
data class BottomBarConfig(
    val slots: List<BottomBarSlot> = BottomBarSlot.DEFAULT_SLOTS,
    /** Whether the Inbox, Today, Upcoming and Anytime icons are drawn in their identity colours. */
    val smartListColors: Boolean = true,
)
