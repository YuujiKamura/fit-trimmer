package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlin.concurrent.read

/**
 * A composable function that provides a surface for rendering video frames
 * within the Windows video player. It adjusts to size changes and ensures the video
 * is displayed properly with respect to its aspect ratio.
 *
 * @param playerState The state of the Windows video player, used to manage video playback and rendering.
 * @param modifier The modifier to be used to adjust the layout or styling of the composable.
 */
@Composable
fun WindowsVideoPlayerSurface(
    playerState: WindowsVideoPlayerState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.onSizeChanged {
            playerState.onResized()
        },
        contentAlignment = Alignment.Center
    ) {
        if (playerState.hasMedia) {
            val ticks = playerState.frameTicks
            // Draw the video frame to fill the entire canvas area
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(playerState.aspectRatio),
            ) {
                // Reference ticks to register redraw dependency
                val t = ticks
                playerState.bitmapLock.read {
                    playerState.currentFrameBitmap?.let { bitmap ->
                        val frame = bitmap.asComposeImageBitmap()
                        drawImage(
                            image = frame,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt())
                        )
                    }
                }
            }
        }
    }
}
