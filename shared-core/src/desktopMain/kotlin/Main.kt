import fit.*
import mp4.Mp4Parser
import java.io.File
import kotlinx.datetime.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.net.Socket
import java.io.PrintWriter

fun reportProgress(progress: Float, status: String, isEncoding: Boolean) {
    // 互換性重視のASCII進捗表示
    if (isEncoding) {
        val barLength = 20
        val filledLength = (progress * barLength).toInt()
        val bar = "#".repeat(filledLength) + "-".repeat(barLength - filledLength)
        print("\r$status [$bar]")
    } else {
        println("\nEncoding Finished Successfully.")
    }

    // GUIへの通知
    try {
        Socket("localhost", 48099).use { socket ->
            socket.soTimeout = 500
            val writer = PrintWriter(socket.outputStream, true)
            val json = Json.encodeToString<CpCommand>(CpCommand.UpdateProgress(progress, isEncoding))
            writer.println(json)
        }
    } catch (e: Exception) {
        // GUIが起動していない場合は無視
    }
}

data class VideoMetaInfo(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationSeconds: Double,
    val fps: String
)

fun parseVideoMetaInfo(videoPath: String): VideoMetaInfo {
    val ffmpegPath = try { fit.findFfmpegPath() } catch (e: Exception) { "ffmpeg" }
    val pb = ProcessBuilder(ffmpegPath, "-i", videoPath)
    pb.redirectErrorStream(true)
    val p = pb.start()
    val output = p.inputStream.bufferedReader().readText()
    p.waitFor()

    var width = 1920
    var height = 1080
    var duration = 300.0
    var fps = "30"
    var rotation = 0

    val durRegex = Regex("""Duration:\s*(\d+):(\d+):(\d+)\.(\d+)""")
    val durMatch = durRegex.find(output)
    if (durMatch != null) {
        val h = durMatch.groupValues[1].toInt()
        val m = durMatch.groupValues[2].toInt()
        val s = durMatch.groupValues[3].toInt()
        val ms = durMatch.groupValues[4].toInt()
        duration = h * 3600.0 + m * 60.0 + s.toDouble() + ms / 100.0
    }

    val videoRegex = Regex("""Video:.*?\s+(\d+)x(\d+)\s+""")
    val videoMatch = videoRegex.find(output)
    if (videoMatch != null) {
        width = videoMatch.groupValues[1].toInt()
        height = videoMatch.groupValues[2].toInt()
    }

    val fpsRegex = Regex("""(\d+(\.\d+)?)\s+fps""")
    val fpsMatch = fpsRegex.find(output)
    if (fpsMatch != null) {
        fps = fpsMatch.groupValues[1]
    }

    val rotRegex = Regex("""rotation\s*of\s*(-?\d+(\.\d+)?)\s*degrees""")
    val rotMatch = rotRegex.find(output)
    if (rotMatch != null) {
        rotation = rotMatch.groupValues[1].toDouble().toInt()
    }

    return VideoMetaInfo(width, height, rotation, duration, fps)
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage:")
        println("  FitTrimmerCLI <fit_file> <video_file> --encode [--test]")
        println("  FitTrimmerCLI <video_file> --list-cache")
        println("  FitTrimmerCLI <video_file> --delete-cache <job_hash>")
        println("  FitTrimmerCLI <video_file> --salvage-cache <job_hash> --output-dir <path> [--expand <ratio>] [--time-buffer <ms>]")
        return
    }

    val firstArgFile = File(args[0])

    // 1. --list-cache
    if (args.contains("--list-cache")) {
        if (!firstArgFile.exists()) {
            println("❌ Video file not found: ${firstArgFile.absolutePath}")
            return
        }
        val jobs = fit.CacheRegistry.scanAvailableJobs(firstArgFile.absolutePath)
        if (jobs.isEmpty()) {
            println("No unfinished encoding caches found for: ${firstArgFile.name}")
        } else {
            println("=== Available Caches ===")
            jobs.forEach { job ->
                val formattedTime = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .format(java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(job.lastModified), java.time.ZoneId.systemDefault()))
                println("Job Hash:      ${job.jobHash}")
                println("  Parts count: ${job.partsCount} TS files")
                println("  Mask video:  ${if (job.hasMaskVideo) "Yes" else "No"}")
                println("  Last Active: $formattedTime")
                println("--------------------------------")
            }
        }
        return
    }

    // 2. --delete-cache <job_hash>
    val deleteCacheIdx = args.indexOf("--delete-cache")
    if (deleteCacheIdx >= 0 && deleteCacheIdx + 1 < args.size) {
        val jobHash = args[deleteCacheIdx + 1]
        if (!firstArgFile.exists()) {
            println("❌ Video file not found: ${firstArgFile.absolutePath}")
            return
        }
        val jobs = fit.CacheRegistry.scanAvailableJobs(firstArgFile.absolutePath)
        val job = jobs.find { it.jobHash == jobHash }
        if (job == null) {
            println("❌ Cache job with hash '$jobHash' not found.")
            return
        }
        println("⚠️ Deleting cache job ${job.jobHash} (${job.folder.absolutePath}) ...")
        fit.CacheRegistry.deleteCacheJob(job)
        println("✨ Cache job successfully deleted.")
        return
    }

    // 3. --salvage-cache <job_hash> --output-dir <path>
    val salvageIdx = args.indexOf("--salvage-cache")
    if (salvageIdx >= 0 && salvageIdx + 1 < args.size) {
        val jobHash = args[salvageIdx + 1]
        if (!firstArgFile.exists()) {
            println("❌ Video file not found: ${firstArgFile.absolutePath}")
            return
        }
        
        val outputDirIdx = args.indexOf("--output-dir")
        if (outputDirIdx < 0 || outputDirIdx + 1 >= args.size) {
            println("❌ Missing required argument: --output-dir <path>")
            return
        }
        val outputDir = File(args[outputDirIdx + 1])
        
        val jobs = fit.CacheRegistry.scanAvailableJobs(firstArgFile.absolutePath)
        val job = jobs.find { it.jobHash == jobHash }
        if (job == null) {
            println("❌ Cache job with hash '$jobHash' not found.")
            return
        }

        // Custom parameters
        val expandIdx = args.indexOf("--expand")
        val timeBufferIdx = args.indexOf("--time-buffer")
        val customExpand = if (expandIdx >= 0 && expandIdx + 1 < args.size) args[expandIdx + 1].toDoubleOrNull() else null
        val customTimeBuffer = if (timeBufferIdx >= 0 && timeBufferIdx + 1 < args.size) args[timeBufferIdx + 1].toLongOrNull() else null

        val settings = HudSettings().let { base ->
            base.copy(
                blurLicensePlates = true,
                plateMaskExpandRatio = customExpand ?: base.plateMaskExpandRatio,
                plateMaskTimeBufferMs = customTimeBuffer ?: base.plateMaskTimeBufferMs
            )
        }

        // If time-buffer or expand is customized, rebuild the plate mask video first
        if (customExpand != null || customTimeBuffer != null) {
            println("🔄 Custom settings specified. Re-generating plate mask video...")
            val plateCache = fit.PlateCacheManager.loadCache(firstArgFile.absolutePath)
            if (plateCache == null) {
                println("❌ ERROR: YOLO plate detection cache not found for: ${firstArgFile.name}. Cannot re-generate mask video.")
                return
            }

            val meta = parseVideoMetaInfo(firstArgFile.absolutePath)
            val encoder = fit.NativeHudEncoder(settings, onProgress = { progress, status ->
                reportProgress(progress, "Mask Re-gen: $status", true)
            })

            println("Re-generating plate_mask.mkv (Dilation scale with timeBuffer = ${settings.plateMaskTimeBufferMs}ms, expandRatio = ${settings.plateMaskExpandRatio})...")
            try {
                encoder.generateMaskVideoOnly(
                    videoPath = firstArgFile.absolutePath,
                    jobDir = job.folder,
                    jobHash = job.jobHash,
                    plateCache = plateCache,
                    targetDurationSeconds = meta.durationSeconds,
                    videoRotation = meta.rotation,
                    videoWidth = meta.width,
                    videoHeight = meta.height,
                    videoFps = meta.fps,
                    exportWidth = meta.width, // Export at original width
                    exportHeight = meta.height // Export at original height
                )
                println("✨ Plate mask video re-generated successfully.")
            } catch (e: Exception) {
                println("❌ ERROR: Failed to re-generate plate mask video: ${e.message}")
                e.printStackTrace()
                return
            }
        }

        val outPath = fit.CacheRegistry.getSalvageOutputPath(firstArgFile.absolutePath, outputDir.absolutePath, settings).absolutePath
        println("🚀 RESTORING CACHE JOB: ${job.jobHash}")
        println("Target Output: $outPath")

        try {
            fit.CacheRegistry.salvageAndMerge(job.folder, outPath) { progress, status ->
                reportProgress(progress, status, true)
            }
            reportProgress(1.0f, "Finished", false)
            println("✨ SUCCESS: $outPath")
        } catch (e: Exception) {
            println("\n❌ ERROR during salvage: ${e.message}")
            e.printStackTrace()
        }
        return
    }

    val fitFile = firstArgFile
    val videoFile = if (args.size > 1) File(args[1]) else null
    val doEncode = args.contains("--encode")
    val isTest = args.contains("--test")

    if (doEncode && videoFile != null) {
        if (!fitFile.exists()) {
            println("❌ FIT file not found: ${fitFile.absolutePath}")
            return
        }
        if (!videoFile.exists()) {
            println("❌ Video file not found: ${videoFile.absolutePath}")
            return
        }

        println("🚀 STARTING NATIVE KMP ENCODE" + (if(isTest) " [TEST MODE: 5 SECONDS]" else ""))
        println("FIT:   ${fitFile.name}")
        println("VIDEO: ${videoFile.name}")

        val mp4Parser = Mp4Parser()
        val scanSize = minOf(videoFile.length(), 100L * 1024 * 1024).toInt()
        val headBytes = videoFile.inputStream().use { it.readNBytes(scanSize) }
        val meta = mp4Parser.parse(headBytes)
        val startUtc = if (meta != null) {
            val unixStart = meta.creationTimeSeconds - 2082844800L
            Instant.fromEpochSeconds(unixStart).toString()
        } else {
            "2026-06-21T02:09:49Z"
        }

        println("SYNC START (UTC): $startUtc")
        println("--------------------------------------------------")
        
        val encoder = NativeHudEncoder(HudSettings(), onProgress = { progress, status ->
            reportProgress(progress, status, true)
        })
        
        val output = videoFile.absolutePath.replace(".mp4", if(isTest) "_TEST_HUD.mp4" else "_CLI_HUD.mp4")
        
        try {
            val duration = if (isTest) 5 else -1
            encoder.encode(fitFile.absolutePath, videoFile.absolutePath, output, startUtc, maxDurationSeconds = duration)
            reportProgress(1.0f, "Finished", false)
            println("✨ SUCCESS: $output")
        } catch (e: Exception) {
            println("\n❌ ERROR during encode: ${e.message}")
            e.printStackTrace()
        }
        return
    }

    println("❌ Invalid arguments.")
}
