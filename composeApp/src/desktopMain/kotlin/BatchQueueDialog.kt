import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import viewmodel.AppViewModel
import viewmodel.BatchJobStatus
import viewmodel.BatchJobPhaseStatus
import viewmodel.BatchJobPhaseType
import java.io.File

@Composable
fun BatchQueueDialog(
    viewModel: AppViewModel,
    outputDir: String,
    moveOutputToSource: Boolean,
    scope: CoroutineScope
) {
    if (!viewModel.showBatchConfirmDialog) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .width(960.dp)
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ヘッダー
                Text(
                    text = if (viewModel.isBatchRunning) "バッチ処理 実行中" else "バッチ処理キューと進行管理",
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF007AFF),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                
                Text(
                    text = if (viewModel.isBatchRunning) 
                        "現在バックグラウンドでバッチ処理を実行しています。この画面のまま進行状況を確認できます。" 
                    else 
                        "以下のジョブ（計 ${viewModel.batchQueue.size} 件）の進行管理とフェーズのオンオフを設定できます。",
                    fontSize = 12.sp,
                    color = Color(0xFF1C1C1E)
                )
                
                Divider(color = Color(0xFFE5E5EA))
                
                // バッチ全体の大容量進捗バー (実行中のみ)
                if (viewModel.isBatchRunning) {
                    val completedCount = viewModel.batchQueue.count { it.status == BatchJobStatus.COMPLETED }
                    val totalCount = viewModel.batchQueue.size
                    val overallProgress = if (totalCount > 0) completedCount.toFloat() / totalCount.toFloat() else 0f
                    
                    Surface(
                        color = Color(0xFFF2F2F7),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = viewModel.batchStatusText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF007AFF)
                                )
                                Text(
                                    text = "全体進捗: $completedCount / $totalCount 件完了 (${(overallProgress * 100).toInt()}%)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF636366)
                                )
                            }
                            LinearProgressIndicator(
                                progress = overallProgress,
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = Color(0xFF34C759),
                                backgroundColor = Color(0xFFD1D1D6)
                            )
                        }
                    }
                }
                
                // ジョブのタスクリスト
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(1.dp, Color(0xFFE5E5EA), RoundedCornerShape(8.dp))
                        .background(Color(0xFFF9F9F9))
                        .padding(8.dp)
                ) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(viewModel.batchQueue.size) { idx ->
                            val job = viewModel.batchQueue[idx]
                            val canEditJob = !viewModel.isBatchRunning
                            var entryNameDraft by remember(job.id, job.entryName) { mutableStateOf(job.entryName) }
                            
                            // 実行中のジョブカードの背景色を変更してアクティブなジョブを強調
                            val cardBgColor = if (job.status == BatchJobStatus.RUNNING) {
                                Color(0xFFF2F8FF)
                            } else {
                                Color.White
                            }
                            val cardBorderColor = if (job.status == BatchJobStatus.RUNNING) {
                                Color(0xFF007AFF)
                            } else {
                                Color(0xFFE5E5EA)
                            }
                            
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(cardBgColor, RoundedCornerShape(8.dp))
                                    .border(1.dp, cardBorderColor, RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 上段: ファイル名/編集と操作ボタン
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (canEditJob) {
                                        // 編集モード
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = entryNameDraft,
                                                onValueChange = { entryNameDraft = it },
                                                enabled = true,
                                                singleLine = true,
                                                textStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                                modifier = Modifier.weight(1f).height(48.dp),
                                                colors = TextFieldDefaults.outlinedTextFieldColors(
                                                    textColor = Color(0xFF1C1C1E),
                                                    focusedBorderColor = Color(0xFF007AFF),
                                                    unfocusedBorderColor = Color(0xFFE5E5EA)
                                                )
                                            )
                                            OutlinedButton(
                                                onClick = {
                                                    viewModel.renameBatchJobEntry(job.id, entryNameDraft)
                                                    entryNameDraft = job.entryName
                                                },
                                                enabled = entryNameDraft.trim().isNotEmpty() && entryNameDraft != job.entryName,
                                                modifier = Modifier.height(34.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp)
                                            ) {
                                                Text("保存", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    } else {
                                        // 進行確認モード (大きなラベル表示)
                                        Text(
                                            text = job.entryName,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (job.status == BatchJobStatus.RUNNING) Color(0xFF007AFF) else Color(0xFF1C1C1E),
                                            modifier = Modifier.weight(1f).padding(vertical = 6.dp)
                                        )
                                    }
                                    
                                    Spacer(Modifier.width(12.dp))
                                    
                                    // ジョブ自体のステータスバッジ
                                    val jobStatusColor = when (job.status) {
                                        BatchJobStatus.WAITING -> Color.Gray
                                        BatchJobStatus.RUNNING -> Color(0xFF007AFF)
                                        BatchJobStatus.COMPLETED -> Color(0xFF34C759)
                                        BatchJobStatus.FAILED -> Color(0xFFFF3B30)
                                    }
                                    Surface(
                                        color = jobStatusColor.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = when(job.status) {
                                                BatchJobStatus.WAITING -> "待機中"
                                                BatchJobStatus.RUNNING -> "処理中 (${(job.progress * 100).toInt()}%)"
                                                BatchJobStatus.COMPLETED -> "完了"
                                                BatchJobStatus.FAILED -> "失敗"
                                            },
                                            color = jobStatusColor,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    
                                    // 操作ボタン (編集モード中のみ表示)
                                    if (canEditJob) {
                                        Spacer(Modifier.width(8.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedButton(
                                                onClick = { viewModel.moveBatchJobUp(job.id) },
                                                enabled = idx > 0,
                                                modifier = Modifier.size(30.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Icon(Icons.Filled.KeyboardArrowUp, null, modifier = Modifier.size(18.dp))
                                            }
                                            OutlinedButton(
                                                onClick = { viewModel.moveBatchJobDown(job.id) },
                                                enabled = idx < viewModel.batchQueue.lastIndex,
                                                modifier = Modifier.size(30.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Icon(Icons.Filled.KeyboardArrowDown, null, modifier = Modifier.size(18.dp))
                                            }
                                            OutlinedButton(
                                                onClick = { viewModel.removeFromBatchQueue(job.id) },
                                                modifier = Modifier.size(30.dp),
                                                contentPadding = PaddingValues(0.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE02424)),
                                                border = BorderStroke(1.dp, Color(0xFFE02424))
                                            ) {
                                                Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                                
                                // 中段: 加工内容バッジと詳細情報
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (job.fitPath.isNotEmpty()) {
                                        DialogBadge(text = "HUD焼き付け", backgroundColor = Color(0xFFE8F2FF), textColor = Color(0xFF007AFF))
                                    } else {
                                        DialogBadge(text = "高速トリム (HUDなし)", backgroundColor = Color(0xFFF2F2F7), textColor = Color(0xFF8E8E93))
                                    }
                                    
                                    if (job.settings.blurLicensePlates && job.fitPath.isNotEmpty()) {
                                        DialogBadge(text = "プレートぼかし", backgroundColor = Color(0xFFE2F9E9), textColor = Color(0xFF34C759))
                                    }
                                    
                                    if (job.autoDetectRoadCaptionsOnEncode && job.fitPath.isNotEmpty()) {
                                        DialogBadge(text = "路線名検出", backgroundColor = Color(0xFFFFF7E6), textColor = Color(0xFFFF9500))
                                    }
                                    
                                    val resolution = if (job.settings.exportResolution.isEmpty()) "元サイズ" else job.settings.exportResolution
                                    DialogBadge(text = "解像度: $resolution", backgroundColor = Color(0xFFF2F2F7), textColor = Color(0xFF1C1C1E))
                                    
                                    Spacer(Modifier.weight(1f))
                                    
                                    Text(
                                        text = "トリム: %.1fs - %.1fs (分割: ${job.splitPoints.size + 1})".format(job.trimStartSeconds, job.trimEndSeconds),
                                        fontSize = 10.sp,
                                        color = Color(0xFF636366),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                
                                // エラーメッセージ
                                if (job.errorMessage != null) {
                                    Text(
                                        text = "❌ エラー: ${job.errorMessage}",
                                        color = Color(0xFFFF3B30),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFFFF3B30).copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                                            .padding(6.dp)
                                    )
                                }
                                
                                // キャッシュサルベージ支援UI (編集モードのみ)
                                if (canEditJob) {
                                    val availableJobs = remember(job.videoPath) { fit.CacheRegistry.scanAvailableJobs(job.videoPath) }
                                    if (availableJobs.isNotEmpty()) {
                                        val jobCache = availableJobs.first()
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFFFFF9E6), RoundedCornerShape(4.dp))
                                                .border(1.dp, Color(0xFFFFD60A), RoundedCornerShape(4.dp))
                                                .padding(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("⚠️ 未完了キャッシュ (TSパーツ: ${jobCache.partsCount}) があります", fontSize = 10.sp, color = Color(0xFFFF9500), fontWeight = FontWeight.Bold)
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                OutlinedButton(
                                                    onClick = {
                                                        job.phases.forEach { p ->
                                                            when (p.type) {
                                                                BatchJobPhaseType.PLATE_SCAN -> p.enabled = false
                                                                BatchJobPhaseType.ROAD_SCAN -> {
                                                                    p.enabled = false
                                                                    p.status = BatchJobPhaseStatus.COMPLETED
                                                                }
                                                                BatchJobPhaseType.HUD_ENCODE -> {
                                                                    p.enabled = false
                                                                    p.status = BatchJobPhaseStatus.COMPLETED
                                                                }
                                                                BatchJobPhaseType.CONCAT_MERGE -> {
                                                                    p.enabled = true
                                                                    p.status = BatchJobPhaseStatus.WAITING
                                                                    p.progress = 0f
                                                                }
                                                                else -> {}
                                                            }
                                                        }
                                                        viewModel.saveBatchQueue()
                                                    },
                                                    modifier = Modifier.height(24.dp),
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9500)),
                                                    border = BorderStroke(1.dp, Color(0xFFFF9500))
                                                ) {
                                                    Text("マージ復元モードにする", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                }
                                                OutlinedButton(
                                                    onClick = {
                                                        fit.CacheRegistry.deleteCacheJob(jobCache)
                                                        viewModel.refreshAvailableCacheJobs()
                                                    },
                                                    modifier = Modifier.height(24.dp),
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF3B30)),
                                                    border = BorderStroke(1.dp, Color(0xFFFF3B30))
                                                ) {
                                                    Text("キャッシュ削除", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                // 下段: 各フェーズのチェックリストと進行
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        job.phases.forEach { phase ->
                                            val canEditPhase = canEditJob && phase.type != BatchJobPhaseType.FAST_TRIM
                                            val phaseLabel = when (phase.type) {
                                                BatchJobPhaseType.PLATE_SCAN -> "① プレート検出"
                                                BatchJobPhaseType.ROAD_SCAN -> "② 路線名検出"
                                                BatchJobPhaseType.HUD_ENCODE -> "③ HUDエンコード"
                                                BatchJobPhaseType.CONCAT_MERGE -> "④ 結合マージ"
                                                BatchJobPhaseType.FAST_TRIM -> "⚡ 高速トリミング"
                                            }
                                            val phaseStatusColor = when (phase.status) {
                                                BatchJobPhaseStatus.WAITING -> Color.Gray
                                                BatchJobPhaseStatus.RUNNING -> Color(0xFF007AFF)
                                                BatchJobPhaseStatus.COMPLETED -> Color(0xFF34C759)
                                                BatchJobPhaseStatus.FAILED -> Color(0xFFFF3B30)
                                                BatchJobPhaseStatus.SKIPPED -> Color.LightGray
                                            }
                                            
                                            Surface(
                                                color = Color(0xFFF2F2F7),
                                                shape = RoundedCornerShape(6.dp),
                                                border = BorderStroke(1.dp, if (phase.status == BatchJobPhaseStatus.RUNNING) Color(0xFF007AFF) else Color.Transparent),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(6.dp),
                                                    verticalArrangement = Arrangement.Center
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        // 編集時のみチェックボックスを表示、実行中は状況表示のみにする
                                                        if (canEditJob) {
                                                            Checkbox(
                                                                checked = phase.enabled,
                                                                onCheckedChange = { 
                                                                    phase.enabled = it
                                                                    viewModel.saveBatchQueue()
                                                                },
                                                                enabled = canEditPhase,
                                                                modifier = Modifier.size(24.dp)
                                                            )
                                                            Spacer(Modifier.width(2.dp))
                                                        } else {
                                                            // 実行中マーク
                                                            Box(
                                                                modifier = Modifier
                                                                    .padding(horizontal = 4.dp)
                                                                    .size(6.dp)
                                                                    .background(phaseStatusColor, androidx.compose.foundation.shape.CircleShape)
                                                            )
                                                            Spacer(Modifier.width(4.dp))
                                                        }
                                                        
                                                        Text(
                                                            text = phaseLabel,
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (phase.enabled) Color(0xFF1C1C1E) else Color(0xFF8E8E93)
                                                        )
                                                    }
                                                    
                                                    // 各フェーズの個別進捗表示
                                                    val progressPercent = (phase.progress * 100).toInt()
                                                    val statusText = when (phase.status) {
                                                        BatchJobPhaseStatus.WAITING -> "待機中"
                                                        BatchJobPhaseStatus.RUNNING -> "進行: $progressPercent%"
                                                        BatchJobPhaseStatus.COMPLETED -> "完了"
                                                        BatchJobPhaseStatus.FAILED -> "失敗"
                                                        BatchJobPhaseStatus.SKIPPED -> "スキップ"
                                                    }
                                                    
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(statusText, fontSize = 8.sp, color = phaseStatusColor)
                                                    }
                                                    
                                                    // 進行中のフェーズにはプログレスバーをフェーズ内に表示
                                                    if (phase.status == BatchJobPhaseStatus.RUNNING) {
                                                        LinearProgressIndicator(
                                                            progress = phase.progress,
                                                            modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 1.dp),
                                                            color = Color(0xFF007AFF),
                                                            backgroundColor = Color(0xFFD1D1D6)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                // ジョブ全体の進捗バー (ジョブ進行中のみ表示)
                                if (job.status == BatchJobStatus.RUNNING) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("ジョブ処理状況", fontSize = 8.sp, color = Color(0xFF007AFF), fontWeight = FontWeight.Bold)
                                            Text("${(job.progress * 100).toInt()}%", fontSize = 8.sp, color = Color(0xFF007AFF), fontWeight = FontWeight.Bold)
                                        }
                                        LinearProgressIndicator(
                                            progress = job.progress,
                                            modifier = Modifier.fillMaxWidth().height(4.dp),
                                            color = Color(0xFF007AFF),
                                            backgroundColor = Color(0xFFE5E5EA)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                Divider(color = Color(0xFFE5E5EA))
                
                // 下部アクションボタン
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (viewModel.isBatchRunning) {
                        Button(
                            onClick = {
                                viewModel.isCanceled = true
                                viewModel.batchStatusText = "キャンセル中..."
                            },
                            modifier = Modifier.weight(1f).height(40.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFFF3B30), contentColor = Color.White)
                        ) {
                            Text("処理を停止", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                viewModel.dismissBatchConfirmDialog("cancel-button")
                            },
                            modifier = Modifier.weight(1f).height(40.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1C1C1E))
                        ) {
                            Text("閉じる", fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                // バッチ処理を開始してもダイアログを閉じず、進行確認画面に切り替える
                                viewModel.prepareBatchQueueForStart()
                                scope.launch(Dispatchers.Main) {
                                    runBatchJobs(
                                        viewModel = viewModel,
                                        outputDir = outputDir,
                                        moveOutputToSource = moveOutputToSource,
                                        onProgressUpdate = {}
                                    )
                                }
                            },
                            modifier = Modifier.weight(2f).height(40.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF007AFF), contentColor = Color.White)
                        ) {
                            Text(
                                text = "バッチ処理を開始",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DialogBadge(text: String, backgroundColor: Color, textColor: Color) {
    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
