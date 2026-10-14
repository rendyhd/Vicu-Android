package com.rendyhd.vicu.data.remote

import kotlinx.serialization.json.Json

/** The JSON settings used for every API call and for the payloads queued for the sync engine. */
fun createApiJson(): Json = Json {
    // A newer server may send fields this app does not know.
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    // The server is written in Go, where an empty list or string can come out as null. A null
    // where the app expects a value takes the field's default instead of failing the whole
    // response (and with it a sync that has nothing wrong with it).
    coerceInputValues = true
}
