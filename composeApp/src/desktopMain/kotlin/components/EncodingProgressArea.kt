package components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import utils.formatTime
import androidx.compose.ui.text.font.FontWeight

@Composable
fun EncodingProgressArea(
    progressProvider: () -> Float,
    statusTextProvider: () -> String,
    encodingPreviewImageProvider: () -> ImageBitmap?,
    showLivePreview: Boolean,
    controlsEnabled: Boolean,
    videoLengthMs: Long,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = progressProvider()
    val statusText = statusTextProvider()
    val encodingPreviewImage = encodingPreviewImageProvider()
    val currentMs = (progress * videoLengthMs).toLong()

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .aspectRatio(16f / 9f)
                .fillMaxWidth()
                .background(Color.Black, shape = RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFFE5E5EA), shape = RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
        ) {
            if (showLivePreview) {
                encodingPreviewImage?.let { hudImg ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val imgW = hudImg.width.toFloat()
                        val imgH = hudImg.height.toFloat()
                        if (imgW > 0f && imgH > 0f) {
                            val imgAspect = imgW / imgH
                            val canvasAspect = size.width / size.height
                            
                            val dstW: Float
                            val dstH: Float
                            val offsetX: Float
                            val offsetY: Float
                            
                            if (canvasAspect > imgAspect) {
                                dstH = size.height
                                dstW = size.height * imgAspect
                                offsetX = (size.width - dstW) / 2f
                                offsetY = 0f
                            } else {
                                dstW = size.width
                                dstH = size.width / imgAspect
                                offsetX = 0f
                                offsetY = (size.height - dstH) / 2f
                            }
                            
                            drawImage(
                                image = hudImg,
                                dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
                                dstSize = IntSize(dstW.toInt(), dstH.toInt())
                            )
                        } else {
                            drawImage(
                                image = hudImg,
                                dstSize = IntSize(size.width.toInt(), size.height.toInt())
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xE0101012)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Live Preview Disabled\n(Encoding is running)",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Progress bar and controls
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${formatTime(currentMs)} / ${formatTime(videoLengthMs)} (${(progress * 100).toInt()}%)",
                color = Color(0xFF1C1C1E),
                fontSize = 11.sp
            )

            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.weight(1f).height(6.dp),
                color = Color(0xFF007AFF),
                backgroundColor = Color(0xFFE5E5EA)
            )

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = onCancel,
                enabled = controlsEnabled,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFFEF4444),
                    disabledBackgroundColor = Color(0xFFE5E5EA)
                )
            ) {
                Text("⏹ CANCEL", color = if (controlsEnabled) Color.White else Color(0xFF8E8E93), fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }

        if (statusText.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = statusText,
                color = Color(0xFF8E8E93),
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                textAlign = TextAlign.Start
            )
        }
    }
}
