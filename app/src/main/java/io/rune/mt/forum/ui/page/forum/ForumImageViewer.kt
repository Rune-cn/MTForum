// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.nav.gesture.PredictiveBackHandler

/**
 * Full-screen zoomable image viewer for a tapped post picture.
 *
 * Pinch to zoom (1×–5×), drag to pan while zoomed, tap to dismiss. Kept dependency-free (no
 * third-party zoom library) so it composes with the Miuix theme without extra wiring.
 */
/**
 * Transform applied to the lightbox while the predictive-back gesture runs.
 *
 * At rest (0) this is exactly the identity, so an untouched lightbox is pixel-identical to before;
 * at commit (1) the picture has shrunk slightly and faded out while the scrim cleared. Kept pure so
 * the gesture mapping can be asserted without driving a real touch stream.
 */
internal data class ImageViewerDismissVisuals(
    val scale: Float,
    val alpha: Float,
    val scrimAlpha: Float,
)

/** Panel scale reduction at full predictive-back drag. */
internal const val VIEWER_DISMISS_SCALE_DELTA = 0.12f

/** Maps predictive-back progress (0..1, clamped) to the lightbox transform. */
internal fun imageViewerDismissVisuals(progress: Float): ImageViewerDismissVisuals {
    val p = progress.coerceIn(0f, 1f)
    return ImageViewerDismissVisuals(
        scale = 1f - VIEWER_DISMISS_SCALE_DELTA * p,
        alpha = 1f - p,
        scrimAlpha = 0.96f * (1f - p),
    )
}

/**
 * Full-screen zoomable image viewer for a tapped post picture.
 *
 * Pinch to zoom (1脳鈥?脳), drag to pan while zoomed, tap to dismiss.
 *
 * **Predictive back** (user request): the system back gesture drives the lightbox instead of
 * popping the thread underneath it. The handler is registered *inside* the dialog and enabled only
 * while the picture is shown, which is the AGENTS rule 15 exception shape (no standing
 * `BackHandler` in a nav entry): while the lightbox is shut the handler does not exist, so
 * `NavDisplay` keeps sole ownership of the gesture. The drag streams `progress` into the same
 * transform the commit uses, so the lightbox shrinks/fades with the finger.
 */
@Composable
fun ForumImageViewer(
    url: String?,
    onDismiss: () -> Unit,
) {
    if (url.isNullOrBlank()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var dismissProgress by remember { mutableFloatStateOf(0f) }
        val visuals = imageViewerDismissVisuals(dismissProgress)

        // Inside the dialog: the handler only exists while the picture is open, and it consumes the
        // gesture so the thread behind does not preview its own dismissal at the same time.
        PredictiveBackHandler(
            enabled = true,
            onProgress = { events ->
                try {
                    events.collect { event -> dismissProgress = event.progress }
                } catch (cancel: CancellationException) {
                    dismissProgress = 0f
                    throw cancel
                }
            },
            onCommit = {
                dismissProgress = 0f
                onDismiss()
            },
            onCancel = { dismissProgress = 0f },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = visuals.scrimAlpha))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            if (scale == 1f) offset = Offset.Zero
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale * visuals.scale
                        scaleY = scale * visuals.scale
                        alpha = visuals.alpha
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
        }
    }
}
