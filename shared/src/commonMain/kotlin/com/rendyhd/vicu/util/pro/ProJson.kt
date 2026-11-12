package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DueDates
import kotlin.math.floor
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Small readers over decoded JSON for the Pro rules (docs/cross-app-semantics-v1.md section 21):
 * every value is read the way the desktop's `typeof` checks read it, and anything that does not
 * have the shape reads as absent.
 */

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON number, as a double; booleans, strings and null are not numbers. */
internal fun JsonElement?.asNumber(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString || primitive is JsonNull) return null
    return primitive.doubleOrNull?.takeIf { it.isFinite() }
}

/** A JSON number with no fraction. */
internal fun JsonElement?.asWhole(): Long? = asNumber()?.takeIf { it == floor(it) }?.toLong()

internal fun JsonElement?.asFlag(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    return if (primitive.isString) null else primitive.content.toBooleanStrictOrNull()
}

internal fun JsonObject.text(key: String): String? = this[key].asText()
internal fun JsonObject.number(key: String): Double? = this[key].asNumber()
internal fun JsonObject.whole(key: String): Long? = this[key].asWhole()
internal fun JsonObject.obj(key: String): JsonObject? = this[key].asObject()
internal fun JsonObject.list(key: String): JsonArray? = this[key] as? JsonArray

/** The whole numbers of an array; other entries are left out. */
internal fun JsonObject.wholes(key: String): List<Long> = list(key)?.mapNotNull { it.asWhole() } ?: emptyList()

/** The texts of an array; other entries are left out. */
internal fun JsonObject.texts(key: String): List<String> = list(key)?.mapNotNull { it.asText() } ?: emptyList()

/** The object with a key set (a null value sets JSON null), the other keys kept in their order. */
internal fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))

internal fun JsonObject.without(vararg keys: String): JsonObject = JsonObject(filterKeys { it !in keys })

internal fun jsonOf(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)
internal fun jsonOf(value: Long?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)
internal fun jsonOf(value: Int?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

/** The local day of a stored instant, or null when it is no date. */
internal fun localDayOf(value: String?, zone: TimeZone): LocalDate? = DueDates.localDateOf(value, zone)

/** A stored date as an instant, or null when it is empty, the null date or unreadable. */
internal fun instantOf(value: String?): Instant? = DateUtils.parseIsoDate(value)

/** Strings in the order of the desktop's `<` on UTF-16 code units. */
internal fun compareText(a: String, b: String): Int = a.compareTo(b)
