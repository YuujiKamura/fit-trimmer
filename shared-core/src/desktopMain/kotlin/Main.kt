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

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: FitTrimmerCLI <fit_file> <video_file> --encode [--test]")
        return
    }

    val fitFile = File(args[0])
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
