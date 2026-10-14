package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.core.BlockContent
import io.github.linreal.cascade.editor.core.BlockType
import io.github.linreal.cascade.editor.registry.BlockCallbacks
import io.github.linreal.cascade.editor.registry.BlockRenderScope
import io.github.linreal.cascade.editor.registry.BlockRegistry
import io.github.linreal.cascade.editor.registry.ScopedBlockRenderer
import io.github.linreal.cascade.editor.ui.createEditorRegistry

internal fun createVikunjaDescriptionEditorRegistry(): BlockRegistry =
    createEditorRegistry().apply {
        registerRenderer(
            VikunjaDescriptionHtmlProfile.PRESERVED_HTML_TYPE_ID,
            PreservedHtmlBlockRenderer,
        )
    }

internal object PreservedHtmlBlockRenderer : ScopedBlockRenderer<BlockType> {
    @Composable
    override fun Render(
        block: Block,
        isSelected: Boolean,
        isFocused: Boolean,
        modifier: Modifier,
        callbacks: BlockCallbacks,
        scope: BlockRenderScope,
    ) {
        val tagName = ((block.content as? BlockContent.Custom)?.data?.get("tagName") as? String)
            ?.takeIf { it.isNotBlank() }
        var confirmingRemoval by remember { mutableStateOf(false) }
        if (confirmingRemoval) {
            // The block holds HTML the editor cannot show (a table, say); removing it loses that
            // content for good, so it takes a second step.
            AlertDialog(
                onDismissRequest = { confirmingRemoval = false },
                title = { Text("Remove this content?") },
                text = {
                    Text(
                        "The description has ${tagName?.let { "a <$it> block" } ?: "a block"} that this " +
                            "editor cannot show. It is kept as it is until you remove it, and removing " +
                            "it deletes it from the description.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmingRemoval = false
                            scope.deleteBlock(block.id)
                        },
                    ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingRemoval = false }) { Text("Keep") }
                },
            )
        }
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                )
                Text(
                    text = tagName?.let { "Unsupported <$it> HTML preserved" }
                        ?: "Unsupported HTML preserved",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                )
                IconButton(
                    onClick = { confirmingRemoval = true },
                    enabled = scope.canEditBlockStructure,
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Remove preserved HTML block",
                    )
                }
            }
        }
    }
}
