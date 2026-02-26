package com.rendyhd.vicu.ui.components.task

import androidx.compose.ui.graphics.Color
import com.rendyhd.vicu.util.parser.TokenType

// Light theme text colors
private val DATE_TEXT_LIGHT = Color(0xFF16A34A)
private val PRIORITY_TEXT_LIGHT = Color(0xFFDC2626)
private val LABEL_TEXT_LIGHT = Color(0xFFEA580C)
private val PROJECT_TEXT_LIGHT = Color(0xFF2563EB)
private val RECURRENCE_TEXT_LIGHT = Color(0xFF9333EA)

// Dark theme text colors
private val DATE_TEXT_DARK = Color(0xFF4ADE80)
private val PRIORITY_TEXT_DARK = Color(0xFFF87171)
private val LABEL_TEXT_DARK = Color(0xFFFB923C)
private val PROJECT_TEXT_DARK = Color(0xFF60A5FA)
private val RECURRENCE_TEXT_DARK = Color(0xFFC084FC)

// Light theme background colors (alpha ~0.12)
private val DATE_BG_LIGHT = Color(0x1F22C55E)
private val PRIORITY_BG_LIGHT = Color(0x1FEF4444)
private val LABEL_BG_LIGHT = Color(0x1FF97316)
private val PROJECT_BG_LIGHT = Color(0x1F3B82F6)
private val RECURRENCE_BG_LIGHT = Color(0x1FA855F7)

// Dark theme background colors (alpha ~0.20)
private val DATE_BG_DARK = Color(0x3322C55E)
private val PRIORITY_BG_DARK = Color(0x33EF4444)
private val LABEL_BG_DARK = Color(0x33F97316)
private val PROJECT_BG_DARK = Color(0x333B82F6)
private val RECURRENCE_BG_DARK = Color(0x33A855F7)

fun tokenTextColor(type: TokenType, isDark: Boolean): Color = when (type) {
    TokenType.DATE -> if (isDark) DATE_TEXT_DARK else DATE_TEXT_LIGHT
    TokenType.PRIORITY -> if (isDark) PRIORITY_TEXT_DARK else PRIORITY_TEXT_LIGHT
    TokenType.LABEL -> if (isDark) LABEL_TEXT_DARK else LABEL_TEXT_LIGHT
    TokenType.PROJECT -> if (isDark) PROJECT_TEXT_DARK else PROJECT_TEXT_LIGHT
    TokenType.RECURRENCE -> if (isDark) RECURRENCE_TEXT_DARK else RECURRENCE_TEXT_LIGHT
}

fun tokenBgColor(type: TokenType, isDark: Boolean): Color = when (type) {
    TokenType.DATE -> if (isDark) DATE_BG_DARK else DATE_BG_LIGHT
    TokenType.PRIORITY -> if (isDark) PRIORITY_BG_DARK else PRIORITY_BG_LIGHT
    TokenType.LABEL -> if (isDark) LABEL_BG_DARK else LABEL_BG_LIGHT
    TokenType.PROJECT -> if (isDark) PROJECT_BG_DARK else PROJECT_BG_LIGHT
    TokenType.RECURRENCE -> if (isDark) RECURRENCE_BG_DARK else RECURRENCE_BG_LIGHT
}

fun tokenChipColor(type: TokenType, isDark: Boolean): Color = tokenTextColor(type, isDark)
