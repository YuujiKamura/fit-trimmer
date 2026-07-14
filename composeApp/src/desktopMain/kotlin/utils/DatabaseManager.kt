package utils

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable
data class DbActivity(
    val id: String,
    val name: String,
    val startDate: String,
    val startDateLocal: String,
    val distance: Double,
    val movingTime: Int,
    val elapsedTime: Int,
    val tss: Double,
    val sufferScore: Int?,
    val avgPower: Double?,
    val normalizedPower: Double?,
    val avgHr: Double?,
    val fitPath: String? = null,
    val segments: List<DbSegment> = emptyList(),
    val telemetry: List<DbTelemetryPoint> = emptyList(),
    val ctl: Double? = null,
    val atl: Double? = null,
    val tsb: Double? = null
)

@Serializable
data class DbSegment(
    val name: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val averageGrade: Double,
    val startIndex: Int = 0,
    val endIndex: Int = 0,
    val startLat: Double = 0.0,
    val startLon: Double = 0.0,
    val endLat: Double = 0.0,
    val endLon: Double = 0.0,
    val startElev: Double = 0.0,
    val endElev: Double = 0.0,
    val minGrade: Double = 0.0,
    val maxGrade: Double = 0.0,
    val startFitTimestamp: Double = 0.0,
    val endFitTimestamp: Double = 0.0
)

@Serializable
data class DbTelemetryPoint(
    val timestamp: Double,
    val speed: Double,
    val power: Double,
    val cadence: Double,
    val heartRate: Double,
    val elevation: Double,
    val grade: Double,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val distance: Double = 0.0,
    val elapsedSeconds: Int = 0,
    val temperature: Double = 0.0
)

object DatabaseManager {
    private val json = Json { ignoreUnknownKeys = true }
    
    @kotlin.jvm.Volatile
    var dbFilePath: String? = null

    private fun getConnection(): Connection {
        val path = dbFilePath ?: run {
            val userHome = System.getProperty("user.home")
            val dbDir = File(userHome, ".fit-trimmer")
            if (!dbDir.exists()) {
                dbDir.mkdirs()
            }
            File(dbDir, "rider_profile.db").absolutePath
        }
        val url = "jdbc:sqlite:${path.replace("\\", "/")}"
        return DriverManager.getConnection(url)
    }

    init {
        setupDatabase()
    }

    internal fun setupDatabase() {
        try {
            Class.forName("org.sqlite.JDBC")
            getConnection().use { conn ->
                val statement = conn.createStatement()
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS activities (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        start_date TEXT NOT NULL,
                        start_date_local TEXT NOT NULL,
                        distance REAL NOT NULL,
                        moving_time INTEGER NOT NULL,
                        elapsed_time INTEGER NOT NULL,
                        tss REAL NOT NULL,
                        suffer_score INTEGER,
                        avg_power REAL,
                        normalized_power REAL,
                        avg_hr REAL,
                        fit_path TEXT,
                        ctl REAL,
                        atl REAL,
                        tsb REAL,
                        payload TEXT NOT NULL
                    )
                """.trimIndent())
                // Safe migration columns if DB already exists
                try { statement.execute("ALTER TABLE activities ADD COLUMN ctl REAL") } catch(_: Exception) {}
                try { statement.execute("ALTER TABLE activities ADD COLUMN atl REAL") } catch(_: Exception) {}
                try { statement.execute("ALTER TABLE activities ADD COLUMN tsb REAL") } catch(_: Exception) {}
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveActivity(act: DbActivity) {
        getConnection().use { conn ->
            val payload = json.encodeToString(act)
            val sql = """
                INSERT OR REPLACE INTO activities (
                    id, name, start_date, start_date_local, distance, 
                    moving_time, elapsed_time, tss, suffer_score, 
                    avg_power, normalized_power, avg_hr, fit_path, 
                    ctl, atl, tsb, payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            
            val pstmt = conn.prepareStatement(sql)
            pstmt.setString(1, act.id)
            pstmt.setString(2, act.name)
            pstmt.setString(3, act.startDate)
            pstmt.setString(4, act.startDateLocal)
            pstmt.setDouble(5, act.distance)
            pstmt.setInt(6, act.movingTime)
            pstmt.setInt(7, act.elapsedTime)
            pstmt.setDouble(8, act.tss)
            if (act.sufferScore != null) pstmt.setInt(9, act.sufferScore) else pstmt.setNull(9, java.sql.Types.INTEGER)
            if (act.avgPower != null) pstmt.setDouble(10, act.avgPower) else pstmt.setNull(10, java.sql.Types.REAL)
            if (act.normalizedPower != null) pstmt.setDouble(11, act.normalizedPower) else pstmt.setNull(11, java.sql.Types.REAL)
            if (act.avgHr != null) pstmt.setDouble(12, act.avgHr) else pstmt.setNull(12, java.sql.Types.REAL)
            pstmt.setString(13, act.fitPath)
            if (act.ctl != null) pstmt.setDouble(14, act.ctl) else pstmt.setNull(14, java.sql.Types.REAL)
            if (act.atl != null) pstmt.setDouble(15, act.atl) else pstmt.setNull(15, java.sql.Types.REAL)
            if (act.tsb != null) pstmt.setDouble(16, act.tsb) else pstmt.setNull(16, java.sql.Types.REAL)
            pstmt.setString(17, payload)
            pstmt.executeUpdate()
        }
    }

    fun getAllActivities(): List<DbActivity> {
        val list = mutableListOf<DbActivity>()
        try {
            getConnection().use { conn ->
                val statement = conn.createStatement()
                val rs = statement.executeQuery("SELECT payload FROM activities ORDER BY start_date DESC")
                while (rs.next()) {
                    val payload = rs.getString("payload")
                    try {
                        list.add(json.decodeFromString<DbActivity>(payload))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun deleteActivity(id: String) {
        try {
            getConnection().use { conn ->
                val pstmt = conn.prepareStatement("DELETE FROM activities WHERE id = ?")
                pstmt.setString(1, id)
                pstmt.executeUpdate()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
