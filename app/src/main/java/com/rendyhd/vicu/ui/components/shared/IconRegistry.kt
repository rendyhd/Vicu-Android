package com.rendyhd.vicu.ui.components.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.vector.ImageVector
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.BottomBarSlotType

data class IconOption(
    val key: String,
    val icon: ImageVector,
    val label: String,
)

object IconRegistry {

    val PRESET_ICONS: List<IconOption> = listOf(
        IconOption("folder", Icons.Outlined.Folder, "Folder"),
        IconOption("star", Icons.Outlined.Star, "Star"),
        IconOption("favorite", Icons.Outlined.FavoriteBorder, "Heart"),
        IconOption("home", Icons.Outlined.Home, "Home"),
        IconOption("work", Icons.Outlined.Work, "Work"),
        IconOption("school", Icons.Outlined.School, "School"),
        IconOption("shopping_cart", Icons.Outlined.ShoppingCart, "Cart"),
        IconOption("fitness", Icons.Outlined.FitnessCenter, "Fitness"),
        IconOption("code", Icons.Outlined.Code, "Code"),
        IconOption("pets", Icons.Outlined.Pets, "Pets"),
        IconOption("auto_awesome", Icons.Outlined.AutoAwesome, "Sparkle"),
        IconOption("lightbulb", Icons.Outlined.Lightbulb, "Idea"),
        IconOption("filter_list", Icons.Outlined.FilterList, "Filter"),
        IconOption("bookmark", Icons.Outlined.Bookmark, "Bookmark"),
        IconOption("flag", Icons.Outlined.Flag, "Flag"),
        IconOption("build", Icons.Outlined.Build, "Build"),
        IconOption("palette", Icons.Outlined.Palette, "Palette"),
    )

    private val ICON_MAP: Map<String, ImageVector> = PRESET_ICONS.associate { it.key to it.icon }

    private val SMART_LIST_ICONS: Map<BottomBarSlotType, ImageVector> = mapOf(
        BottomBarSlotType.TODAY to Icons.Outlined.WbSunny,
        BottomBarSlotType.UPCOMING to Icons.Outlined.CalendarMonth,
        BottomBarSlotType.ANYTIME to Icons.Outlined.AllInclusive,
    )

    private val TYPE_DEFAULT_ICONS: Map<BottomBarSlotType, ImageVector> = SMART_LIST_ICONS + mapOf(
        BottomBarSlotType.PROJECT to Icons.Outlined.Folder,
        BottomBarSlotType.CUSTOM_LIST to Icons.Outlined.FilterList,
    )

    val INBOX_ICON: ImageVector = Icons.Outlined.MoveToInbox

    fun resolveIcon(slot: BottomBarSlot): ImageVector {
        // Smart lists always use their default icon
        SMART_LIST_ICONS[slot.type]?.let { return it }
        // For project/custom list, use custom icon or type default
        if (slot.iconKey.isNotEmpty()) {
            ICON_MAP[slot.iconKey]?.let { return it }
        }
        return TYPE_DEFAULT_ICONS[slot.type] ?: Icons.Outlined.Folder
    }

    fun resolveLabel(slot: BottomBarSlot): String = when (slot.type) {
        BottomBarSlotType.TODAY -> "Today"
        BottomBarSlotType.UPCOMING -> "Upcoming"
        BottomBarSlotType.ANYTIME -> "Anytime"
        BottomBarSlotType.PROJECT -> ""  // resolved from project data
        BottomBarSlotType.CUSTOM_LIST -> ""  // resolved from list data
    }
}
