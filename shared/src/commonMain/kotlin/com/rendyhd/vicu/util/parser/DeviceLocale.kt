package com.rendyhd.vicu.util.parser

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** The language tag of the device's current locale: what orders slash dates unless a caller says otherwise. */
internal fun deviceLocaleTag(): String = Locale.getDefault().toLanguageTag()

private val dayFirstByTag = ConcurrentHashMap<String, Boolean>()

/**
 * True when slash dates in the locale [tag] are day/month ("15/10"), false when month/day
 * ("10/15"). It is decided from the locale's own short date format, like desktop, so a locale whose
 * short date starts with the year (ja, sv, hu) counts as month/day for the part after the year. An
 * unknown tag falls back to month/day.
 */
internal fun isDayFirstLocale(tag: String): Boolean =
    dayFirstByTag.getOrPut(tag) {
        val pattern = runCatching {
            (DateFormat.getDateInstance(DateFormat.SHORT, Locale.forLanguageTag(tag)) as? SimpleDateFormat)?.toPattern()
        }.getOrNull().orEmpty()
        val day = pattern.indexOf('d')
        val month = pattern.indexOf('M')
        day >= 0 && month >= 0 && day < month
    }
