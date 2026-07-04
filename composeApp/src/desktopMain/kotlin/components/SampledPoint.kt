package components

data class SampledPoint(
    val seconds: Float,
    val power: Double,
    val speed: Double,
    val elevation: Double,
    val isValid: Boolean
)
