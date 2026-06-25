package utils

import java.io.File
import java.awt.FileDialog
import java.awt.Frame
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
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
    try {
        val ffmpegPath = findFfmpegPath()
        val pbDur = ProcessBuilder(ffmpegPath, "-i", videoPath)
        pbDur.redirectErrorStream(true)
        val pDur = pbDur.start()
        
        val job = coroutineContext[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { pDur.destroyForcibly() }
        try {
            val output = pDur.inputStream.bufferedReader().readText()
            pDur.waitFor()
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
        } finally {
            handle?.dispose()
            if (pDur.isAlive) {
                pDur.destroyForcibly()
                pDur.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        println("DEBUG: Exception during video duration parse (FFmpeg): ${e.message}")
        e.printStackTrace()
    }
    null
}

suspend fun getVideoStartUtc(videoPath: String): String? = withContext(Dispatchers.IO) {
    try {
        val videoFile = File(videoPath)
        println("DEBUG: getVideoStartUtc for path: $videoPath, length=${videoFile.length()}")
        if (videoFile.exists()) {
            ensureActive()
            val parser = Mp4Parser()
            // Using 16MB scan size instead of 100MB to limit latency on network virtual files (Google DriveFS)
            val scanSize = minOf(videoFile.length(), 16L * 1024 * 1024).toInt()
            
            var meta: Mp4Parser.Metadata? = null
            
            // 1. Scan head using RandomAccessFile
            java.io.RandomAccessFile(videoFile, "r").use { raf ->
                val buf = ByteArray(scanSize)
                var bytesRead = 0
                while (bytesRead < scanSize) {
                    ensureActive()
                    val read = raf.read(buf, bytesRead, minOf(buf.size - bytesRead, 65536))
                    if (read == -1) break
                    bytesRead += read
                }
                println("DEBUG: getVideoStartUtc head bytesRead: $bytesRead")
                val headBytes = if (bytesRead == buf.size) buf else buf.copyOf(bytesRead)
                meta = parser.parse(headBytes)
            }
            ensureActive()
            
            // 2. Scan tail using native seek if mvhd was not found in head
            if (meta == null && videoFile.length() > scanSize) {
                val tailOffset = videoFile.length() - scanSize
                println("DEBUG: getVideoStartUtc tail scanning at tailOffset: $tailOffset")
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
                    println("DEBUG: getVideoStartUtc tail bytesRead: $bytesRead")
                    val tailBytes = if (bytesRead == buf.size) buf else buf.copyOf(bytesRead)
                    meta = parser.parse(tailBytes)
                }
            }
            
            if (meta != null) {
                val unixStart = meta!!.creationTimeSeconds - 2082844800L
                val startUtc = java.time.Instant.ofEpochSecond(unixStart).toString()
                println("DEBUG: getVideoStartUtc success meta duration=${meta!!.duration}, startUtc=$startUtc")
                return@withContext startUtc
            } else {
                println("DEBUG: getVideoStartUtc failed to parse metadata via Mp4Parser. Trying FFmpeg fallback...")
                try {
                    val ffmpegPath = findFfmpegPath()
                    val pb = ProcessBuilder(ffmpegPath, "-i", videoPath)
                    pb.redirectErrorStream(true)
                    val p = pb.start()
                    val reader = p.inputStream.bufferedReader()
                    var creationTimeStr: String? = null
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
                    p.destroyForcibly()
                    p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                    
                    if (creationTimeStr != null) {
                        val formatted = creationTimeStr.replace(" ", "T") + "Z"
                        println("DEBUG: getVideoStartUtc success via FFmpeg: $formatted")
                        return@withContext formatted
                    }
                } catch (e: Exception) {
                    println("DEBUG: Exception during FFmpeg creation_time fallback: ${e.message}")
                    e.printStackTrace()
                }
                println("DEBUG: getVideoStartUtc failed to parse metadata (meta is null)")
            }
        } else {
            println("DEBUG: getVideoStartUtc file does not exist")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        println("DEBUG: Exception during video start UTC parse: ${e.message}")
        e.printStackTrace()
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
