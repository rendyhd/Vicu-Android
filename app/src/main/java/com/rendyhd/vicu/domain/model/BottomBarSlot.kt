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
) {
    companion object {
        val DEFAULT_SLOTS = listOf(
            BottomBarSlot(BottomBarSlotType.TODAY),
            BottomBarSlot(BottomBarSlotType.UPCOMING),
            BottomBarSlot(BottomBarSlotType.ANYTIME),
        )
    }
}
