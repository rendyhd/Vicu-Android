package com.rendyhd.vicu.ui.components.task

import io.github.linreal.cascade.editor.core.BlockContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DescriptionEditorSessionTest {
    private val overLimitHtml by lazy { "x".repeat(4_000_001) }

    @Test
    fun importCanonicalizationIsNotPublishedAsAnEdit() {
        val session = session("Legacy <strong>notes</strong>")
        var published: String? = null

        val changed = session.publishResolvedIfChanged(session.resolveBlocks()) {
            published = it
        }

        assertFalse(changed)
        assertEquals(null, published)
    }

    @Test
    fun synchronousFlushIncludesTheLatestRuntimeCharacter() {
        val session = session("<p>Alpha</p>")
        session.appendToFirstBlock("!")
        var published: String? = null

        val changed = session.flush { published = it }

        assertTrue(changed)
        assertEquals("<p>Alpha!</p>", published)
    }

    @Test
    fun localViewModelEchoDoesNotReloadEditorState() {
        val session = session("<p>Alpha</p>")
        val originalId = session.stateHolder.state.blocks.single().id
        session.appendToFirstBlock("!")
        var published = ""
        session.flush { published = it }

        val reloaded = session.syncExternalHtml(published)

        assertFalse(reloaded)
        assertEquals(originalId, session.stateHolder.state.blocks.single().id)
        assertEquals("Alpha!", session.firstBlockText())
    }

    @Test
    fun hostCanonicalizationWithoutATextEditDoesNotReloadEditorState() {
        val session = session("Legacy <strong>notes</strong>")
        val originalId = session.stateHolder.state.blocks.single().id

        val canonicalHtml = session.currentHtmlForHost()
        val reloaded = session.syncExternalHtml(canonicalHtml)

        assertFalse(reloaded)
        assertEquals(originalId, session.stateHolder.state.blocks.single().id)
        assertEquals("Legacy notes", session.firstBlockText())
    }

    @Test
    fun acknowledgedLocalValueCanLaterBeAcceptedAsARemoteRevert() {
        val session = session("<p>A</p>")
        session.appendToFirstBlock("B")
        var firstEcho = ""
        session.flush { firstEcho = it }
        assertFalse(session.syncExternalHtml(firstEcho))
        assertTrue(session.syncExternalHtml("<p>Remote</p>"))
        assertEquals("Remote", session.firstBlockText())
        assertTrue(session.syncExternalHtml(firstEcho))
        assertEquals("AB", session.firstBlockText())
    }

    @Test
    fun normalizedPendingLocalEchoDoesNotReloadEditorState() {
        val session = session("<p>A</p>")
        session.appendToFirstBlock("B")
        session.flush {}

        val reloaded = session.syncExternalHtml("<p>AB</p>\n")

        assertFalse(reloaded)
        assertEquals("AB", session.firstBlockText())
    }

    @Test
    fun genuineExternalValueHardReplacesTheDocument() {
        val session = session("<p>Local</p>")

        val reloaded = session.syncExternalHtml("<p>Remote</p>")

        assertTrue(reloaded)
        assertEquals("Remote", session.firstBlockText())
        assertFalse(session.flush {})
    }

    @Test
    fun emptyDescriptionStartsAsAnEditableParagraphWithoutDirtyingHostState() {
        val session = session("")

        assertEquals("", session.firstBlockText())
        assertFalse(session.flush {})

        session.appendToFirstBlock("Ready")
        assertEquals("<p>Ready</p>", session.currentHtmlForHost())
        assertFalse(session.flush {})
    }

    @Test
    fun overLimitInitialDescriptionIsPreservedAndCannotBePublishedOver() {
        val session = session(overLimitHtml)
        session.appendToFirstBlock("replacement")
        var published: String? = null

        assertTrue(session.isImportBlocked)
        assertFalse(session.flush { published = it })
        assertEquals(null, published)
        assertEquals(overLimitHtml, session.currentHtmlForHost())
    }

    @Test
    fun overLimitExternalReplacementFailsClosedUntilAValidReplacementArrives() {
        val session = session("<p>Local</p>")

        assertFalse(session.syncExternalHtml(overLimitHtml))
        session.appendToFirstBlock(" edit")
        assertTrue(session.isImportBlocked)
        assertFalse(session.flush {})
        assertEquals(overLimitHtml, session.currentHtmlForHost())

        assertTrue(session.syncExternalHtml("<p>Recovered</p>"))
        assertFalse(session.isImportBlocked)
        assertEquals("Recovered", session.firstBlockText())
    }

    @Test
    fun recentLocalEchoRecoversSessionAfterRejectedOverLimitValue() {
        val session = session("<p>Local</p>")
        session.appendToFirstBlock(" edit")
        var localEcho = ""
        session.flush { localEcho = it }
        session.syncExternalHtml(overLimitHtml)

        val reloaded = session.syncExternalHtml(localEcho)

        assertFalse(reloaded)
        assertFalse(session.isImportBlocked)
        assertEquals("Local edit", session.firstBlockText())
        assertFalse(session.flush {})
    }

    @Test
    fun duplicateImportedTaskIdsAreMadeUniqueBeforeEnteringTheEditor() {
        val session = session(
            """
                <ul data-type="taskList">
                  <li data-type="taskItem" data-task-id="duplicate"><div><p>One</p></div></li>
                  <li data-type="taskItem" data-task-id="duplicate"><div><p>Two</p></div></li>
                </ul>
            """.trimIndent(),
        )

        val taskIds = session.stateHolder.state.blocks.take(2).map { it.id.value }

        assertEquals(2, taskIds.toSet().size)
    }

    @Test
    fun trailingEditorScaffoldIsNotPublishedAsAnImportEdit() {
        val preservedBlock = "<table><tr><td>Opaque</td></tr></table>"
        val initialSession = session(preservedBlock)
        val replacementSession = session("<p>Local</p>")

        assertFalse(initialSession.flush {})
        assertTrue(replacementSession.syncExternalHtml(preservedBlock))
        assertFalse(replacementSession.flush {})
    }

    @Test
    fun focusEndPutsTheCursorAtTheEndOfTheLastLine() {
        val session = session("<p>First</p><p>Second</p>")
        val last = session.stateHolder.state.blocks.last()

        session.focusEnd()

        assertEquals(last.id, session.stateHolder.state.focusedBlockId)
        assertEquals("Second", session.textStates.getVisibleText(last.id))
        // The raw text starts with Cascade's hidden sentinel; the cursor is after the last letter.
        val state = session.textStates.getOrCreate(last.id, "Second", 0)
        assertEquals(state.text.length, state.selection.start)
        assertEquals(state.text.length, state.selection.end)
    }

    @Test
    fun focusEndOnEmptyNotesFocusesTheEmptyLine() {
        val session = session("")

        session.focusEnd()

        assertEquals(session.stateHolder.state.blocks.single().id, session.stateHolder.state.focusedBlockId)
    }

    @Test
    fun focusRequestIsTakenOnce() {
        val controller = DescriptionEditorController()
        assertFalse(controller.takeFocusRequest())

        controller.requestFocus()

        assertTrue(controller.isFocusRequested)
        assertTrue(controller.takeFocusRequest())
        assertFalse(controller.isFocusRequested)
        assertFalse(controller.takeFocusRequest())
    }
}

private fun session(html: String): DescriptionEditorSession =
    DescriptionEditorSession(
        initialHtml = html,
        warningReporter = {},
    )

private fun DescriptionEditorSession.appendToFirstBlock(text: String) {
    val block = stateHolder.state.blocks.single()
    val initialText = (block.content as BlockContent.Text).text
    textStates.getOrCreate(
        blockId = block.id,
        initialText = initialText,
        initialCursorPosition = initialText.length,
    ).edit {
        append(text)
    }
}

private fun DescriptionEditorSession.firstBlockText(): String =
    (resolveBlocks().single().content as BlockContent.Text).text
