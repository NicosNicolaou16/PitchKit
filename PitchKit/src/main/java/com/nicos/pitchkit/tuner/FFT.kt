package com.nicos.pitchkit.tuner

internal object FFT {
    /**
     * In-place iterative radix-2 Cooley–Tukey FFT.
     * Converts a signal from the TIME domain to the FREQUENCY domain.
     * re = real parts (your samples), im = imaginary parts (start at 0).
     * Both arrays are overwritten with the result. Length must be a power of 2.
     */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n == 1) return
        require(n and (n - 1) == 0) { "length must be power of 2" }

        // ---- Bit-reversal permutation ----
        // The FFT emits outputs in bit-reversed index order, so we pre-shuffle
        // the inputs to compensate (e.g. index 001 swaps with 100).
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        // ---- Butterfly computation ----
        // Merge sub-transforms of size 2, 4, 8, ... n, each stage combining pairs
        // via a rotating "twiddle factor" (a point on the complex unit circle).
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = Math.cos(ang)
            val wIm = Math.sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    // second value * twiddle (complex multiply)
                    val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe
                    im[i + k + len / 2] = aIm - bIm
                    // rotate twiddle by the base angle
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Plain magnitude spectrum (no padding). Kept for callers that don't need
     * the finer resolution. Uses the largest power of 2 that fits the input.
     */
    fun magnitude(samples: FloatArray): DoubleArray {
        return magnitudePadded(samples, padFactor = 1)
    }

    /**
     * Zero-padded magnitude spectrum.
     * Zero-padding does NOT add real information, but it INTERPOLATES the spectrum
     * onto a finer frequency grid, which helps resolve closely-spaced LOW notes
     * (e.g. E2 vs F2, where FFT bins are naturally coarse).
     *
     * @param padFactor 1 = no padding; 2 = double the FFT size; etc. Higher = finer
     *                  grid but more CPU. Must keep the result a power of 2.
     */
    fun magnitudePadded(samples: FloatArray, padFactor: Int = 2): DoubleArray {
        val base = Integer.highestOneBit(samples.size)   // largest pow2 that fits
        val n = base * padFactor                         // padded size (still pow2)
        val re = DoubleArray(n)                           // entries past `base` stay 0 = padding
        val im = DoubleArray(n)

        // Apply a Hann window to the REAL samples only (the padded tail stays zero).
        // Windowing tapers the buffer edges to reduce spectral leakage.
        for (i in 0 until base) {
            val w = 0.5 * (1 - Math.cos(2 * Math.PI * i / (base - 1)))
            re[i] = samples[i] * w
        }

        transform(re, im)

        // Magnitude of each bin = sqrt(real² + imag²) = strength of that frequency.
        val mags = DoubleArray(n / 2)
        for (i in 0 until n / 2) {
            mags[i] = Math.hypot(re[i], im[i])
        }
        return mags
    }

    /**
     * Refines a peak's true position using its two neighboring bins.
     * An FFT bin covers a RANGE of frequencies, so a real note usually falls
     * BETWEEN bin centers. Fitting a parabola through (bin-1, bin, bin+1) recovers
     * the true peak to sub-bin accuracy, so its energy maps to the right pitch class.
     *
     * @return fractional bin offset, roughly in -0.5..+0.5, to ADD to `bin`.
     */
    fun interpolatePeak(mags: DoubleArray, bin: Int): Double {
        if (bin <= 0 || bin >= mags.size - 1) return 0.0
        val a = mags[bin - 1]
        val b = mags[bin]
        val g = mags[bin + 1]
        val denom = a - 2 * b + g
        if (denom == 0.0) return 0.0
        // Vertex of the parabola through the three sample points.
        return 0.5 * (a - g) / denom
    }
}