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

fun fallbackParseVideoMetaInfo(output: String): VideoMetaInfo {
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

fun parseVideoMetaInfo(videoPath: String): VideoMetaInfo {
    val ffprobePath = try { fit.findFfprobePath() } catch (e: Exception) { "ffprobe" }
    
    // Call ffprobe for structured JSON
    val pb = ProcessBuilder(
        ffprobePath, "-v", "error",
        "-select_streams", "v:0",
        "-show_entries", "stream=width,height,r_frame_rate:stream_tags=rotate",
        "-show_entries", "format=duration",
        "-show_entries", "stream_side_data=rotation",
        "-of", "json",
        videoPath
    )
    pb.redirectErrorStream(true)
    
    var width = 1920
    var height = 1080
    var duration = 300.0
    var fps = "30"
    var rotation = 0

    try {
        val p = pb.start()
        val output = p.inputStream.bufferedReader().readText()
        p.waitFor()

        val root = kotlinx.serialization.json.Json.parseToJsonElement(output).jsonObject
        
        // 1. Format (duration)
        val formatObj = root["format"]?.let { if (it is kotlinx.serialization.json.JsonObject) it.jsonObject else null }
        if (formatObj != null) {
            formatObj["duration"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.jsonPrimitive else null }?.contentOrNull?.toDoubleOrNull()?.let {
                duration = it
            }
        }

        // 2. Streams (width, height, fps, rotation)
        val streams = root["streams"]?.let { if (it is kotlinx.serialization.json.JsonArray) it.jsonArray else null }
        if (streams != null && streams.isNotEmpty()) {
            val streamElement = streams[0]
            if (streamElement is kotlinx.serialization.json.JsonObject) {
                val stream = streamElement.jsonObject
                stream["width"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.jsonPrimitive else null }?.intOrNull?.let { width = it }
                stream["height"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.jsonPrimitive else null }?.intOrNull?.let { height = it }
                
                // Parse r_frame_rate fraction (e.g. "30000/1001" or "30/1")
                stream["r_frame_rate"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.jsonPrimitive else null }?.contentOrNull?.let { rateStr ->
                    if ("/" in rateStr) {
                        val parts = rateStr.split("/")
                        if (parts.size == 2) {
                            val num = parts[0].toDoubleOrNull()
                            val den = parts[1].toDoubleOrNull()
                            if (num != null && den != null && den > 0.0) {
                                val calcFps = num / den
                                fps = String.format(java.util.Locale.US, "%.2f", calcFps)
                            }
                        }
                    } else {
                        fps = rateStr
                    }
                }

                // Tags (rotate)
                val tagsObj = stream["tags"]?.let { if (it is kotlinx.serialization.json.JsonObject) it.jsonObject else null }
                if (tagsObj != null) {
                    tagsObj["rotate"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.jsonPrimitive else null }?.contentOrNull?.toDoubleOrNull()?.let {
                        rotation = it.toInt()
                    }
                }

                // Side data list (rotation)
                val sideDataList = stream["side_data_list"]?.let { if (it is kotlinx.serialization.json.JsonArray) it.jsonArray else null }
                if (sideDataList != null) {
                    sideDataList.forEach { sideDataElement ->
                        if (sideDataElement is kotlinx.serialization.json.JsonObject) {
                            val sideData = sideDataElement.jsonObject
                            sideData["rotation"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.jsonPrimitive else null }?.contentOrNull?.toDoubleOrNull()?.let {
                                rotation = it.toInt()
                            }
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        // Fallback to legacy ffmpeg stderr output parsing
        try {
            val ffmpegPath = try { fit.findFfmpegPath() } catch (ex: Exception) { "ffmpeg" }
            val pbFallback = ProcessBuilder(ffmpegPath, "-i", videoPath)
            pbFallback.redirectErrorStream(true)
            val pFallback = pbFallback.start()
            val outputFallback = pFallback.inputStream.bufferedReader().readText()
            pFallback.waitFor()
            return fallbackParseVideoMetaInfo(outputFallback)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
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
        val jobs = fit.CacheJobManager.getInstance().scanJobs(firstArgFile.absolutePath)
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
        val jobs = fit.CacheJobManager.getInstance().scanJobs(firstArgFile.absolutePath)
        val job = jobs.find { it.jobHash == jobHash }
        if (job == null) {
            println("❌ Cache job with hash '$jobHash' not found.")
            return
        }
        println("⚠️ Deleting cache job ${job.jobHash} (${job.folder.absolutePath}) ...")
        job.delete()
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
        
        val jobs = fit.CacheJobManager.getInstance().scanJobs(firstArgFile.absolutePath)
        val job = jobs.find { it.jobHash == jobHash }
        if (job == null) {
            println("❌ Cache job with hash '$jobHash' not found.")
            return
        }

        val settings = HudSettings().let { base ->
            base.copy(
                blurLicensePlates = true
            )
        }

        // If called with args for mask generation, rebuild it
        if (true) { // Temporary true to keep block, better to check some flag
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

            println("Re-generating plate_mask.mkv...")
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

        val outPath = fit.CacheJobManager.getInstance().getSalvageOutputPath(firstArgFile.absolutePath, outputDir.absolutePath, settings).absolutePath
        println("🚀 RESTORING CACHE JOB: ${job.jobHash}")
        println("Target Output: $outPath")

        try {
            job.salvageAndMerge(File(outPath)) { progress, status ->
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
        
        val settings = HudSettings(blurLicensePlates = true)
        val encoder = NativeHudEncoder(settings, onProgress = { progress, status ->
            reportProgress(progress, status, true)
        })
        
        val output = videoFile.absolutePath.replace(".mp4", if(isTest) "_TEST_HUD.mp4" else "_CLI_HUD.mp4")
        
        try {
            val duration = if (isTest) 15 else -1
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
