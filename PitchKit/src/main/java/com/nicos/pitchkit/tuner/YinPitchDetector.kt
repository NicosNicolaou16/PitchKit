package com.nicos.pitchkit.tuner

internal class YinPitchDetector(
    private val sampleRate: Int,
    private val threshold: Double = 0.15   // lower = stricter; 0.10–0.15 works well for guitar
) {
    /**
     * Detects the fundamental of a SINGLE note. YIN works in the time domain,
     * finding the period at which the signal best repeats itself — this resists the
     * octave errors that plague simple FFT peak-picking. Returns Hz, or -1.
     */
    fun detect(buffer: FloatArray): Float {
        val tau = buffer.size / 2
        val yin = DoubleArray(tau)

        // 1. Difference function: how different is the signal from itself shifted by t?
        for (t in 1 until tau) {
            var sum = 0.0
            for (i in 0 until tau) {
                val delta = buffer[i] - buffer[i + t]
                sum += delta * delta
            }
            yin[t] = sum
        }

        // 2. Cumulative mean normalization: lets us use a fixed threshold and avoids
        //    the trivial t=0 dip.
        yin[0] = 1.0
        var runningSum = 0.0
        for (t in 1 until tau) {
            runningSum += yin[t]
            yin[t] *= t / runningSum
        }

        // 3. First dip below threshold = the fundamental (not a harmonic).
        var tauEstimate = -1
        var t = 2
        while (t < tau) {
            if (yin[t] < 0.15) {
                while (t + 1 < tau && yin[t + 1] < yin[t]) t++
                tauEstimate = t
                break
            }
            t++
        }
        if (tauEstimate == -1) return -1f

        // 4. Parabolic interpolation around the dip for SUB-SAMPLE period accuracy.
        //    This is what makes the cents reading precise rather than quantized.
        val betterTau = parabolicInterp(yin, tauEstimate)
        return (sampleRate / betterTau).toFloat()
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