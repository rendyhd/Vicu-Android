package com.rendyhd.vicu.ui.components.task

import com.rendyhd.vicu.util.CrossAppFixture
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `markdown` vectors of test-fixtures/description-format-v1.json (description format,
 * "Markdown input"). The desktop runs the same file in src/renderer/lib/__tests__/markdown-input.test.ts.
 */
class MarkdownInputFixtureTest {

    @Serializable
    private data class Vector(val name: String, val markdown: String, val html: String, val isMarkdown: Boolean)

    @Serializable
    private data class Fixture(val markdown: List<Vector>)

    private val vectors: List<Vector> by lazy {
        Json { ignoreUnknownKeys = true }
            .decodeFromString(Fixture.serializer(), CrossAppFixture.readTestFixture("description-format-v1.json"))
            .markdown
    }

    @Test
    fun everyVectorGivesItsHtmlAndFlag() {
        assertTrue(vectors.size >= 30, "vectors: ${vectors.size}")
        val failures = vectors.mapNotNull { vector ->
            val result = MarkdownInput.toDescriptionHtml(vector.markdown)
            when {
                result.html != vector.html -> "${vector.name}: html\n  expected ${vector.html}\n  actual   ${result.html}"
                result.markdown != vector.isMarkdown -> "${vector.name}: isMarkdown ${result.markdown}"
                else -> null
            }
        }
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    @Test
    fun markdownHrefAcceptsOnlyWebAndMail() {
        for (url in listOf("https://example.com", "HTTP://x", "mailto:me@example.com")) {
            assertEquals(url, MarkdownInput.markdownHref(url))
        }
        for (url in listOf("https://", "mailto:", "javascript:alert(1)", "/relative", "www.example.com", "https://a b", "ftp://x")) {
            assertNull(MarkdownInput.markdownHref(url), url)
        }
    }
}
