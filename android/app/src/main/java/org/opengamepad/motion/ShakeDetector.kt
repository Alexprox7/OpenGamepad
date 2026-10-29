package org.opengamepad.motion

/**
 * Detects a shake: two acceleration peaks (a back-and-forth) within [WINDOW_NS], then stays
 * quiet for [COOLDOWN_NS] so one shake presses the button once.
 *
 * Fed with the magnitude of linear acceleration (gravity removed) in g. [sensitivity] 0..1
 * moves the peak threshold from 2.6 g (firm shake) to 1.2 g (light shake).
 */
class ShakeDetector(var sensitivity: Float = 0.5f) {
    private var above = false
    private var firstPeakNs = 0L
    private var peaks = 0
    private var quietUntilNs = Long.MIN_VALUE

    val threshold: Float get() = 2.6f - 1.4f * sensitivity.coerceIn(0f, 1f)

    /** Returns true when this sample completes a shake. */
    fun onSample(timestampNs: Long, linearAccel: Float): Boolean {
        val high = linearAccel >= threshold
        val rising = high && !above
        // Hysteresis: a peak ends when the magnitude drops well below the threshold.
        above = if (above) linearAccel > threshold * 0.6f else high
        if (timestampNs < quietUntilNs || !rising) return false
        if (peaks == 0 || timestampNs - firstPeakNs > WINDOW_NS) {
            firstPeakNs = timestampNs
            peaks = 1
            return false
        }
        if (timestampNs - firstPeakNs < MIN_GAP_NS) return false
        peaks = 0
        quietUntilNs = timestampNs + COOLDOWN_NS
        return true
    }

    companion object {
        const val WINDOW_NS = 500_000_000L
        const val MIN_GAP_NS = 60_000_000L
        const val COOLDOWN_NS = 600_000_000L
    }
}
