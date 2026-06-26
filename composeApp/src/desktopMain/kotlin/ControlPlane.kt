import fit.CpCommand
import fit.CpState
import fit.HudSettings
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ControlPlane(
    private val onCommand: (CpCommand) -> String?,
    private val getState: () -> CpState
) {
    private var serverSocket: ServerSocket? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun start(port: Int = 48099) {
        thread(isDaemon = true) {
            try {
                serverSocket = ServerSocket(port)
                // セッションファイルを書き出し (エージェントが発見するため)
                val sessionFile = File(System.getProperty("user.home"), ".fittrimmer_cp.json")
                sessionFile.writeText("{\"port\": $port, \"pid\": ${ProcessHandle.current().pid()}}")
                
                println("📡 Control Plane listening on port $port")

                while (true) {
                    val client = serverSocket?.accept() ?: break
                    handleClient(client)
                }
            } catch (e: Exception) {
                println("❌ Control Plane Error: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        thread(isDaemon = true) {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.inputStream))
                val writer = PrintWriter(s.outputStream, true)
                
                val line = reader.readLine() ?: return@thread
                try {
                    val cmd = json.decodeFromString<CpCommand>(line)
                    if (cmd is CpCommand.GetState) {
                        writer.println(json.encodeToString(getState()))
                    } else {
                        val response = onCommand(cmd)
                        if (response != null) {
                            writer.println(response)
                        } else {
                            writer.println("{\"status\": \"ok\"}")
                        }
                    }
                } catch (e: Exception) {
                    writer.println("{\"status\": \"error\", \"message\": \"${e.message}\"}")
                }
            }
        }
    }
}
