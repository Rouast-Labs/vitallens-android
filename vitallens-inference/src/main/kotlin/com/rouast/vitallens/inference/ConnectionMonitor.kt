package com.rouast.vitallens.inference

/**
 * Thresholds for deciding that a connection is too slow for the standard frame rate.
 * Keep in sync with the other VitalLens SDKs.
 */
object ConnectionThresholds {
    /** Median request duration (ms) above which the connection counts as slow. */
    const val MEDIAN_MS: Double = 1000.0

    /** Duration (ms) of a single request above which the connection counts as slow. */
    const val SINGLE_MS: Double = 2000.0

    /** Frames in a single request above which the connection counts as slow. */
    const val MAX_FRAMES: Int = 50

    /** Number of most recent requests the median is taken over. */
    const val WINDOW: Int = 5

    /** Requests needed before the median rule applies. */
    const val MIN_SAMPLES: Int = 3

    /** Frame rate of eco mode. Slow connections are only reported above it. */
    const val ECO_FPS: Double = 15.0

    /**
     * Frames queued in a single stream request above which the connection counts as unstable and
     * the stream is reset. Even eco mode cannot recover from this.
     */
    const val STREAM_RESET_FRAMES: Int = 100

    /** Time (ms) after which a stream request is given up. */
    const val STREAM_TIMEOUT_MS: Long = 10_000

    /** Time (ms) without any result after which a running scan or monitor is stopped as too slow. */
    const val STALL_TIMEOUT_MS: Long = 8_000

    /** Message shown when the connection is too unstable to continue. Keep in sync with the other SDKs. */
    const val UNSTABLE_MESSAGE: String = "Connection unstable. Stream is resetting."
}

/** Details of a connection that was found to be too slow. */
data class SlowConnectionInfo(val medianMs: Double, val frames: Int)

/**
 * Decides from stream request durations and sizes whether the connection is too slow for the
 * current frame rate. Reports at most once, until [reset]. Not thread-safe: call from one coroutine.
 */
class ConnectionMonitor(
    private val medianMs: Double = ConnectionThresholds.MEDIAN_MS,
    private val singleMs: Double = ConnectionThresholds.SINGLE_MS,
    private val maxFrames: Int = ConnectionThresholds.MAX_FRAMES,
    private val window: Int = ConnectionThresholds.WINDOW,
    private val minSamples: Int = ConnectionThresholds.MIN_SAMPLES,
) {
    private val durations = ArrayDeque<Double>()
    private var samples = 0
    private var fired = false

    /**
     * Records one stream request.
     *
     * @param ms Time from sending the request to receiving the response.
     * @param frames Number of frames sent in the request.
     * @return Details if the connection was just found to be too slow, otherwise `null`.
     */
    fun record(ms: Double, frames: Int): SlowConnectionInfo? {
        if (fired) return null
        samples++
        durations.addLast(ms)
        if (durations.size > window) durations.removeFirst()

        // One very slow request or a long queue is decisive on its own: waiting for more samples
        // is too late once too many frames are queued.
        val median = currentMedian()
        val decisive = ms > singleMs || frames > maxFrames
        if (decisive || (samples >= minSamples && median > medianMs)) {
            fired = true
            return SlowConnectionInfo(median, frames)
        }
        return null
    }

    fun reset() {
        durations.clear()
        samples = 0
        fired = false
    }

    private fun currentMedian(): Double {
        val sorted = durations.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
