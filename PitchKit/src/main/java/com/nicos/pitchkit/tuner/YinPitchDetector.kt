package com.nicos.pitchkit.tuner

internal class YinPitchDetector(
    private val sampleRate: Int,
    minFreq: Double,                       // lowest pitch to look for (profile)
    maxFreq: Double,                       // highest pitch to look for (profile)
    private val threshold: Double = 0.15   // lower = stricter; 0.10–0.15 works well for guitar
) {
    // Lag (samples per period) = sampleRate / frequency → the frequency range becomes a lag range
    private val tauMin = maxOf(2, (sampleRate / maxFreq).toInt())
    private val tauMax = (sampleRate / minFreq).toInt() + 1   // guitar: ~631 instead of 4096
    /**
     * Detects the fundamental of a SINGLE note. YIN works in the time domain,
     * finding the period at which the signal best repeats itself — this resists the
     * octave errors that plague simple FFT peak-picking. Returns Hz, or -1.
     */
    fun detect(buffer: FloatArray): Float {
        val window = buffer.size / 2
        val maxTau = minOf(tauMax, buffer.size - window - 1)  // never read past the buffer
        if (maxTau <= tauMin) return -1f

        // 1. Difference function, only up to maxTau (the ~6× saving)
        val yin = DoubleArray(maxTau + 1)
        for (t in 1..maxTau) {
            var sum = 0.0
            for (i in 0 until window) {
                val d = buffer[i] - buffer[i + t]
                sum += d * d
            }
            yin[t] = sum
        }

        // 2. Cumulative mean normalization
        yin[0] = 1.0
        var running = 0.0
        for (t in 1..maxTau) {
            running += yin[t]
            yin[t] = if (running == 0.0) 1.0 else yin[t] * t / running   // digital silence → avoid NaN
        }

        // 3. First dip below threshold, from tauMin: pitches above maxFreq (hiss) are ignored
        var t = tauMin
        while (t < maxTau) {
            if (yin[t] < threshold) {
                while (t + 1 <= maxTau && yin[t + 1] < yin[t]) t++   // walk to the bottom of the dip
                return (sampleRate / parabolicInterp(yin, t)).toFloat()
            }
            t++
        }
        return -1f
    }

    /** Fits a parabola through 3 points around the dip to refine the minimum. */
    private fun parabolicInterp(yin: DoubleArray, tau: Int): Double {
        val x0 = if (tau > 0) tau - 1 else tau            // left neighbor
        val x2 = if (tau + 1 < yin.size) tau + 1 else tau // right neighbor
        // Edge cases where a neighbor doesn't exist: just pick the smaller point.
        if (x0 == tau) return if (yin[tau] <= yin[x2]) tau.toDouble() else x2.toDouble()
        if (x2 == tau) return if (yin[tau] <= yin[x0]) tau.toDouble() else x0.toDouble()
        val s0 = yin[x0]
        val s1 = yin[tau]
        val s2 = yin[x2]
        // Standard vertex-of-parabola formula. denom==0 means flat → no shift.
        val denom = 2 * (2 * s1 - s2 - s0)
        return if (denom == 0.0) tau.toDouble() else tau + (s2 - s0) / denom
    }
}