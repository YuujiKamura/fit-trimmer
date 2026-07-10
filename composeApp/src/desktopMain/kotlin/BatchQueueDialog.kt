import androidx.compose.animation.*

import androidx.compose.animation.core.*

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

import androidx.compose.ui.draw.alpha

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.text.TextStyle

import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.dp

import androidx.compose.ui.unit.sp

import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.launch

import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import components.VideoPreviewArea
import fit.TelemetryPoint
import fit.HudSettings
import fit.HudConfig
import fit.DynamicRendererProxy
import androidx.compose.ui.text.rememberTextMeasurer
import java.io.File
import kotlinx.coroutines.delay
import viewmodel.AppViewModel

import viewmodel.BatchJobStatus

import viewmodel.BatchJobPhaseStatus

import viewmodel.BatchJobPhaseType

import utils.openFolderInExplorer



@OptIn(ExperimentalAnimationApi::class)

@Composable

fun BatchQueueDialog(

    viewModel: AppViewModel,

    outputDir: String,

    moveOutputToSource: Boolean,

    scope: CoroutineScope

) {

    // ダイアログ全体にふわっとズームするアニメーションを適用

    AnimatedVisibility(

        visible = viewModel.showBatchConfirmDialog,

        enter = fadeIn(animationSpec = tween(250, easing = EaseOutQuad)) + 

                scaleIn(initialScale = 0.96f, animationSpec = tween(250, easing = EaseOutQuad)),

        exit = fadeOut(animationSpec = tween(200, easing = EaseInQuad)) + 

               scaleOut(targetScale = 0.96f, animationSpec = tween(200, easing = EaseInQuad))

    ) {

        // パルス（点滅）アニメーションの定義（実行中のフェーズやジョブ枠線の鼓動感に使用）        // プレビュー状態管理
        var selectedJobIdx by remember { mutableStateOf(0) }
        val selectedJob = viewModel.batchQueue.getOrNull(selectedJobIdx)
        val previewPlayerState = rememberVideoPlayerState()
        var previewTimeMs by remember { mutableStateOf(0L) }
        var isSeeking by remember { mutableStateOf(false) }
        var seekTargetTimeMs by remember { mutableStateOf(0L) }
        
        var selectedTelemetryPoints by remember { mutableStateOf<List<TelemetryPoint>>(emptyList()) }
        var selectedTrimmedPoints by remember { mutableStateOf<List<TelemetryPoint>>(emptyList()) }
        
        LaunchedEffect(selectedJob?.fitPath, selectedJob?.videoStartUtc, selectedJob?.timeOffsetMillis, selectedJob?.trimStartSeconds, selectedJob?.trimEndSeconds) {
            val job = selectedJob
            if (job == null) {
                selectedTelemetryPoints = emptyList()
                selectedTrimmedPoints = emptyList()
                return@LaunchedEffect
            }
            
            val fitFile = File(job.fitPath)
            if (fitFile.exists()) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val bytes = fitFile.readBytes()
                        val parser = fit.FitParser(bytes)
                        parser.parse()
                        val points = parser.getTelemetry()
                        selectedTelemetryPoints = points
                        
                        val startTime = try { java.time.Instant.parse(job.videoStartUtc) } catch(e: Exception) { java.time.Instant.EPOCH }
                        val fitEpoch = java.time.Instant.parse("1989-12-31T00:00:00Z").epochSecond
                        val startUtcSeconds = startTime.toEpochMilli() / 1000.0 + job.timeOffsetMillis / 1000.0
                        
                        val videoStartFit = startUtcSeconds + job.trimStartSeconds - fitEpoch
                        val videoEndFit = startUtcSeconds + job.trimEndSeconds - fitEpoch
                        
                        selectedTrimmedPoints = points.filter { it.timestamp in videoStartFit..videoEndFit }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else {
                selectedTelemetryPoints = emptyList()
                selectedTrimmedPoints = emptyList()
            }
        }
        
        LaunchedEffect(selectedJob?.videoPath) {
            val path = selectedJob?.videoPath ?: ""
            if (path.isNotEmpty() && File(path).exists()) {
                previewPlayerState.openUri(path)
                val startMs = ((selectedJob?.trimStartSeconds ?: 0.0) * 1000).toLong()
                previewPlayerState.seekTo(startMs.toFloat())
                previewTimeMs = 0L // set relative time to 0L
            }
        }
        
        DisposableEffect(Unit) {
            onDispose {
                previewPlayerState.dispose()
            }
        }



        val infiniteTransition = rememberInfiniteTransition()

        val pulseAlpha by infiniteTransition.animateFloat(

            initialValue = 0.4f,

            targetValue = 1.0f,

            animationSpec = infiniteRepeatable(

                animation = tween(1000, easing = FastOutSlowInEasing),

                repeatMode = RepeatMode.Reverse

            )

        )



        Box(

            modifier = Modifier

                .fillMaxSize()

                .background(Color.Black.copy(alpha = 0.5f))

                .clickable(enabled = true, onClick = {}),

            contentAlignment = Alignment.Center

        ) {

            Card(
                modifier = Modifier
                    .width(1300.dp)
                    .fillMaxHeight(0.96f)
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = 12.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1.3f).fillMaxHeight(),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                    // ヘッダー

                    Text(

                        text = if (viewModel.isBatchRunning) "エンコード実行中" else "エンコードジョブ一覧と進行管理",

                        fontSize = 22.sp,

                        fontWeight = FontWeight.Bold,

                        color = Color(0xFF007AFF),

                        modifier = Modifier.align(Alignment.CenterHorizontally)

                    )

                    

                    Text(

                        text = if (viewModel.isBatchRunning) 

                            "現在バックグラウンドでエンコード処理を実行しています。この画面のまま進行状況を確認できます。" 

                        else 

                            "以下のエンコードジョブ（計 ${viewModel.batchQueue.size} 件）の進行管理とフェーズのオンオフを設定できます。",

                        fontSize = 14.sp,

                        color = Color(0xFF1C1C1E)

                    )

                    

                    Divider(color = Color(0xFFE5E5EA), modifier = Modifier.padding(vertical = 4.dp))

                    

                    // 全体進捗バー (実行中のみ表示、スムーズに伸びるアニメーション付き)

                    if (viewModel.isBatchRunning) {

                        val completedCount = viewModel.batchQueue.count { it.status == BatchJobStatus.COMPLETED }

                        val totalCount = viewModel.batchQueue.size

                        val overallProgress = if (totalCount > 0) completedCount.toFloat() / totalCount.toFloat() else 0f

                        

                        // 進捗遷移をアニメーション化

                        val overallProgressAnimated by animateFloatAsState(

                            targetValue = overallProgress,

                            animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy)

                        )

                        

                        Surface(

                            color = Color(0xFFF2F2F7),

                            shape = RoundedCornerShape(10.dp),

                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)

                        ) {

                            Column(

                                modifier = Modifier.padding(16.dp),

                                verticalArrangement = Arrangement.spacedBy(8.dp)

                            ) {

                                Row(

                                    modifier = Modifier.fillMaxWidth(),

                                    horizontalArrangement = Arrangement.SpaceBetween,

                                    verticalAlignment = Alignment.CenterVertically

                                ) {

                                    val parsedTop = parseBatchStatusText(viewModel.batchStatusText)

                                    val simplifiedTopText = if (parsedTop.isParsed) {

                                        val jobPrefix = viewModel.batchStatusText.substringBefore("]", "") + "]"

                                        val action = parsedTop.actionText?.substringBefore(":") ?: "HUDエンコード"

                                        "$jobPrefix $action 中"

                                    } else {

                                        viewModel.batchStatusText

                                    }

                                    Text(

                                        text = simplifiedTopText,

                                        fontSize = 14.sp,

                                        fontWeight = FontWeight.Bold,

                                        color = Color(0xFF007AFF),

                                        modifier = Modifier.alpha(pulseAlpha) // 進行状況テキストをパルス点滅

                                    )

                                    Text(

                                        text = "全体進捗: $completedCount / $totalCount 件完了 (${(overallProgress * 100).toInt()}%)",

                                        fontSize = 13.sp,

                                        fontWeight = FontWeight.Bold,

                                        color = Color(0xFF636366)

                                    )

                                }

                                LinearProgressIndicator(

                                    progress = overallProgressAnimated,

                                    modifier = Modifier.fillMaxWidth().height(10.dp),

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

                            .border(1.dp, Color(0xFFE5E5EA), RoundedCornerShape(10.dp))

                            .background(Color(0xFFF9F9F9))

                            .padding(12.dp)

                    ) {

                        LazyColumn(

                            verticalArrangement = Arrangement.spacedBy(12.dp)

                        ) {

                            items(viewModel.batchQueue.size) { idx ->

                                val job = viewModel.batchQueue[idx]

                                val canEditJob = !viewModel.isBatchRunning

                                var entryNameDraft by remember(job.id, job.entryName) { mutableStateOf(job.entryName) }

                                

                                val isSelected = idx == selectedJobIdx
                                val cardBgColor = if (job.status == BatchJobStatus.RUNNING) {
                                    Color(0xFFF2F8FF)
                                } else if (isSelected) {
                                    Color(0xFFF2F8FF).copy(alpha = 0.5f)
                                } else {
                                    Color.White
                                }
                                // 実行中のカードの枠線をパルス点滅させてアクティブ感を強調
                                val cardBorderColor = if (job.status == BatchJobStatus.RUNNING) {
                                    Color(0xFF007AFF).copy(alpha = pulseAlpha)
                                } else if (isSelected) {
                                    Color(0xFF007AFF)
                                } else {
                                    Color(0xFFE5E5EA)
                                }
                                
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(cardBgColor, RoundedCornerShape(10.dp))
                                        .border(if (isSelected) 2.dp else 1.dp, cardBorderColor, RoundedCornerShape(10.dp))
                                        .clickable { selectedJobIdx = idx }
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

                                            Row(

                                                modifier = Modifier.weight(1f),

                                                horizontalArrangement = Arrangement.spacedBy(8.dp),

                                                verticalAlignment = Alignment.CenterVertically

                                            ) {

                                                OutlinedTextField(

                                                    value = entryNameDraft,

                                                    onValueChange = { entryNameDraft = it },

                                                    enabled = true,

                                                    singleLine = true,

                                                    textStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),

                                                    modifier = Modifier.weight(1f).height(52.dp),

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

                                                    modifier = Modifier.height(38.dp),

                                                    contentPadding = PaddingValues(horizontal = 12.dp)

                                                ) {

                                                    Text("保存", fontSize = 12.sp, fontWeight = FontWeight.Bold)

                                                }

                                            }

                                        } else {

                                            Text(

                                                text = job.entryName,

                                                fontSize = 15.sp,

                                                fontWeight = FontWeight.Bold,

                                                color = if (job.status == BatchJobStatus.RUNNING) Color(0xFF007AFF) else Color(0xFF1C1C1E),

                                                modifier = Modifier.weight(1f).padding(vertical = 4.dp)

                                            )

                                        }

                                        

                                        Spacer(Modifier.width(16.dp))

                                        

                                        // ジョブ自体のステータスバッジ

                                        val jobStatusColor = when (job.status) {

                                            BatchJobStatus.WAITING -> Color.Gray

                                            BatchJobStatus.RUNNING -> Color(0xFF007AFF)

                                            BatchJobStatus.COMPLETED -> Color(0xFF34C759)

                                            BatchJobStatus.FAILED -> Color(0xFFFF3B30)

                                        }

                                        Surface(

                                            color = jobStatusColor.copy(alpha = 0.15f),

                                            shape = RoundedCornerShape(6.dp)

                                        ) {

                                            Text(

                                                text = when(job.status) {

                                                    BatchJobStatus.WAITING -> "待機中"

                                                    BatchJobStatus.RUNNING -> "処理中 (${(job.progress * 100).toInt()}%)"

                                                    BatchJobStatus.COMPLETED -> "完了"

                                                    BatchJobStatus.FAILED -> "失敗"

                                                },

                                                color = jobStatusColor,

                                                fontSize = 12.sp,

                                                fontWeight = FontWeight.Bold,

                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)

                                            )

                                        }

                                        

                                        // 操作ボタン

                                        if (canEditJob) {

                                            Spacer(Modifier.width(12.dp))

                                            Row(

                                                horizontalArrangement = Arrangement.spacedBy(6.dp),

                                                verticalAlignment = Alignment.CenterVertically

                                            ) {

                                                OutlinedButton(

                                                    onClick = { viewModel.moveBatchJobUp(job.id) },

                                                    enabled = idx > 0,

                                                    modifier = Modifier.size(34.dp),

                                                    contentPadding = PaddingValues(0.dp)

                                                ) {

                                                    Icon(Icons.Filled.KeyboardArrowUp, null, modifier = Modifier.size(20.dp))

                                                }

                                                OutlinedButton(

                                                    onClick = { viewModel.moveBatchJobDown(job.id) },

                                                    enabled = idx < viewModel.batchQueue.lastIndex,

                                                    modifier = Modifier.size(34.dp),

                                                    contentPadding = PaddingValues(0.dp)

                                                ) {

                                                    Icon(Icons.Filled.KeyboardArrowDown, null, modifier = Modifier.size(20.dp))

                                                }

                                                OutlinedButton(

                                                    onClick = { viewModel.removeFromBatchQueue(job.id) },

                                                    modifier = Modifier.size(34.dp),

                                                    contentPadding = PaddingValues(0.dp),

                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE02424)),

                                                    border = BorderStroke(1.dp, Color(0xFFE02424))

                                                ) {

                                                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))

                                                }

                                            }

                                        }

                                    }

                                    

                                    // 中段: 加工内容バッジと詳細情報

                                    Row(

                                        modifier = Modifier.fillMaxWidth(),

                                        horizontalArrangement = Arrangement.spacedBy(8.dp),

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

                                            fontSize = 12.sp,

                                            color = Color(0xFF636366),

                                            fontWeight = FontWeight.Bold

                                        )

                                    }

                                    

                                    // エラーメッセージ

                                    if (job.errorMessage != null) {

                                        Text(

                                            text = "❌ エラー: ${job.errorMessage}",

                                            color = Color(0xFFFF3B30),

                                            fontSize = 12.sp,

                                            fontWeight = FontWeight.Bold,

                                            modifier = Modifier

                                                .fillMaxWidth()

                                                .background(Color(0xFFFF3B30).copy(alpha = 0.08f), RoundedCornerShape(6.dp))

                                                .padding(10.dp)

                                        )

                                    }

                                    

                                    // キャッシュサルベージ支援UI

                                    if (canEditJob) {

                                        val availableJobs = remember(job.videoPath) { fit.CacheJobManager.getInstance().scanJobs(job.videoPath) }

                                        if (availableJobs.isNotEmpty()) {

                                            val jobCache = availableJobs.first()

                                            Row(

                                                modifier = Modifier

                                                    .fillMaxWidth()

                                                    .background(Color(0xFFFFF9E6), RoundedCornerShape(6.dp))

                                                    .border(1.dp, Color(0xFFFFD60A), RoundedCornerShape(6.dp))

                                                    .padding(10.dp),

                                                verticalAlignment = Alignment.CenterVertically,

                                                horizontalArrangement = Arrangement.SpaceBetween

                                            ) {

                                                Text("⚠️ 未完了キャッシュ (TSパーツ: ${jobCache.partsCount}) があります", fontSize = 12.sp, color = Color(0xFFFF9500), fontWeight = FontWeight.Bold)

                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {

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

                                                        modifier = Modifier.height(28.dp),

                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),

                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9500)),

                                                        border = BorderStroke(1.dp, Color(0xFFFF9500))

                                                    ) {

                                                        Text("マージ復元モードにする", fontSize = 11.sp, fontWeight = FontWeight.Bold)

                                                    }

                                                    OutlinedButton(

                                                        onClick = {

                                                            viewModel.deleteCacheJob(jobCache)

                                                        },

                                                        modifier = Modifier.height(28.dp),

                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),

                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF3B30)),

                                                        border = BorderStroke(1.dp, Color(0xFFFF3B30))

                                                    ) {

                                                        Text("キャッシュ削除", fontSize = 11.sp, fontWeight = FontWeight.Bold)

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

                                            horizontalArrangement = Arrangement.spacedBy(10.dp)

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

                                                

                                                val borderStroke = if (phase.status == BatchJobPhaseStatus.RUNNING) {

                                                    BorderStroke(1.5.dp, Color(0xFF007AFF).copy(alpha = pulseAlpha))

                                                } else {

                                                    BorderStroke(1.dp, Color.Transparent)

                                                }

                                                

                                                Surface(

                                                    color = Color(0xFFF2F2F7),

                                                    shape = RoundedCornerShape(8.dp),

                                                    border = borderStroke,

                                                    modifier = Modifier.weight(1f)

                                                ) {

                                                    Column(

                                                        modifier = Modifier.padding(8.dp),

                                                        verticalArrangement = Arrangement.Center

                                                    ) {

                                                        Row(

                                                            verticalAlignment = Alignment.CenterVertically

                                                        ) {

                                                            if (canEditJob) {

                                                                Checkbox(

                                                                    checked = phase.enabled,

                                                                    onCheckedChange = { 

                                                                        phase.enabled = it

                                                                        viewModel.saveBatchQueue()

                                                                    },

                                                                    enabled = canEditPhase,

                                                                    modifier = Modifier.size(28.dp)

                                                                )

                                                                Spacer(Modifier.width(4.dp))

                                                            } else {

                                                                // 実行中マークを点滅させて活動感を演出

                                                                Box(

                                                                    modifier = Modifier

                                                                        .padding(horizontal = 6.dp)

                                                                        .size(8.dp)

                                                                        .background(

                                                                            color = if (phase.status == BatchJobPhaseStatus.RUNNING) phaseStatusColor.copy(alpha = pulseAlpha) else phaseStatusColor,

                                                                            shape = androidx.compose.foundation.shape.CircleShape

                                                                        )

                                                                )

                                                                Spacer(Modifier.width(6.dp))

                                                            }

                                                            

                                                            Text(

                                                                text = phaseLabel,

                                                                fontSize = 12.sp,

                                                                fontWeight = FontWeight.Bold,

                                                                color = if (phase.enabled) Color(0xFF1C1C1E) else Color(0xFF8E8E93)

                                                            )

                                                        }

                                                        

                                                        val progressPercent = (phase.progress * 100).toInt()

                                                        val statusText = when (phase.status) {

                                                            BatchJobPhaseStatus.WAITING -> "待機中"

                                                            BatchJobPhaseStatus.RUNNING -> "進行: $progressPercent%"

                                                            BatchJobPhaseStatus.COMPLETED -> "完了"

                                                            BatchJobPhaseStatus.FAILED -> "失敗"

                                                            BatchJobPhaseStatus.SKIPPED -> "スキップ"

                                                        }

                                                        

                                                        Row(

                                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),

                                                            horizontalArrangement = Arrangement.SpaceBetween,

                                                            verticalAlignment = Alignment.CenterVertically

                                                        ) {

                                                            Text(statusText, fontSize = 11.sp, color = phaseStatusColor, fontWeight = FontWeight.Bold)

                                                        }

                                                        

                                                        if (phase.status == BatchJobPhaseStatus.RUNNING) {

                                                            val phaseProgressAnimated by animateFloatAsState(targetValue = phase.progress)

                                                            LinearProgressIndicator(

                                                                progress = phaseProgressAnimated,

                                                                modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 2.dp),

                                                                color = Color(0xFF007AFF),

                                                                backgroundColor = Color(0xFFD1D1D6)

                                                            )

                                                        }

                                                    }

                                                }

                                            }

                                        }

                                    }

                                    

                                    // ジョブ全体の進捗バー (進行中のみ、スムーズなアニメーション付き)

                                    if (job.status == BatchJobStatus.RUNNING) {

                                        val jobProgressAnimated by animateFloatAsState(

                                            targetValue = job.progress,

                                            animationSpec = spring(stiffness = Spring.StiffnessMedium)

                                        )

                                        Column(

                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),

                                            verticalArrangement = Arrangement.spacedBy(6.dp)

                                        ) {

                                            Row(

                                                modifier = Modifier.fillMaxWidth(),

                                                horizontalArrangement = Arrangement.SpaceBetween,

                                                verticalAlignment = Alignment.CenterVertically

                                            ) {

                                                Text("ジョブ処理状況", fontSize = 11.sp, color = Color(0xFF007AFF), fontWeight = FontWeight.Bold)

                                                Text("${(job.progress * 100).toInt()}%", fontSize = 11.sp, color = Color(0xFF007AFF), fontWeight = FontWeight.Bold)

                                            }

                                            LinearProgressIndicator(

                                                progress = jobProgressAnimated,

                                                modifier = Modifier.fillMaxWidth().height(6.dp),

                                                color = Color(0xFF007AFF),

                                                backgroundColor = Color(0xFFE5E5EA)

                                            )

                                            val parsedDetails = parseBatchStatusText(viewModel.batchStatusText)

                                            if (parsedDetails.isParsed) {

                                                val partStr = parsedDetails.partInfo?.let { "$it • " } ?: ""

                                                val etaStr = parsedDetails.etaInfo?.let { "残り時間: ${it.replace("ETA: ", "")}" } ?: ""

                                                val speedStr = parsedDetails.speedInfo?.let { " (速度: $it)" } ?: ""

                                                val displayText = "$partStr$etaStr$speedStr"

                                                if (displayText.isNotEmpty()) {

                                                    Text(

                                                        text = displayText,

                                                        fontSize = 11.sp,

                                                        color = Color(0xFF636366),

                                                        modifier = Modifier.padding(vertical = 2.dp)

                                                    )

                                                }

                                            } else if (viewModel.batchStatusText.isNotEmpty()) {

                                                val displayText = viewModel.batchStatusText.substringAfter("] ").trim()

                                                if (displayText.isNotEmpty()) {

                                                    Text(

                                                        text = displayText,

                                                        fontSize = 11.sp,

                                                        color = Color(0xFF636366),

                                                        modifier = Modifier.padding(vertical = 2.dp)

                                                    )

                                                }

                                            }

                                        }

                                    }

                                }

                            }

                        }

                    }

                    

                    Divider(color = Color(0xFFE5E5EA), modifier = Modifier.padding(vertical = 4.dp))

                    

                    // 下部アクションボタン

                    Row(

                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),

                        horizontalArrangement = Arrangement.spacedBy(16.dp)

                    ) {

                        if (viewModel.isBatchRunning) {

                            Button(

                                onClick = {

                                    viewModel.isCanceled = true

                                    viewModel.batchStatusText = "キャンセル中..."

                                },

                                modifier = Modifier.weight(1f).height(44.dp),

                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFFF3B30), contentColor = Color.White)

                            ) {

                                Text("処理を停止", fontSize = 14.sp, fontWeight = FontWeight.Bold)

                            }

                        } else {

                            val hasCompletedJobs = viewModel.batchQueue.any { it.status == BatchJobStatus.COMPLETED }

                            OutlinedButton(

                                onClick = {

                                    viewModel.dismissBatchConfirmDialog("cancel-button")

                                },

                                modifier = Modifier.weight(1f).height(44.dp),

                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1C1C1E))

                            ) {

                                Text("閉じる", fontSize = 14.sp, fontWeight = FontWeight.Bold)

                            }

                            

                            if (hasCompletedJobs) {

                                OutlinedButton(

                                    onClick = {

                                        openFolderInExplorer(outputDir)

                                    },

                                    modifier = Modifier.weight(1.5f).height(44.dp),

                                    colors = ButtonDefaults.outlinedButtonColors(

                                        contentColor = Color(0xFF34C759)

                                    ),

                                    border = BorderStroke(1.5.dp, Color(0xFF34C759)),

                                    shape = RoundedCornerShape(8.dp)

                                ) {

                                    Text("出力フォルダを開く", fontSize = 14.sp, fontWeight = FontWeight.Bold)

                                }

                            }

                            

                            Button(

                                onClick = {

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

                                modifier = Modifier.weight(2f).height(44.dp),

                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF007AFF), contentColor = Color.White)

                            ) {

                                Text(
                                    text = "エンコードを開始",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    } // Left Column End

                    // Right Column (HUD & Video Layout Preview)
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "HUDレイアウトプレビュー",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF007AFF)
                        )
                        
                        if (selectedJob != null) {
                            val textMeasurer = rememberTextMeasurer()
                            val hudConfig = remember(selectedJob.settings, selectedJob.trimStartSeconds) {
                                val s = selectedJob.settings
                                fit.HudConfig(
                                    valSize = s.valSize, tightness = s.tightness, spacing = s.spacing,
                                    xOffset = s.xOffset, yOffset = s.yOffset, graphH = s.graphH, graphW = s.graphW,
                                    captionPosition = s.captionPosition,
                                    roadCaptions = s.roadCaptions,
                                    powerTrendSpanSeconds = s.powerTrendSpanSeconds,
                                    useImperialUnits = s.useImperialUnits,
                                    language = s.language,
                                    elevationGraphScope = s.elevationGraphScope,
                                    heartRateAccumulationScope = s.heartRateAccumulationScope,
                                    showSpeed = s.showSpeed,
                                    showCadence = s.showCadence,
                                    showHeartRate = s.showHeartRate,
                                    showPower = s.showPower,
                                    showWkg = s.showWkg,
                                    showPowerTrend = s.showPowerTrend,
                                    showGrade = s.showGrade,
                                    showElevation = s.showElevation,
                                    showDistanceTime = s.showDistanceTime,
                                    bodyWeightKg = s.bodyWeightKg,
                                    customCaptions = s.customCaptions,
                                    trimStartSeconds = selectedJob.trimStartSeconds,
                                    mapSizeScale = s.mapSizeScale,
                                    mapType = s.mapType,
                                    mapPosition = s.mapPosition,
                                    hudBgAlpha = s.hudBgAlpha,
                                    mapZoomScale = s.mapZoomScale,
                                    mapZoomOffset = s.mapZoomOffset,
                                    fixMapNorthUp = s.fixMapNorthUp,
                                    mapMarkerSizeScale = s.mapMarkerSizeScale,
                                    mapTextSizeScale = s.mapTextSizeScale,
                                    mapRangeMode = s.mapRangeMode,
                                    textShadowAlpha = s.textShadowAlpha,
                                    showCumulativeDistanceTime = s.showCumulativeDistanceTime
                                )
                            }
                            val rendererProxy = remember(hudConfig) { fit.DynamicRendererProxy(hudConfig) }
                            val videoLengthMs = ((selectedJob.trimEndSeconds - selectedJob.trimStartSeconds) * 1000).toLong().coerceAtLeast(1000L)
                            
                            VideoPreviewArea(
                                videoPath = selectedJob.videoPath,
                                videoLengthMs = videoLengthMs,
                                adjustedStartUtc = selectedJob.adjustedStartUtc,
                                telemetryPoints = selectedTelemetryPoints,
                                trimmedTelemetryPoints = selectedTrimmedPoints,
                                originalTelemetryPoints = selectedTelemetryPoints,
                                settings = selectedJob.settings,
                                rendererProxy = rendererProxy,
                                textMeasurer = textMeasurer,
                                playerState = previewPlayerState,
                                videoCurrentTimeMsProvider = { previewTimeMs },
                                onCurrentTimeChange = { 
                                    previewTimeMs = it
                                },
                                isSeekingProvider = { isSeeking },
                                seekTargetTimeMsProvider = { seekTargetTimeMs },
                                onSeekStart = { isSeeking = true },
                                onSeekProgress = { seekTargetTimeMs = it },
                                onSeekEnd = { 
                                    isSeeking = false
                                    previewTimeMs = it
                                    val targetPlayerMs = ((selectedJob.trimStartSeconds * 1000) + it).toFloat()
                                    previewPlayerState.seekTo(targetPlayerMs)
                                },
                                trimStartSeconds = selectedJob.trimStartSeconds,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                isEncoding = false,
                                isDetectingPlates = false
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF2F2F7)), contentAlignment = Alignment.Center) {
                                Text("プレビューするジョブを選択してください", color = Color(0xFF8E8E93))
                            }
                        }
                    }
                } // Row End
            }
        }
    }
}



@Composable

fun DialogBadge(text: String, backgroundColor: Color, textColor: Color) {

    Surface(

        color = backgroundColor,

        shape = RoundedCornerShape(6.dp),

        modifier = Modifier.padding(vertical = 2.dp)

    ) {

        Text(

            text = text,

            color = textColor,

            fontSize = 12.sp,

            fontWeight = FontWeight.Bold,

            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)

        )

    }

}





data class EncodingProgressDetails(

    val partInfo: String?,

    val actionText: String?,

    val timeInfo: String?,

    val speedInfo: String?,

    val etaInfo: String?,

    val isParsed: Boolean

)



fun parseBatchStatusText(statusText: String): EncodingProgressDetails {

    if (!statusText.contains("|")) {

        return EncodingProgressDetails(null, null, null, null, null, false)

    }

    try {

        val parts = statusText.split("|").map { it.trim() }

        if (parts.size >= 4) {

            val firstPart = parts[0]

            var partInfo: String? = null

            val partRegex = Regex("""\[Part\s+\d+/\d+\]""")

            val partMatch = partRegex.find(firstPart)

            if (partMatch != null) {

                partInfo = partMatch.value

            }

            var actionText = firstPart

            val prefixRegex = Regex("""^\[\d+/\d+\]\s*""")

            actionText = prefixRegex.replace(actionText, "")

            if (partInfo != null) {

                actionText = actionText.replace(partInfo, "").trim()

            }

            val timeInfo = parts[1]

            val speedInfo = parts[2]

            val etaInfo = parts[3]

            return EncodingProgressDetails(

                partInfo = partInfo?.replace("[", "")?.replace("]", ""),

                actionText = actionText,

                timeInfo = timeInfo,

                speedInfo = speedInfo.replace("Speed: ", ""),

                etaInfo = etaInfo,

                isParsed = true

            )

        }

    } catch (e: Exception) {

    }

    return EncodingProgressDetails(null, null, null, null, null, false)

}

