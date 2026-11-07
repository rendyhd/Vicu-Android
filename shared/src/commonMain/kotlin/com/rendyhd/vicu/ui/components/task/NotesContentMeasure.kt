package com.rendyhd.vicu.ui.components.task

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.core.BlockId
import io.github.linreal.cascade.editor.core.BlockType
import io.github.linreal.cascade.editor.registry.BlockCallbacks
import io.github.linreal.cascade.editor.registry.BlockRegistry
import io.github.linreal.cascade.editor.registry.BlockRenderScope
import io.github.linreal.cascade.editor.registry.BlockRenderer
import io.github.linreal.cascade.editor.registry.ScopedBlockRenderer

/**
 * How tall the notes' text really is. Cascade's list always fills the height it is given and keeps
 * its scroll state to itself, so its own size says nothing about its content. Each block reports
 * where it ends instead, through a renderer wrapped around the registered one.
 */
@Stable
internal class NotesContentMeasure {
    private val bottoms = mutableStateMapOf<BlockId, Float>()

    /** Set while the editor is placed; block positions are read relative to it. */
    var editor: LayoutCoordinates? = null

    /** Where the last block ends, in pixels from the top of the editor; null before any is placed. */
    val contentHeight: Int? by derivedStateOf { bottoms.values.maxOrNull()?.toInt() }

    fun report(id: BlockId, block: LayoutCoordinates) {
        val root = editor ?: return
        if (!root.isAttached || !block.isAttached) return
        val bottom = root.localBoundingBoxOf(block, clipBounds = false).bottom
        if (bottoms[id] != bottom) bottoms[id] = bottom
    }

    fun forget(id: BlockId) {
        bottoms.remove(id)
    }
}

/** Wraps every renderer of this registry, the given [extraTypeIds] too, so its blocks report to [measure]. */
@Suppress("UNCHECKED_CAST")
internal fun BlockRegistry.measuredBy(measure: NotesContentMeasure, extraTypeIds: List<String>): BlockRegistry = apply {
    (getAllDescriptors().map { it.typeId } + extraTypeIds).distinct().forEach { typeId ->
        val renderer = getRenderer(typeId) as? BlockRenderer<BlockType> ?: return@forEach
        registerRenderer(typeId, MeasuredBlockRenderer(renderer, measure))
    }
}

private class MeasuredBlockRenderer(
    private val delegate: BlockRenderer<BlockType>,
    private val measure: NotesContentMeasure,
) : ScopedBlockRenderer<BlockType> {
    override val handlesSelectionVisual: Boolean get() = delegate.handlesSelectionVisual
    override val supportsDragPreview: Boolean get() = delegate.supportsDragPreview

    @Composable
    override fun Render(
        block: Block,
        isSelected: Boolean,
        isFocused: Boolean,
        modifier: Modifier,
        callbacks: BlockCallbacks,
        scope: BlockRenderScope,
    ) {
        DisposableEffect(block.id) {
            onDispose { measure.forget(block.id) }
        }
        val measured = modifier.onGloballyPositioned { measure.report(block.id, it) }
        if (delegate is ScopedBlockRenderer<BlockType>) {
            delegate.Render(block, isSelected, isFocused, measured, callbacks, scope)
        } else {
            delegate.Render(block, isSelected, isFocused, measured, callbacks)
        }
    }
}
