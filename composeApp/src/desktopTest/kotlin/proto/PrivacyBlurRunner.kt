package proto

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 独立したプロトタイプ: Kotlin版 Privacy Blur ランナー
 * FFmpegのパイプを利用して動画を1フレームずつ読み込み、
 * PrivacyBlurProtoで検出した座標にぼかし（ピクセレート）を適用し、再度エンコードする。
 */
class PrivacyBlurRunner(
    private val ffmpegPath: String = "C:\\Users\\yuuji\\fit-trimmer\\temp_work\\bin\\ffmpeg.exe",
    private val modelPath: String = "/PlateYOLO-Dynamic.onnx",
    private val inputSize: Int = 1088,
    private val confThreshold: Float = 0.35f,
    private val scale: Float = 1.35f
) {
    fun processVideo(inputPath: String, outputPath: String) {
        val inFile = File(inputPath)
        if (!inFile.exists()) {
            println("Input file not found: $inputPath")
            return
        }

        // --- 1. 動画情報の取得 (ffprobe/ffmpeg) ---
        // 簡易的に 1920x1080 30fps であると仮定するか、動的に取得するか。
        // プロトタイプなので引数等で決め打ちにするか、ffmpegの出力から読む。
        // 今回はユーザーのInsta360ファイルに合わせて一旦 1920x1080 とする。
        val width = 1920
        val height = 1080
        val fps = 30.0
        val frameBytes = width * height * 3

        println("[Runner] Starting process for $inputPath -> $outputPath")
        
        val detector = PrivacyBlurProto(modelPath, inputSize, confThreshold, scale)

        // --- 2. 読み込み用 FFmpeg プロセス ---
        val decodeCmd = listOf(
            ffmpegPath, "-y",
            "-i", inputPath,
            "-f", "rawvideo",
            "-pix_fmt", "rgb24",
            "-an", // no audio
            "-"
        )
        val decodeProcess = ProcessBuilder(decodeCmd)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val inputStream: InputStream = decodeProcess.inputStream

        // --- 3. 書き込み用 FFmpeg プロセス ---
        val encodeCmd = listOf(
            ffmpegPath, "-y",
            "-f", "rawvideo",
            "-vcodec", "rawvideo",
            "-s", "${width}x${height}",
            "-pix_fmt", "rgb24",
            "-r", fps.toString(),
            "-i", "-",
            "-c:v", "libx264",
            "-crf", "18",
            "-preset", "fast",
            "-pix_fmt", "yuv420p",
            outputPath
        )
        val encodeProcess = ProcessBuilder(encodeCmd)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val outputStream: OutputStream = encodeProcess.outputStream

        val buffer = ByteArray(frameBytes)
        val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        var frameCount = 0

        try {
            while (true) {
                var bytesReadTotal = 0
                while (bytesReadTotal < frameBytes) {
                    val read = inputStream.read(buffer, bytesReadTotal, frameBytes - bytesReadTotal)
                    if (read == -1) break
                    bytesReadTotal += read
                }
                if (bytesReadTotal < frameBytes) break // EOF

                // バッファを BufferedImage に転送
                val raster = image.raster
                val dataBuffer = raster.dataBuffer as java.awt.image.DataBufferByte
                val targetBytes = dataBuffer.data
                
                // RGB (ffmpeg) -> BGR (BufferedImage)
                for (i in 0 until width * height) {
                    targetBytes[i * 3 + 0] = buffer[i * 3 + 2] // B
                    targetBytes[i * 3 + 1] = buffer[i * 3 + 1] // G
                    targetBytes[i * 3 + 2] = buffer[i * 3 + 0] // R
                }

                // --- 推論 (既存の PlateDetector を使い、パースミス等の不要なバグを回避) ---
                val rawBoxes = utils.PlateDetector.getInstance().detect(
                    image = image,
                    confThreshold = confThreshold,
                    iouThreshold = 0.45f,
                    maskMode = "plate_direct",
                    inputSize = inputSize
                )

                // --- 拡張 (Expand) ---
                val expandedBoxes = rawBoxes.map { box ->
                    val cx = (box.x1 + box.x2) / 2.0f
                    val cy = (box.y1 + box.y2) / 2.0f
                    val w = (box.x2 - box.x1).toFloat()
                    val h = (box.y2 - box.y1).toFloat()

                    val newW = w * scale
                    val newH = h * scale

                    val nx1 = Math.max(0f, cx - newW / 2.0f).toInt()
                    val ny1 = Math.max(0f, cy - newH / 2.0f).toInt()
                    val nx2 = Math.min(width - 1f, cx + newW / 2.0f).toInt()
                    val ny2 = Math.min(height - 1f, cy + newH / 2.0f).toInt()

                    fit.PlateBox(nx1, ny1, nx2, ny2)
                }

                // --- 描画 (平均色でのベタ塗り) ---
                if (expandedBoxes.isNotEmpty()) {
                    val g = image.createGraphics()
                    for (box in expandedBoxes) {
                        val bx = box.x1
                        val by = box.y1
                        val bw = box.x2 - box.x1
                        val bh = box.y2 - box.y1
                        
                        if (bw > 0 && bh > 0) {
                            // 領域を切り出して平均色を計算
                            val subImg = image.getSubimage(bx, by, bw, bh)
                            var rSum = 0L
                            var gSum = 0L
                            var bSum = 0L
                            val pixels = IntArray(bw * bh)
                            subImg.getRGB(0, 0, bw, bh, pixels, 0, bw)
                            for (p in pixels) {
                                rSum += (p shr 16) and 0xFF
                                gSum += (p shr 8) and 0xFF
                                bSum += p and 0xFF
                            }
                            val count = bw * bh
                            val avgR = (rSum / count).toInt()
                            val avgG = (gSum / count).toInt()
                            val avgB = (bSum / count).toInt()
                            
                            // 平均色で塗りつぶす
                            g.color = java.awt.Color(avgR, avgG, avgB)
                            g.fillRect(bx, by, bw, bh)
                        }
                    }
                    g.dispose()
                }

                // BGR -> RGB に戻して出力へ
                for (i in 0 until width * height) {
                    buffer[i * 3 + 0] = targetBytes[i * 3 + 2] // R
                    buffer[i * 3 + 1] = targetBytes[i * 3 + 1] // G
                    buffer[i * 3 + 2] = targetBytes[i * 3 + 0] // B
                }
                outputStream.write(buffer)
                
                frameCount++
                if (frameCount % 30 == 0) {
                    println("[Runner] Processed $frameCount frames...")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            inputStream.close()
            outputStream.close()
            detector.close()
            
            decodeProcess.waitFor()
            encodeProcess.waitFor()
            println("[Runner] Done. Total frames: $frameCount")
        }
    }
}
