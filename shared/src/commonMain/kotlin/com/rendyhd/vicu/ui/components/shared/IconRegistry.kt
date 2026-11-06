package com.rendyhd.vicu.ui.components.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Build
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
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.vector.ImageVector
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.BottomBarSlotType
import com.rendyhd.vicu.domain.model.SyncedPrefKeys

data class IconOption(
    val key: String,
    val icon: ImageVector,
    val label: String,
)

object IconRegistry {

    /** The key syncs as a custom list's `icon` and the desktop draws the same keys (custom-list-icons.ts): never rename one. */
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

    /** The key of "no icon": a custom list (or a project, section 11.3) drawn with its name alone. */
    const val NONE = SyncedPrefKeys.ICON_NONE

    /** The None tile of a picker that offers it. */
    val NONE_OPTION = IconOption(NONE, Icons.Outlined.Block, "None")

    /**
     * The icon a custom list is drawn with: its preset, the filter for an empty or unknown key, or
     * null for [NONE], which leaves the room of the icon to the name.
     */
    fun customListIcon(key: String): ImageVector? =
        if (key == NONE) null else ICON_MAP[key] ?: Icons.Outlined.FilterList

    /** The smart list a slot type shows, or null for a project or custom list slot. */
    fun smartListIdentity(type: BottomBarSlotType): SmartListIdentity? = when (type) {
        BottomBarSlotType.TODAY -> SmartListIdentity.TODAY
        BottomBarSlotType.PLAN -> SmartListIdentity.PLAN
        BottomBarSlotType.UPCOMING -> SmartListIdentity.UPCOMING
        BottomBarSlotType.ANYTIME -> SmartListIdentity.ANYTIME
        BottomBarSlotType.PROJECT, BottomBarSlotType.CUSTOM_LIST -> null
    }

    private val SMART_LIST_ICONS: Map<BottomBarSlotType, ImageVector> = mapOf(
        BottomBarSlotType.TODAY to SmartListIdentity.TODAY.icon,
        BottomBarSlotType.PLAN to SmartListIdentity.PLAN.icon,
        BottomBarSlotType.UPCOMING to SmartListIdentity.UPCOMING.icon,
        BottomBarSlotType.ANYTIME to SmartListIdentity.ANYTIME.icon,
    )

    private val TYPE_DEFAULT_ICONS: Map<BottomBarSlotType, ImageVector> = SMART_LIST_ICONS + mapOf(
        BottomBarSlotType.PROJECT to Icons.Outlined.Folder,
        BottomBarSlotType.CUSTOM_LIST to Icons.Outlined.FilterList,
    )

    fun resolveIcon(slot: BottomBarSlot): ImageVector {
        // Smart lists always use their default icon
        SMART_LIST_ICONS[slot.type]?.let { return it }
        // For project/custom list, use custom icon or type default
        if (slot.iconKey.isNotEmpty()) {
            ICON_MAP[slot.iconKey]?.let { return it }
        }
        return TYPE_DEFAULT_ICONS[slot.type] ?: Icons.Outlined.Folder
    }
}
