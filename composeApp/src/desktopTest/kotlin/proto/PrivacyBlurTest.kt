package proto

import org.junit.Test
import java.io.File

class PrivacyBlurTest {

    @Test
    fun runPrototypeBlur() {
        // テスト用の短い動画ファイル（存在しない場合は適当に用意する必要がある）
        // F:\Insta360\20260712\VID_20260712_163908_005.mp4 などを切り出したものを想定
        val inputVideo = "C:\\Users\\yuuji\\fit-trimmer\\temp_work\\test_in.mp4"
        val outputVideo = "C:\\Users\\yuuji\\fit-trimmer\\temp_work\\test_out_blurred.mp4"

        if (!File(inputVideo).exists()) {
            println("Test skipped. Please place a 1920x1080 30fps video at $inputVideo")
            return
        }

        // ffmpeg path (プロジェクト内のパスを指定)
        val ffmpegPath = "C:\\Users\\yuuji\\fit-trimmer\\temp_work\\bin\\ffmpeg.exe"
        
        // Python版OSSに合わせたデフォルト設定（高解像度推論 + 枠の1.35倍拡張）
        val runner = PrivacyBlurRunner(
            ffmpegPath = "C:\\Users\\yuuji\\fit-trimmer\\temp_work\\bin\\ffmpeg.exe",
            modelPath = "/PlateYOLO-Dynamic.onnx",
            inputSize = 1088,
            confThreshold = 0.35f,
            scale = 1.35f
        )

        runner.processVideo(inputVideo, outputVideo)
        
        println("Output generated: $outputVideo")
    }
}
