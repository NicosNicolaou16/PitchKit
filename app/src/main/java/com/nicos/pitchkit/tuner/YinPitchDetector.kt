package com.nicos.pitchkit.tuner

class YinPitchDetector(
    private val sampleRate: Int,
    private val threshold: Double = 0.15   // lower = stricter; 0.10–0.15 works well for guitar
) {
    /**
     * Detects the fundamental frequency of a SINGLE note.
     * YIN works in the time domain by finding the period at which the signal
     * most closely repeats itself. It resists the "octave errors" that plague
     * simple FFT peak-picking (where a harmonic is mistaken for the fundamental).
     * Returns Hz, or -1 if no clear pitch.
     */
    fun detect(buffer: FloatArray): Float {
        val tau = buffer.size / 2     // max lag we test (tau = "period" candidate)
        val yin = DoubleArray(tau)

        // ---- STEP 1: Difference function ----
        // For each candidate period t, measure how DIFFERENT the signal is from
        // itself shifted by t samples. If the signal repeats every t samples
        // (i.e. t is the true period), this difference is near zero.
        for (t in 1 until tau) {
            var sum = 0.0
            for (i in 0 until tau) {
                val delta = buffer[i] - buffer[i + t]
                sum += delta * delta     // squared difference
            }
            yin[t] = sum
        }

        // ---- STEP 2: Cumulative mean normalization ----
        // The raw difference is always ~0 at t=0, which we must ignore. This step
        // normalizes each value against the running average so we can use a fixed
        // threshold, and avoids picking the trivial t=0 dip.
        yin[0] = 1.0
        var runningSum = 0.0
        for (t in 1 until tau) {
            runningSum += yin[t]
            yin[t] *= t / runningSum
        }

        // ---- STEP 3: Absolute threshold ----
        // Find the FIRST period where the normalized difference dips below the
        // threshold — this is the fundamental (not a harmonic). We then keep
        // descending into that dip to find its true lowest point.
        var tauEstimate = -1
        var t = 2
        while (t < tau) {
            if (yin[t] < threshold) {
                // walk down to the local minimum of this valley
                while (t + 1 < tau && yin[t + 1] < yin[t]) t++
                tauEstimate = t
                break
            }
            t++
        }
        if (tauEstimate == -1) return -1f   // no repeating pattern found = no pitch

        // ---- STEP 4: Parabolic interpolation ----
        // The true period rarely lands exactly on an integer sample. Fitting a
        // parabola through the dip and its two neighbors gives sub-sample accuracy,
        // which matters a lot for showing tuning in cents.
        val betterTau = parabolicInterp(yin, tauEstimate)

        // frequency = sampleRate / period
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