import fit.CpCommand
import fit.CpState
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


object ControlPlaneStatus {
    @Volatile var isEncoding: Boolean = false
    @Volatile var progress: Float = 0.0f
    @Volatile var fitPath: String = ""
    @Volatile var videoPath: String = ""
    @Volatile var videoStartUtc: String = ""
    @Volatile var isAligningTelemetry: Boolean = false
    @Volatile var isDetectingPlates: Boolean = false
    @Volatile var plateDetectionProgress: String = ""
    @Volatile var plateDetectionError: String? = null
    @Volatile var plateRecordCount: Int = 0
    @Volatile var videoCurrentTimeMs: Long = 0L
    @Volatile var settingsJson: String = ""
}

class ControlPlane(
    private val onCommand: (CpCommand) -> String?,
    private val getState: () -> CpState
) {
    private var serverSocket: ServerSocket? = null
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val executor = Executors.newFixedThreadPool(4) // 同時接続を4つに制限（エージェント＋予備程度）

    fun start(port: Int = 48099) {
        thread(isDaemon = true, name = "ControlPlane-Main") {
            val sessionFile = File(System.getProperty("user.home"), ".fittrimmer_cp.json")
            
            try {
                // 終了時に確実にファイルを消す
                Runtime.getRuntime().addShutdownHook(Thread {
                    if (sessionFile.exists()) sessionFile.delete()
                })

                serverSocket = ServerSocket(port)
                sessionFile.writeText("{\"port\": $port, \"pid\": ${ProcessHandle.current().pid()}}")
                
                println("📡 Control Plane listening on port $port")

                while (!serverSocket!!.isClosed) {
                    val client = serverSocket?.accept() ?: break
                    executor.execute {
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                println("❌ Control Plane Error: ${e.message}")
                if (sessionFile.exists()) sessionFile.delete()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 5000 // 5秒でタイムアウト（ハング防止）
                val reader = BufferedReader(InputStreamReader(s.inputStream))
                val writer = PrintWriter(s.outputStream, true)
                
                val line = reader.readLine() ?: return
                
                val cmd = json.decodeFromString<CpCommand>(line)
                if (cmd is CpCommand.GetState) {
                    val status = ControlPlaneStatus
                    val currentSettings = if (status.settingsJson.isNotEmpty()) {
                        try {
                            json.decodeFromString<fit.HudSettings>(status.settingsJson)
                        } catch (e: Exception) {
                            fit.HudSettings()
                        }
                    } else {
                        fit.HudSettings()
                    }
                    val state = CpState(
                        settings = currentSettings,
                        fitPath = status.fitPath,
                        videoPath = status.videoPath,
                        isEncoding = status.isEncoding,
                        progress = status.progress,
                        videoStartUtc = status.videoStartUtc,
                        isAligningTelemetry = status.isAligningTelemetry,
                        isDetectingPlates = status.isDetectingPlates,
                        plateDetectionProgress = status.plateDetectionProgress,
                        plateDetectionError = status.plateDetectionError,
                        plateRecordCount = status.plateRecordCount,
                        activePlateBoxes = emptyList(),
                        videoCurrentTimeMs = status.videoCurrentTimeMs
                    )
                    writer.println(json.encodeToString(state))
                } else {
                    val response = onCommand(cmd)
                    if (response != null) {
                        writer.println(response)
                    } else {
                        writer.println("{\"status\": \"ok\"}")
                    }
                }
            } catch (e: Exception) {
                // エラーレスポンスを返せるなら返す
                try {
                    val writer = PrintWriter(s.outputStream, true)
                    writer.println("{\"status\": \"error\", \"message\": \"${e.message}\"}")
                } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        serverSocket?.close()
        executor.shutdown()
    }
}
