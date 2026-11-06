package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.asPaddingValues
import coil3.compose.AsyncImage
import com.rendyhd.vicu.util.ImageTokens
import kotlinx.coroutines.delay

/** What a screen reader says for the image on page [index] (from 0) of [total]. */
internal fun imageViewerDescription(index: Int, total: Int): String =
    if (total <= 1) "Image" else "Image ${index + 1} of $total"

/**
 * The images full screen, swipe between them and pinch to zoom. With [onSave] and [onShare] it offers
 * those for the image on screen; each is given a way to say something, because the viewer covers the
 * app's snackbar. [busyIds] are the images being fetched for one of them.
 */
@Composable
fun ImageViewerDialog(
    taskId: Long,
    images: List<ImageTokens.ImageRef.Image>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onSave: ((Long, (String) -> Unit) -> Unit)? = null,
    onShare: ((Long, (String) -> Unit) -> Unit)? = null,
    busyIds: Set<Long> = emptySet(),
) {
    if (images.isEmpty()) return
    val safeInitial = initialIndex.coerceIn(0, images.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeInitial) { images.size }
    var transform by remember { mutableStateOf(ImageTransform.Identity) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pagerState.currentPage) {
        transform = ImageTransform.Identity
    }
    LaunchedEffect(note) {
        if (note != null) {
            delay(NOTE_MILLIS)
            note = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = transform.scale <= 1.01f,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                var pageSize by remember { mutableStateOf(Size.Zero) }
                val transformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
                    transform = transform.transformedBy(zoomChange, panChange, centroid, pageSize)
                }
                val attId = images[page].attachmentId
                // BaseUrlInterceptor adds the `/api/v2/` prefix — don't duplicate it here.
                val isCurrentPage = page == pagerState.currentPage
                AsyncImage(
                    model = "http://localhost/tasks/$taskId/attachments/$attId",
                    contentDescription = imageViewerDescription(page, images.size),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { pageSize = Size(it.width.toFloat(), it.height.toFloat()) }
                        .graphicsLayer {
                            scaleX = if (isCurrentPage) transform.scale else 1f
                            scaleY = if (isCurrentPage) transform.scale else 1f
                            translationX = if (isCurrentPage) transform.offset.x else 0f
                            translationY = if (isCurrentPage) transform.offset.y else 0f
                        }
                        .transformable(transformState, lockRotationOnZoomPan = true),
                )
            }

            // On a dark plate: an image can be white right up to the corner.
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(WindowInsets.systemBars.asPaddingValues())
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val current = images.getOrNull(pagerState.currentPage)?.attachmentId
                if (current != null && current in busyIds) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(12.dp).size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else if (current != null) {
                    if (onSave != null) {
                        IconButton(onClick = { onSave(current) { note = it } }) {
                            Icon(Icons.Default.Download, contentDescription = "Save image", tint = Color.White)
                        }
                    }
                    if (onShare != null) {
                        IconButton(onClick = { onShare(current) { note = it } }) {
                            Icon(Icons.Default.Share, contentDescription = "Share image", tint = Color.White)
                        }
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
            }

            note?.let { text ->
                Text(
                    text = text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(WindowInsets.systemBars.asPaddingValues())
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** How long a note about saving or sharing stays on the viewer. */
private const val NOTE_MILLIS = 4_000L
