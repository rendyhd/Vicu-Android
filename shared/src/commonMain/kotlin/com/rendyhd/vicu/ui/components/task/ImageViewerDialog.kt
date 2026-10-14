package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

/** What a screen reader says for the image on page [index] (from 0) of [total]. */
internal fun imageViewerDescription(index: Int, total: Int): String =
    if (total <= 1) "Image" else "Image ${index + 1} of $total"

@Composable
fun ImageViewerDialog(
    taskId: Long,
    images: List<ImageTokens.ImageRef.Image>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (images.isEmpty()) return
    val safeInitial = initialIndex.coerceIn(0, images.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeInitial) { images.size }
    var transform by remember { mutableStateOf(ImageTransform.Identity) }

    LaunchedEffect(pagerState.currentPage) {
        transform = ImageTransform.Identity
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

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(WindowInsets.systemBars.asPaddingValues())
                    .padding(4.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
        }
    }
}
