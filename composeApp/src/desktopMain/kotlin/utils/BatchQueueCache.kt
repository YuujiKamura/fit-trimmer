package utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import fit.HudSettings

@Serializable
data class SerializedBatchJobPhase(
    val type: String,
    val enabled: Boolean,
    val status: String,
    val progress: Float
)

@Serializable
data class SerializedBatchJob(
    val id: String,
    val videoPath: String,
    val fitPath: String,
    val videoStartUtc: String,
    val timeOffsetMillis: Long,
    val trimStartSeconds: Double,
    val trimEndSeconds: Double,
    val splitPoints: List<Double>,
    val settings: HudSettings,
    val autoDetectRoadCaptionsOnEncode: Boolean,
    val outputFileNames: List<String>,
    val status: String,
    val progress: Float,
    val errorMessage: String?,
    val phases: List<SerializedBatchJobPhase>? = null,
    val durationSeconds: Double? = null
)

object BatchQueueCache {
    private val isTesting: Boolean
        get() = System.getProperty("org.gradle.test.worker") != null ||
                System.getProperty("java.class.path")?.contains("junit") == true ||
                Thread.currentThread().stackTrace.any { it.className.contains("junit") || it.className.contains("Test") }

    val file: File
        get() = if (isTesting) {
            File(System.getProperty("java.io.tmpdir"), "fittrimmer_batch_queue_test.json")
        } else {
            File(System.getProperty("user.home"), ".fittrimmer_batch_queue.json")
        }

    private val json = Json { ignoreUnknownKeys = true }

    fun save(jobs: List<SerializedBatchJob>) {
        try {
            file.writeText(json.encodeToString(jobs), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun load(): List<SerializedBatchJob> {
        try {
            if (file.exists()) {
                val content = file.readText(Charsets.UTF_8)
                return json.decodeFromString<List<SerializedBatchJob>>(content)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }
}
