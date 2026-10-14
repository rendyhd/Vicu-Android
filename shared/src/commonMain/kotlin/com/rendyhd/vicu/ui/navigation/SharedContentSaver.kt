package com.rendyhd.vicu.ui.navigation

import androidx.compose.runtime.saveable.Saver
import com.rendyhd.vicu.domain.model.SharedContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How much of a shared text goes into the saved instance state. The state is one small Bundle
 * for the whole app (about 1 MB across the process); a shared web page can be larger than that,
 * and an oversized state crashes the app when it is saved.
 */
internal const val MAX_SAVED_SHARE_CHARS = 20_000

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private class SavedShare(
    val text: String? = null,
    val subject: String? = null,
    val fileUris: List<String> = emptyList(),
    val mimeType: String? = null,
)

/** The share as a string for the saved state; "" for nothing pending. */
internal fun encodeSharedContent(content: SharedContent?): String {
    if (content == null) return ""
    return json.encodeToString(
        SavedShare.serializer(),
        SavedShare(
            text = content.text?.take(MAX_SAVED_SHARE_CHARS),
            subject = content.subject?.take(MAX_SAVED_SHARE_CHARS),
            fileUris = content.fileUris,
            mimeType = content.mimeType,
        ),
    )
}

/** The share [encoded] holds, or null for nothing or a value that cannot be read. */
internal fun decodeSharedContent(encoded: String): SharedContent? {
    if (encoded.isEmpty()) return null
    return try {
        val saved = json.decodeFromString(SavedShare.serializer(), encoded)
        // A share with nothing in it is nothing: it would open an empty sheet.
        if (saved.text == null && saved.subject == null && saved.fileUris.isEmpty()) {
            null
        } else {
            SharedContent(saved.text, saved.subject, saved.fileUris, saved.mimeType)
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * For `rememberSaveable`: the share waiting for the entry sheet. It used to be plain `remember`,
 * so a rotation reopened the (saved) sheet empty and the shared text and files were gone.
 */
internal val SharedContentSaver: Saver<SharedContent?, String> = Saver(
    save = { encodeSharedContent(it) },
    restore = { decodeSharedContent(it) },
)
