package com.rendyhd.vicu.domain.model

import android.net.Uri

/**
 * Holds content received from Android's share intent (ACTION_SEND / ACTION_SEND_MULTIPLE).
 */
data class SharedContent(
    val text: String? = null,
    val subject: String? = null,
    val fileUris: List<Uri> = emptyList(),
    val mimeType: String? = null,
) {
    val hasFiles: Boolean get() = fileUris.isNotEmpty()

    val suggestedTitle: String?
        get() = subject ?: text?.lineSequence()?.firstOrNull()?.take(100)

    val suggestedDescription: String?
        get() = when {
            subject != null && text != null && subject != text -> text
            subject == null && text != null -> text
            else -> null
        }
}
