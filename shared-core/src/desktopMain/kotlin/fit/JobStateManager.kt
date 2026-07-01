package fit

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

object JobStateManager {
    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun loadState(jobDir: File, jobHash: String): JobState {
        val stateFile = File(jobDir, "job_state.json")
        if (stateFile.exists()) {
            try {
                val content = stateFile.readText()
                return json.decodeFromString<JobState>(content)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return JobState(jobHash = jobHash)
    }

    fun saveState(jobDir: File, state: JobState) {
        val stateFile = File(jobDir, "job_state.json")
        try {
            jobDir.mkdirs()
            val content = json.encodeToString(state)
            stateFile.writeText(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
