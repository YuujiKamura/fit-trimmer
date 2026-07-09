package fit

import kotlinx.datetime.Clock as KtClock

interface Clock {
    fun currentTimeMillis(): Long

    companion object Default : Clock {
        override fun currentTimeMillis(): Long = KtClock.System.now().toEpochMilliseconds()
    }
}
