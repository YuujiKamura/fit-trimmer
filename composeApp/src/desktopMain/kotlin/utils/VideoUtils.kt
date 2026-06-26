package utils

import java.io.File
import java.awt.FileDialog
import java.awt.Frame
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import fit.findFfmpegPath
import mp4.Mp4Parser

fun pickFile(title: String, extensions: List<String>): String? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.isVisible = true
    return if (dialog.file != null) File(dialog.directory, dialog.file).absolutePath else null
}

fun pickFolder(title: String): String? {
    val chooser = javax.swing.JFileChooser()
    chooser.dialogTitle = title
    chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.absolutePath
    } else null
}

suspend fun extractPreviewFrame(
    ffmpegPath: String,
    videoPath: String,
    timeMs: Long,
    scaleWidth: Int
): Result<ImageBitmap> = withContext(Dispatchers.IO) {
    val timeSec = timeMs / 1000.0
    val pb = ProcessBuilder(
        ffmpegPath, "-y",
        "-loglevel", "quiet",
        "-ss", String.format(java.util.Locale.US, "%.3f", timeSec),
        "-i", videoPath,
        "-vframes", "1",
        "-vf", "scale=$scaleWidth:-1",
        "-f", "image2pipe",
        "-vcodec", "mjpeg",
        "-"
    )
    pb.redirectError(ProcessBuilder.Redirect.DISCARD)
    
    var proc: Process? = null
    val job = coroutineContext[kotlinx.coroutines.Job]
    val handle = job?.invokeOnCompletion {
        proc?.destroyForcibly()
    }
    
    try {
        proc = pb.start()
        val bytes = proc.inputStream.use { it.readBytes() }
        val exitCode = proc.waitFor()
        if (exitCode == 0 && bytes.isNotEmpty()) {
            val skiaImage = org.jetbrains.skia.Image.makeFromEncoded(bytes)
                ?: throw Exception("Failed to decode image bytes with Skia")
            val bitmap = skiaImage.toComposeImageBitmap()
            Result.success(bitmap)
        } else {
            Result.failure(Exception("FFmpeg exited with code $exitCode"))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        proc?.destroyForcibly()
        try {
            proc?.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
        } catch (ioe: Exception) {}
        throw e
    } catch (e: Exception) {
        proc?.destroyForcibly()
        try {
            proc?.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
        } catch (ioe: Exception) {}
        Result.failure(e)
    } finally {
        handle?.dispose()
        if (proc?.isAlive == true) {
            proc.destroyForcibly()
            try {
                proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
            } catch (ioe: Exception) {}
        }
    }
}

suspend fun getVideoDuration(videoPath: String): Long? = withContext(Dispatchers.IO) {
    var attempt = 1
    val maxAttempts = 3
    while (attempt <= maxAttempts) {
        try {
            val ffmpegPath = findFfmpegPath()
            val pbDur = ProcessBuilder(ffmpegPath, "-i", videoPath)
            pbDur.redirectErrorStream(true)
            val pDur = pbDur.start()
            
            val job = coroutineContext[kotlinx.coroutines.Job]
            val handle = job?.invokeOnCompletion { pDur.destroyForcibly() }
            try {
                var output = ""
                val readThread = kotlin.concurrent.thread {
                    try {
                        output = pDur.inputStream.bufferedReader().readText()
                    } catch (e: Exception) {}
                }
                
                val finished = pDur.waitFor(6, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    println("DEBUG: getVideoDuration timed out (Attempt $attempt/$maxAttempts)")
                    pDur.destroyForcibly()
                    readThread.interrupt()
                } else {
                    readThread.join(1000)
                    val durRegex = Regex("""Duration:\s*(\d+):(\d+):(\d+)\.(\d+)""")
                    val durMatch = durRegex.find(output)
                    if (durMatch != null) {
                        val h = durMatch.groupValues[1].toInt()
                        val m = durMatch.groupValues[2].toInt()
                        val s = durMatch.groupValues[3].toInt()
                        val ms = durMatch.groupValues[4].padEnd(3, '0').take(3).toInt()
                        val dur = (h * 3600 + m * 60 + s) * 1000L + ms
                        println("DEBUG: getVideoDuration (FFmpeg) success duration=$dur")
                        return@withContext dur
                    }
                }
            } finally {
                handle?.dispose()
                if (pDur.isAlive) {
                    pDur.destroyForcibly()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("DEBUG: Exception during video duration parse (Attempt $attempt/$maxAttempts): ${e.message}")
        }
        attempt++
        if (attempt <= maxAttempts) {
            delay(1000)
        }
    }
    null
}

private fun isGoogleDrivePath(path: String): Boolean {
    val normalized = path.replace("\\", "/").lowercase()
    return normalized.contains("google drive") || 
           normalized.contains("マイドライブ") || 
           normalized.contains("my drive") ||
           normalized.startsWith("g:/") || 
           normalized.startsWith("h:/")
}

suspend fun getVideoStartUtc(videoPath: String): String? = withContext(Dispatchers.IO) {
    val isCloud = isGoogleDrivePath(videoPath)
    
    if (isCloud) {
        val result = getVideoStartUtcViaFFmpeg(videoPath)
        if (result != null) return@withContext result
        println("DEBUG: Cloud path FFmpeg metadata extract failed, falling back to parser...")
    }

    val resultParser = getVideoStartUtcViaParser(videoPath)
    if (resultParser != null) return@withContext resultParser

    if (!isCloud) {
        val resultFFmpeg = getVideoStartUtcViaFFmpeg(videoPath)
        if (resultFFmpeg != null) return@withContext resultFFmpeg
    }

    null
}

private suspend fun getVideoStartUtcViaFFmpeg(videoPath: String): String? = withContext(Dispatchers.IO) {
    var attempt = 1
    val maxAttempts = 3
    while (attempt <= maxAttempts) {
        try {
            val ffmpegPath = findFfmpegPath()
            val pb = ProcessBuilder(ffmpegPath, "-i", videoPath)
            pb.redirectErrorStream(true)
            val p = pb.start()
            
            val job = coroutineContext[kotlinx.coroutines.Job]
            val handle = job?.invokeOnCompletion { p.destroyForcibly() }
            
            var creationTimeStr: String? = null
            try {
                val readerThread = kotlin.concurrent.thread {
                    try {
                        val reader = p.inputStream.bufferedReader()
                        val creationTimeRegex = Regex("""creation_time\s*:\s*(\d{4}-\d{2}-\d{2}[\sT]\d{2}:\d{2}:\d{2})""")
                        var line = reader.readLine()
                        while (line != null) {
                            val match = creationTimeRegex.find(line)
                            if (match != null) {
                                creationTimeStr = match.groupValues[1].trim()
                                break
                            }
                            line = reader.readLine()
                        }
                    } catch (e: Exception) {}
                }
                
                val finished = p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    println("DEBUG: getVideoStartUtcViaFFmpeg timed out (Attempt $attempt/$maxAttempts)")
                    p.destroyForcibly()
                    readerThread.interrupt()
                } else {
                    readerThread.join(1000)
                    if (creationTimeStr != null) {
                        val formatted = creationTimeStr!!.replace(" ", "T") + "Z"
                        println("DEBUG: getVideoStartUtc success via FFmpeg (Attempt $attempt): $formatted")
                        return@withContext formatted
                    }
                }
            } finally {
                handle?.dispose()
                if (p.isAlive) {
                    p.destroyForcibly()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("DEBUG: Exception in getVideoStartUtcViaFFmpeg (Attempt $attempt/$maxAttempts): ${e.message}")
        }
        attempt++
        if (attempt <= maxAttempts) {
            delay(1000)
        }
    }
    null
}

private suspend fun getVideoStartUtcViaParser(videoPath: String): String? = withContext(Dispatchers.IO) {
    try {
        val videoFile = File(videoPath)
        if (videoFile.exists()) {
            ensureActive()
            val parser = Mp4Parser()
            val scanSize = minOf(videoFile.length(), 16L * 1024 * 1024).toInt()
            var meta: Mp4Parser.Metadata? = null
            
            java.io.RandomAccessFile(videoFile, "r").use { raf ->
                val buf = ByteArray(scanSize)
                var bytesRead = 0
                while (bytesRead < scanSize) {
                    ensureActive()
                    val read = raf.read(buf, bytesRead, minOf(buf.size - bytesRead, 65536))
                    if (read == -1) break
                    bytesRead += read
                }
                val headBytes = if (bytesRead == buf.size) buf else buf.copyOf(bytesRead)
                meta = parser.parse(headBytes)
            }
            
            if (meta == null && videoFile.length() > scanSize) {
                ensureActive()
                val tailOffset = videoFile.length() - scanSize
                java.io.RandomAccessFile(videoFile, "r").use { raf ->
                    raf.seek(tailOffset)
                    val buf = ByteArray(scanSize)
                    var bytesRead = 0
                    while (bytesRead < scanSize) {
                        ensureActive()
                        val read = raf.read(buf, bytesRead, minOf(buf.size - bytesRead, 65536))
                        if (read == -1) break
                        bytesRead += read
                    }
                    val tailBytes = if (bytesRead == buf.size) buf else buf.copyOf(bytesRead)
                    meta = parser.parse(tailBytes)
                }
            }
            
            if (meta != null) {
                val unixStart = meta!!.creationTimeSeconds - 2082844800L
                val startUtc = java.time.Instant.ofEpochSecond(unixStart).toString()
                println("DEBUG: getVideoStartUtc success via Mp4Parser: $startUtc")
                return@withContext startUtc
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        println("DEBUG: Exception in getVideoStartUtcViaParser: ${e.message}")
    }
    null
}

suspend fun generateInitialThumbnail(ffmpegPath: String, videoPath: String): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        val pb = ProcessBuilder(
            ffmpegPath, "-y",
            "-loglevel", "quiet",
            "-ss", "00:00:01",
            "-i", videoPath,
            "-vframes", "1",
            "-f", "image2pipe",
            "-vcodec", "mjpeg",
            "-"
        )
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        val p = pb.start()
        
        val job = coroutineContext[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { p.destroyForcibly() }
        try {
            val bytes = p.inputStream.use { it.readBytes() }
            p.waitFor()
            if (bytes.isNotEmpty()) {
                val skiaImage = org.jetbrains.skia.Image.makeFromEncoded(bytes)
                return@withContext skiaImage?.toComposeImageBitmap()
            }
        } finally {
            handle?.dispose()
            if (p.isAlive) {
                p.destroyForcibly()
                p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
    }
    null
}

fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

private fun getMd5Hash(str: String): String {
    val md = java.security.MessageDigest.getInstance("MD5")
    val bytes = md.digest(str.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

fun getProxyFileForVideo(videoPath: String): File {
    val tmpDir = System.getProperty("java.io.tmpdir")
    val proxyDir = File(tmpDir, "fit_trimmer_proxies")
    if (!proxyDir.exists()) {
        proxyDir.mkdirs()
    }
    val hash = getMd5Hash(videoPath)
    return File(proxyDir, "$hash.mp4")
}

fun cleanOldProxies() {
    val tmpDir = System.getProperty("java.io.tmpdir")
    val proxyDir = File(tmpDir, "fit_trimmer_proxies")
    if (!proxyDir.exists() || !proxyDir.isDirectory) return

    val now = System.currentTimeMillis()
    val cutoff = now - (7L * 24 * 3600 * 1000)

    val files = proxyDir.listFiles() ?: return

    for (file in files) {
        if (file.isFile && file.lastModified() < cutoff) {
            try {
                file.delete()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    val remainingFiles = (proxyDir.listFiles() ?: return).filter { it.isFile }
    var totalSize = remainingFiles.sumOf { it.length() }
    val limit = 2L * 1024 * 1024 * 1024 // 2 GB

    if (totalSize > limit) {
        val sortedFiles = remainingFiles.sortedBy { it.lastModified() }
        for (file in sortedFiles) {
            val length = file.length()
            try {
                if (file.delete()) {
                    totalSize -= length
                }
            } catch (e: Exception) {
                // ignore
            }
            if (totalSize <= limit) {
                break
            }
        }
    }
}

suspend fun generateProxyVideo(
    videoPath: String,
    ffmpegPath: String,
    videoDurationSec: Double,
    onProgress: (Float) -> Unit
): String? = withContext(Dispatchers.IO) {
    val proxyFile = getProxyFileForVideo(videoPath)
    if (proxyFile.exists()) {
        onProgress(1.0f)
        return@withContext proxyFile.absolutePath
    }

    val tempFile = File(proxyFile.absolutePath + "." + java.util.UUID.randomUUID().toString() + ".tmp")

    val pb = ProcessBuilder(
        ffmpegPath,
        "-y",
        "-i", videoPath,
        "-vf", "scale=-2:360",
        "-c:v", "libx264",
        "-preset", "ultrafast",
        "-crf", "32",
        "-c:a", "aac",
        "-b:a", "64k",
        "-threads", "4",
        tempFile.absolutePath
    )
    pb.redirectErrorStream(true)

    var proc: Process? = null
    val job = coroutineContext[kotlinx.coroutines.Job]
    val handle = job?.invokeOnCompletion {
        proc?.destroyForcibly()
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    try {
        proc = pb.start()
        val progressRegex = Regex("""time=(\d+):(\d{2}):(\d{2})\.(\d+)""")
        
        proc.inputStream.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                ensureActive()
                val match = progressRegex.find(line)
                if (match != null) {
                    val hours = match.groupValues[1].toIntOrNull() ?: 0
                    val minutes = match.groupValues[2].toIntOrNull() ?: 0
                    val seconds = match.groupValues[3].toIntOrNull() ?: 0
                    val fraction = ("0." + match.groupValues[4]).toDoubleOrNull() ?: 0.0
                    val totalSec = hours * 3600.0 + minutes * 60.0 + seconds + fraction
                    if (videoDurationSec > 0.0) {
                        val progressRatio = (totalSec / videoDurationSec).coerceIn(0.0, 1.0)
                        val scaledProgress = (progressRatio * 0.95).toFloat()
                        onProgress(scaledProgress)
                    }
                }
                line = reader.readLine()
            }
        }

        val exitCode = proc.waitFor()
        if (exitCode == 0) {
            if (tempFile.renameTo(proxyFile)) {
                onProgress(1.0f)
                return@withContext proxyFile.absolutePath
            }
        }
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } finally {
        handle?.dispose()
        if (proc?.isAlive == true) {
            proc.destroyForcibly()
            try {
                proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
            } catch (ioe: Exception) {}
        }
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}

