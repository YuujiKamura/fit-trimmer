package utils

import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import java.awt.Color
import java.awt.BasicStroke

class PlateDetectorVisualTest {
    @Test
    fun testPlateDetectionVisual() {
        val workDir = File("temp_work")
        if (!workDir.exists()) workDir.mkdirs()

        val detector = PlateDetector.getInstance()
        val inputNames = listOf("sample_frame_0.jpg", "sample_frame_15250.jpg", "sample_frame_30000.jpg")

        for (name in inputNames) {
            val inputFile = File(workDir, name)
            if (!inputFile.exists()) {
                println("Input file missing: ${inputFile.absolutePath}")
                continue
            }

            val img = ImageIO.read(inputFile)
            if (img == null) {
                println("Failed to read image: ${inputFile.absolutePath}")
                continue
            }

            println("Processing ${inputFile.name} (${img.width}x${img.height})...")
            
            // 1. Cascaded detection
            val t0 = System.nanoTime()
            val vehicles = detector.detect(image = img, confThreshold = 0.35f, maskMode = "wide")
            val platesCascaded = detector.detectCascaded(image = img, confThreshold = 0.25f)
            val t1 = System.nanoTime()
            
            // 2. Direct single-stage plate detection
            val platesDirect = detector.detect(image = img, confThreshold = 0.25f, maskMode = "plate_direct")
            val t2 = System.nanoTime()
            
            val msCascaded = (t1 - t0) / 1_000_000.0
            val msDirect = (t2 - t1) / 1_000_000.0
            
            println("Results for $name:")
            println("  Cascaded : ${platesCascaded.size} plates (Vehicles: ${vehicles.size}) in ${"%.1f".format(msCascaded)} ms")
            println("  Direct   : ${platesDirect.size} plates in ${"%.1f".format(msDirect)} ms")

            // Draw bounding boxes
            val g = img.createGraphics()
            
            // Draw Vehicles (Blue)
            for (veh in vehicles) {
                g.color = Color(0, 0, 255, 80) // transparent blue
                val w = veh.x2 - veh.x1
                val h = veh.y2 - veh.y1
                g.fillRect(veh.x1, veh.y1, w, h)
                g.color = Color.BLUE
                g.stroke = BasicStroke(2f)
                g.drawRect(veh.x1, veh.y1, w, h)
            }
            
            // Draw Plates Cascaded (Red)
            for (plt in platesCascaded) {
                g.color = Color(255, 0, 0, 150) // transparent red
                val w = plt.x2 - plt.x1
                val h = plt.y2 - plt.y1
                g.fillRect(plt.x1, plt.y1, w, h)
                g.color = Color.RED
                g.stroke = BasicStroke(4f)
                g.drawRect(plt.x1, plt.y1, w, h)
            }
            
            // Draw Plates Direct (Green)
            for (plt in platesDirect) {
                g.color = Color(0, 255, 0, 150) // transparent green
                val w = plt.x2 - plt.x1
                val h = plt.y2 - plt.y1
                g.fillRect(plt.x1, plt.y1, w, h)
                g.color = Color.GREEN
                g.stroke = BasicStroke(4f)
                g.drawRect(plt.x1, plt.y1, w, h)
            }
            g.dispose()

            val outputFile = File(workDir, "detected_$name")
            ImageIO.write(img, "jpg", outputFile)
            println("Saved output: ${outputFile.absolutePath}")
        }
    }
}
