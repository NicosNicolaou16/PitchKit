package com.nicos.pitchkit.tuner

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object FFT {
    /**
     * In-place iterative radix-2 Cooley–Tukey FFT.
     * Converts a signal from the TIME domain (amplitude over time) to the
     * FREQUENCY domain (how much of each frequency is present).
     * re = real parts (your samples), im = imaginary parts (start at 0).
     * Both arrays are overwritten with the result.
     */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n == 1) return
        require(n and (n - 1) == 0) { "length must be power of 2" }
        // (n and n-1 == 0) is a bit trick that's true only for powers of 2.

        // ---- STAGE 1: Bit-reversal permutation ----
        // The FFT algorithm produces outputs in "bit-reversed" index order.
        // To get normal order, we pre-shuffle inputs: index i swaps with the
        // index formed by reversing i's bits (e.g. 001 <-> 100).
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            // This loop computes the bit-reversed value of i incrementally.
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {   // swap only once per pair
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        // ---- STAGE 2: Butterfly computation ----
        // We combine the signal in stages, doubling the sub-transform size each
        // pass: 2, 4, 8, ... n. Each stage merges pairs using a "twiddle factor"
        // (a rotating complex number = a point on the unit circle).
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len          // angle step for this stage
            val wRe = cos(ang)                 // twiddle base (real)
            val wIm = sin(ang)                 // twiddle base (imaginary)
            var i = 0
            while (i < n) {
                // cur = current twiddle factor, starts at 1 (angle 0) and rotates.
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    // The "butterfly": take two values, one gets rotated by the
                    // twiddle, then we compute their sum and difference.
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    // b = second value * twiddle  (complex multiplication)
                    val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    // Combine: sum goes to first slot, difference to second.
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe
                    im[i + k + len / 2] = aIm - bIm
                    // Rotate the twiddle factor by the base angle (complex multiply).
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1     // double the sub-transform size
        }
    }

    /**
     * Returns the magnitude spectrum: how strong each frequency bin is.
     * Only the first n/2 bins are meaningful (the rest are mirror images —
     * a property of real-valued input signals).
     */
    fun magnitude(samples: FloatArray): DoubleArray {
        // Use the largest power of 2 that fits, since FFT requires it.
        val n = Integer.highestOneBit(samples.size)
        val re = DoubleArray(n)
        val im = DoubleArray(n)   // imaginary starts all zero: input is real audio

        // ---- Windowing (Hann window) ----
        // Chopping a continuous signal into a finite buffer creates fake
        // frequencies at the edges ("spectral leakage"). Multiplying by a window
        // that tapers to zero at both ends greatly reduces this artifact.
        for (i in 0 until n) {
            val w = 0.5 * (1 - cos(2 * Math.PI * i / (n - 1)))  // Hann curve
            re[i] = samples[i] * w
        }

        transform(re, im)

        // Magnitude of each complex bin = sqrt(real² + imag²) = frequency strength.
        // hypot() computes that safely without overflow.
        val mags = DoubleArray(n / 2)
        for (i in 0 until n / 2) {
            mags[i] = hypot(re[i], im[i])
        }
        return mags
    }
}