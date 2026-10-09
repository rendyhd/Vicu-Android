package com.rendyhd.vicu.ui.components.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.rendyhd.vicu.ui.theme.VicuIdentityColors

/**
 * The identity of a smart list: its icon and its colour. The pairs are test-fixtures/design-tokens-v1.json
 * `identity.lists` (androidIcon, color; SmartListIdentityTest fails on drift) and the colours come from
 * [VicuIdentityColors] in LocalVicuColors. Identity colours only ever colour list icons, never text.
 * Docs: docs/design-system-v1.md.
 */
enum class SmartListIdentity(val key: String, val icon: ImageVector) {
    INBOX("inbox", Icons.Outlined.Inbox),
    TODAY("today", Icons.Outlined.WbSunny),
    UPCOMING("upcoming", Icons.Outlined.CalendarMonth),
    ANYTIME("anytime", Icons.Outlined.Layers),
    ROUTINES("routines", Icons.Outlined.MonitorHeart),
    REVIEW("review", Icons.Outlined.Autorenew),
    LOGBOOK("logbook", Icons.Outlined.CheckCircle),
    ;

    /** The icon colour of this list. */
    fun color(identity: VicuIdentityColors): Color = when (this) {
        INBOX -> identity.inbox
        TODAY -> identity.today
        UPCOMING -> identity.upcoming
        ANYTIME -> identity.anytime
        ROUTINES -> identity.routines
        REVIEW -> identity.review
        LOGBOOK -> identity.logbook
    }
}
