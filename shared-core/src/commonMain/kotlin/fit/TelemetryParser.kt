package fit

data class TelemetryPoint(
    val timestamp: Double,
    val speed: Double,
    val power: Double,
    val cadence: Double,
    val heartRate: Double,
    val elevation: Double,
    var grade: Double,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val distance: Double = 0.0,
    val elapsedSeconds: Int = 0,
    val temperature: Double = 0.0
)

interface TelemetryParser {
    fun parse(bytes: ByteArray): List<TelemetryPoint>
}
