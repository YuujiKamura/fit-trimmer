# Low-Resolution Preview Proxy Implementation Plan

> **For Gemini:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Automatically generate and use a lightweight local 360p video proxy for preview and seeking in the Compose GUI when remote Google Drive videos are loaded, preventing seeking latency.

**Architecture:** Detect DriveFS paths, generate a proxy asynchronously using FFmpeg preset `ultrafast` in the background, cache proxies locally by hashing input paths, evict cache when size > 2GB or age > 7 days, and hot-swap the video source in the player.

**Tech Stack:** Kotlin, Compose Multiplatform, Coroutines, Java ProcessBuilder (FFmpeg).

---

### Task 1: Proxy Cache & Generation Utilities

**Files:**
- Modify: [composeApp/src/desktopMain/kotlin/utils/VideoUtils.kt](file:///C:/Users/yuuji/fit-trimmer/composeApp/src/desktopMain/kotlin/utils/VideoUtils.kt)

**Step 1: Write helper function to compute path MD5 hash**
Define a private helper function to hash the original video path into a unique filename:
```kotlin
private fun getMd5Hash(str: String): String {
    val md = java.security.MessageDigest.getInstance("MD5")
    val bytes = md.digest(str.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
```

**Step 2: Define Proxy Cache location and LRU eviction policy**
Create a directory named `fit_trimmer_proxies` in the temporary directory. Provide a cleanup utility:
```kotlin
fun getProxyFileForVideo(videoPath: String): File {
    val tempDir = System.getProperty("java.io.tmpdir")
    val proxiesDir = File(tempDir, "fit_trimmer_proxies")
    if (!proxiesDir.exists()) {
        proxiesDir.mkdirs()
    }
    val hash = getMd5Hash(videoPath)
    return File(proxiesDir, "${hash}_proxy.mp4")
}

fun cleanOldProxies() {
    try {
        val tempDir = System.getProperty("java.io.tmpdir")
        val proxiesDir = File(tempDir, "fit_trimmer_proxies")
        if (!proxiesDir.exists()) return
        
        val files = proxiesDir.listFiles()?.toList() ?: return
        
        // 1. Delete files older than 7 days
        val oneWeekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        for (f in files) {
            if (f.lastModified() < oneWeekAgo) {
                f.delete()
            }
        }
        
        // 2. LRU size check (limit total folder size to 2 GB = 2 * 1024 * 1024 * 1024 bytes)
        val refreshedFiles = proxiesDir.listFiles()?.toMutableList() ?: return
        var totalSize = refreshedFiles.sumOf { it.length() }
        val sizeLimit = 2L * 1024 * 1024 * 1024
        
        if (totalSize > sizeLimit) {
            refreshedFiles.sortBy { it.lastModified() } // oldest first
            while (totalSize > sizeLimit && refreshedFiles.isNotEmpty()) {
                val oldest = refreshedFiles.removeAt(0)
                val len = oldest.length()
                if (oldest.delete()) {
                    totalSize -= len
                }
            }
        }
    } catch (e: Exception) {
        println("DEBUG: Error cleaning old proxies: ${e.message}")
    }
}
```

**Step 3: Implement asynchronous FFmpeg proxy generator**
Implement `generateProxyVideo` which invokes FFmpeg with `-preset ultrafast` and parses progress:
```kotlin
suspend fun generateProxyVideo(
    videoPath: String,
    ffmpegPath: String,
    videoDurationSec: Double,
    onProgress: (Float) -> Unit
): String? = withContext(Dispatchers.IO) {
    val proxyFile = getProxyFileForVideo(videoPath)
    if (proxyFile.exists() && proxyFile.length() > 0) {
        // Cache hit
        try {
            proxyFile.setLastModified(System.currentTimeMillis())
        } catch (e: Exception) {}
        onProgress(1.0f)
        return@withContext proxyFile.absolutePath
    }
    
    val tempProxyFile = File(proxyFile.absolutePath + ".tmp")
    if (tempProxyFile.exists()) {
        tempProxyFile.delete()
    }
    
    try {
        val pb = ProcessBuilder(
            ffmpegPath, "-y",
            "-i", videoPath,
            "-vf", "scale=-2:360",
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-crf", "32",
            "-c:a", "aac",
            "-b:a", "64k",
            "-threads", "4",
            tempProxyFile.absolutePath
        )
        pb.redirectErrorStream(true)
        val p = pb.start()
        
        val job = coroutineContext[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { 
            p.destroyForcibly()
            if (tempProxyFile.exists()) tempProxyFile.delete()
        }
        
        val reader = p.inputStream.bufferedReader()
        val timeRegex = Regex("""time=(\d{2}):(\d{2}):(\d{2})\.(\d{2})""")
        
        var line = reader.readLine()
        while (line != null) {
            val match = timeRegex.find(line)
            if (match != null && videoDurationSec > 0) {
                val h = match.groupValues[1].toInt()
                val m = match.groupValues[2].toInt()
                val s = match.groupValues[3].toInt()
                val elapsed = h * 3600 + m * 60 + s
                val progress = (elapsed.toFloat() / videoDurationSec.toFloat()).coerceIn(0f, 1f)
                onProgress(progress * 0.95f) // Reserve 5% for completion
            }
            line = reader.readLine()
        }
        
        val exitCode = p.waitFor()
        handle?.dispose()
        
        if (exitCode == 0 && tempProxyFile.exists() && tempProxyFile.length() > 0) {
            if (tempProxyFile.renameTo(proxyFile)) {
                onProgress(1.0f)
                return@withContext proxyFile.absolutePath
            }
        }
    } catch (e: Exception) {
        println("DEBUG: Error generating proxy video: ${e.message}")
    } finally {
        if (tempProxyFile.exists()) {
            tempProxyFile.delete()
        }
    }
    null
}
```

---

### Task 2: ViewModel State Integration

**Files:**
- Modify: [composeApp/src/desktopMain/kotlin/viewmodel/AppViewModel.kt](file:///C:/Users/yuuji/fit-trimmer/composeApp/src/desktopMain/kotlin/viewmodel/AppViewModel.kt)

**Step 1: Add proxy states to AppViewModel**
```kotlin
    // Proxy states
    var isGeneratingProxy by mutableStateOf(false)
    var proxyProgress by mutableStateOf(0f)
    var proxyVideoPath by mutableStateOf<String?>(null)
```

**Step 2: Reset proxy states on video path change**
Inside `var videoPath` setter, reset the proxy states:
```kotlin
    var videoPath: String
        get() = _videoPath
        set(value) {
            if (_videoPath != value) {
                _videoPath = value
                trimStartSeconds = 0.0
                trimEndSeconds = 0.0
                splitPoints = emptyList()
                isGeneratingProxy = false
                proxyProgress = 0f
                proxyVideoPath = null
            }
        }
```

---

### Task 3: Background Proxy Generation Pipeline

**Files:**
- Modify: [composeApp/src/desktopMain/kotlin/Main.kt](file:///C:/Users/yuuji/fit-trimmer/composeApp/src/desktopMain/kotlin/Main.kt)

**Step 1: Launch background proxy generator coroutine**
In `LaunchedEffect(videoPath)` around line 410, trigger proxy generation for Google Drive paths:
```kotlin
        if (videoPath.isNotEmpty() && File(videoPath).exists()) {
            val originalFile = File(videoPath)
            var targetVideoPath = videoPath
            
            // Trigger background proxy creation if it is a Drive video
            val isDrive = utils.isGoogleDrivePath(videoPath)
            if (isDrive) {
                scope.launch {
                    try {
                        viewModel.isGeneratingProxy = true
                        viewModel.proxyProgress = 0f
                        viewModel.proxyVideoPath = null
                        
                        utils.cleanOldProxies()
                        
                        val durationMs = utils.getVideoDuration(videoPath) ?: 0L
                        val durationSec = durationMs / 1000.0
                        val ffmpegPath = fit.findFfmpegPath()
                        
                        val pPath = utils.generateProxyVideo(
                            videoPath = videoPath,
                            ffmpegPath = ffmpegPath,
                            videoDurationSec = durationSec,
                            onProgress = { prog ->
                                viewModel.proxyProgress = prog
                            }
                        )
                        
                        withContext(Dispatchers.Main) {
                            viewModel.proxyVideoPath = pPath
                            viewModel.isGeneratingProxy = false
                        }
                    } catch (e: Exception) {
                        viewModel.isGeneratingProxy = false
                        e.printStackTrace()
                    }
                }
            }
```

---

### Task 4: Player Hot-Swapping & UI Overlay

**Files:**
- Modify: [composeApp/src/desktopMain/kotlin/components/VideoPreviewArea.kt](file:///C:/Users/yuuji/fit-trimmer/composeApp/src/desktopMain/kotlin/components/VideoPreviewArea.kt)

**Step 1: Support hot-swapping video source without losing playhead position**
Update `LaunchedEffect` in `VideoPreviewArea.kt` to trigger on BOTH `videoPath` and `viewModel.proxyVideoPath`:
```kotlin
    val activePath = viewModel.proxyVideoPath ?: videoPath

    LaunchedEffect(activePath) {
        println("DEBUG: VideoPreviewArea LaunchedEffect(activePath) triggered with path: $activePath")
        if (activePath.isNotEmpty() && File(activePath).exists()) {
            val originalFile = File(activePath)
            var targetVideoPath = activePath
            
            // Create junction only if it's NOT the local proxy path AND is on H:\ Drive
            if (activePath.startsWith("H:\\", ignoreCase = true) && viewModel.proxyVideoPath == null) {
                try {
                    val tempDir = System.getProperty("java.io.tmpdir")
                    val junctionFolder = File(tempDir, "fit_trimmer_video_junction")
                    if (junctionFolder.exists()) {
                        ProcessBuilder("cmd.exe", "/c", "rmdir", junctionFolder.absolutePath).start().waitFor()
                    }
                    val parentDir = originalFile.parentFile.absolutePath
                    val pb = ProcessBuilder("cmd.exe", "/c", "mklink", "/j", junctionFolder.absolutePath, parentDir)
                    val p = pb.start()
                    p.waitFor()
                    if (junctionFolder.exists()) {
                        targetVideoPath = File(junctionFolder, originalFile.name).absolutePath
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            println("DEBUG: VideoPreviewArea calling openUri: $targetVideoPath")
            val currentPos = playerState.currentTime // Preserve playhead location
            playerState.openUri(targetVideoPath)
            if (currentPos > 0.0) {
                // Wait for player to load the new video, then restore playhead position
                kotlinx.coroutines.delay(100)
                playerState.seekTo(currentPos.toFloat())
            }
        } else {
            playerState.stop()
        }
    }
```

**Step 2: Add progress indicator overlay to VideoPreviewArea**
Inside `Box` of the video preview surface, render a semi-transparent status overlay at the bottom if `viewModel.isGeneratingProxy == true`:
```kotlin
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .aspectRatio(16f / 9f)
                .background(Color.Black, shape = RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFFE5E5EA), shape = RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
        ) {
            if (videoPath.isNotEmpty()) {
                VideoPlayerSurface(
                    playerState = playerState,
                    modifier = Modifier.fillMaxSize()
                )
                
                // Low-Res Proxy Generation Progress Overlay
                if (viewModel.isGeneratingProxy) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(vertical = 6.dp, horizontal = 12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                progress = viewModel.proxyProgress,
                                modifier = Modifier.size(14.dp),
                                color = Color(0xFF007AFF),
                                strokeWidth = 2.dp
                            )
                            Text(
                                "🔄 Generating low-res preview proxy... (${(viewModel.proxyProgress * 100).toInt()}%)",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
```

---
