package components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.rememberDialogState

@Composable
fun PlateTrackingLogsDialog(
    logs: List<String>,
    onClose: () -> Unit
) {
    val dialogState = rememberDialogState(width = 750.dp, height = 500.dp)
    
    Dialog(
        onCloseRequest = onClose,
        state = dialogState,
        title = "ナンバープレート追尾詳細ログ (Plate Tracking Details)"
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF2F2F7))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "リアルタイム追従ログ (Real-time Tracking Logs)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1C1C1E)
                )
                Text(
                    text = "Total Log Lines: ${logs.size}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            val listState = rememberLazyListState()
            
            // Auto-scroll to the bottom when new logs arrive
            LaunchedEffect(logs.size) {
                if (logs.isNotEmpty()) {
                    listState.animateScrollToItem(logs.size - 1)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "ログはありません。プレート検出を開始すると表示されます。\n(No logs recorded yet. Start scanning to display logs.)",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs) { log ->
                            Text(
                                text = log,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = getLogColor(log)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF007AFF)),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("閉じる (Close)", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun getLogColor(log: String): Color {
    return when {
        log.contains("created", ignoreCase = true) -> Color(0xFF34C759) // Green for creation
        log.contains("lost", ignoreCase = true) -> Color(0xFFFF3B30) // Red for loss
        log.contains("Reconstructed", ignoreCase = true) -> Color(0xFF007AFF) // Blue for reconstruction
        log.contains("matched", ignoreCase = true) -> Color(0xFF8E8E93) // Gray for normal matches
        log.contains("Failed", ignoreCase = true) || log.contains("Error", ignoreCase = true) -> Color(0xFFD12A2A) // Dark red for errors
        else -> Color(0xFF1C1C1E)
    }
}
