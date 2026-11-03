package com.rendyhd.vicu.domain.model

data class SharedContent(
    val text: String? = null,
    val subject: String? = null,
    val fileUris: List<String> = emptyList(),
    val mimeType: String? = null,
) {
    val hasFiles: Boolean get() = fileUris.isNotEmpty()

    /**
     * The subject, else the first line of the text; none when files were shared. The subject
     * that comes with files is the sender's name for them (Android's screenshot sends
     * "Screenshot (<date>)", other apps the file name), and it had to be deleted before typing.
     */
    val suggestedTitle: String?
        get() = if (hasFiles) null else subject ?: text?.lineSequence()?.firstOrNull()?.take(100)

    /** The text, unless it is all the title already says; with files, unless it only repeats the subject. */
    val suggestedDescription: String?
        get() = when {
            hasFiles -> text?.takeUnless { it == subject }
            subject != null && text != null && subject != text -> text
            subject == null && text != null -> text
            else -> null
        }
}
