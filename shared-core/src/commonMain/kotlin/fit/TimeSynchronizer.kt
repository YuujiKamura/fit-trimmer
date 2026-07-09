package fit

interface TimeSynchronizer {
    /**
     * FITタイムスタンプ（1989年12月31日基準の秒数）を動画の相対時間（秒）に変換します。
     * 同期情報が不十分な場合は 0.0 を返します。
     */
    fun fitToVideoSeconds(fitTimestamp: Double): Double

    /**
     * 動画の相対時間（秒）をFITタイムスタンプに変換します。
     * 同期情報が不十分な場合は 0.0 を返します。
     */
    fun videoSecondsToFit(videoSeconds: Double): Double

    /**
     * 同期パラメータが揃っており、計算可能であるかを示します。
     */
    val isReady: Boolean
}

class TimeSynchronizerImpl(
    private val videoStartEpochSecProvider: () -> Long?,
    private val offsetMillisProvider: () -> Long
) : TimeSynchronizer {
    private val fitEpoch = 631065600L // 1989-12-31T00:00:00Z (Unix Epoch Seconds)

    override fun fitToVideoSeconds(fitTimestamp: Double): Double {
        val videoStartEpochSec = videoStartEpochSecProvider() ?: return 0.0
        val offsetSeconds = offsetMillisProvider() / 1000.0
        val videoStartFit = videoStartEpochSec - fitEpoch + offsetSeconds
        return fitTimestamp - videoStartFit
    }

    override fun videoSecondsToFit(videoSeconds: Double): Double {
        val videoStartEpochSec = videoStartEpochSecProvider() ?: return 0.0
        val offsetSeconds = offsetMillisProvider() / 1000.0
        val videoStartFit = videoStartEpochSec - fitEpoch + offsetSeconds
        return videoSeconds + videoStartFit
    }

    override val isReady: Boolean
        get() = videoStartEpochSecProvider() != null
}
