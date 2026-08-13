package com.nicos.pitchkit.tuner

internal class ChordDetector(private val sampleRate: Int) {

    // A chord template = its pitch classes, ignoring octave. pitches[0] is the ROOT.
    private data class Template(val name: String, val pitches: IntArray)

    private val templates = buildTemplates()

    // ---- Hysteresis state (prevents flicker between close chords like Am/F) ----
    private var currentChord: String? = null
    private var currentScore = 0.0

    // ---- Temporal-averaging state ----
    // A rolling buffer of recent chroma vectors, averaged before scoring so the
    // noisy strum "attack" doesn't dominate the decision.
    private val chromaHistory = ArrayDeque<DoubleArray>()
    private val chromaWindow = 3   // average over the last 3 frames

    /**
     * Generates every recognizable chord by transposing each interval pattern to
     * all 12 roots. The first interval is always 0, so pitches[0] is the root.
     */
    private fun buildTemplates(): List<Template> {
        val roots = arrayOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
        val flat = mapOf("C#" to "Db","D#" to "Eb","F#" to "Gb","G#" to "Ab","A#" to "Bb")
        val list = mutableListOf<Template>()
        val qualities = listOf(
            "" to intArrayOf(0,4,7),        // major triad
            "m" to intArrayOf(0,3,7),       // minor triad
            "7" to intArrayOf(0,4,7,10),    // dominant 7th
            "m7" to intArrayOf(0,3,7,10),   // minor 7th
            "maj7" to intArrayOf(0,4,7,11),
            "sus2" to intArrayOf(0,2,7),
            "sus4" to intArrayOf(0,5,7),
            "dim" to intArrayOf(0,3,6),
            "aug" to intArrayOf(0,4,8)
        )
        for (r in roots.indices) {
            for ((suffix, ivals) in qualities) {
                val display = (flat[roots[r]] ?: roots[r]) + suffix
                list.add(Template(display, ivals.map { (it + r) % 12 }.toIntArray()))
            }
        }
        return list
    }

    /**
     * Builds a 12-element chroma vector from a magnitude spectrum, with TWO
     * accuracy improvements baked in:
     *  1. Peak interpolation — only local maxima contribute, and their true
     *     frequency is refined between bins so energy lands on the right pitch class.
     *  2. Harmonic weighting — higher frequencies are down-weighted because most
     *     of that energy is overtones of lower fundamentals, not separate notes.
     *
     * @param mags magnitude spectrum (ideally zero-padded)
     * @param n    the FFT size that produced `mags` (mags.size is n/2)
     */
    private fun chromaFrom(mags: DoubleArray, n: Int): DoubleArray {
        val chroma = DoubleArray(12)

        // Iterate interior bins so we can look at both neighbors for peak-picking.
        for (bin in 1 until mags.size - 1) {
            // ---- Only consider LOCAL MAXIMA (actual spectral peaks) ----
            // A note shows up as a peak; skipping non-peaks removes smeared energy
            // and noise between peaks, which sharpens the chroma.
            if (mags[bin] < mags[bin - 1] || mags[bin] < mags[bin + 1]) continue

            // ---- Refine the peak's true frequency between bins ----
            val offset = FFT.interpolatePeak(mags, bin)
            val freq = (bin + offset) * sampleRate.toDouble() / n
            if (freq < 70 || freq > 5000) continue

            // ---- Harmonic weighting ----
            // Guitar chord fundamentals sit ~80–500 Hz; energy far above is mostly
            // overtones. This gentle rolloff keeps some harmonic info without letting
            // it swamp the fundamentals (a key cause of Am/F confusion).
            val weight = Math.sqrt(200.0 / Math.max(freq, 200.0))

            val midi = 69 + 12 * (Math.log(freq / 440.0) / Math.log(2.0))
            val pc = ((Math.round(midi).toInt() % 12) + 12) % 12
            chroma[pc] += mags[bin] * weight
        }

        // Normalize so the loudest pitch class = 1.0 (volume-independent scoring).
        val max = chroma.maxOrNull() ?: 1.0
        if (max > 0) for (i in chroma.indices) chroma[i] /= max
        return chroma
    }

    /**
     * Averages the current chroma with the previous few frames.
     * The pluck/strum attack is momentary and noisy; the sustained part is what
     * truly identifies the chord. Averaging suppresses the transient and makes
     * detection markedly more stable.
     */
    private fun smoothedChroma(current: DoubleArray): DoubleArray {
        chromaHistory.addLast(current)
        if (chromaHistory.size > chromaWindow) chromaHistory.removeFirst()

        val avg = DoubleArray(12)
        for (frame in chromaHistory) {
            for (i in 0 until 12) avg[i] += frame[i]
        }
        for (i in 0 until 12) avg[i] /= chromaHistory.size
        return avg
    }

    /**
     * Finds the pitch class of the LOWEST strong frequency — the bass note.
     * The bass distinguishes chords that share most notes (Am's bass A vs F's bass F).
     */
    private fun detectBassPitchClass(mags: DoubleArray, n: Int): Int {
        val maxMag = mags.maxOrNull() ?: return -1
        for (bin in 1 until mags.size) {
            val freq = bin * sampleRate.toDouble() / n
            if (freq < 70) continue         // skip rumble below the low E
            if (freq > 400) break           // a chord's bass note won't be above ~400 Hz
            if (mags[bin] > maxMag * 0.3) {  // first prominent low bin
                val midi = 69 + 12 * (Math.log(freq / 440.0) / Math.log(2.0))
                return ((Math.round(midi).toInt() % 12) + 12) % 12
            }
        }
        return -1
    }

    data class ChordResult(val name: String, val score: Double)

    /**
     * Detects the chord in the buffer.
     * Pipeline: zero-padded FFT (once) → interpolated + harmonic-weighted chroma →
     * temporal averaging → bass detection → template scoring with bass bonus →
     * hysteresis → result.
     *
     * @param minScore reject the best match if below this (avoids weak guesses)
     */
    fun detect(buffer: FloatArray, minScore: Double = 0.20): ChordResult? {
        // Zero-padded FFT, computed ONCE and reused for chroma + bass.
        val mags = FFT.magnitudePadded(buffer, padFactor = 2)
        val n = mags.size * 2

        // Interpolated, harmonic-weighted chroma, then averaged over recent frames.
        val rawChroma = chromaFrom(mags, n)
        val c = smoothedChroma(rawChroma)
        if (c.sum() < 0.5) return null      // essentially silence

        val bass = detectBassPitchClass(mags, n)

        // ---- Score every template ----
        var best: Template? = null
        var bestScore = -1.0
        for (t in templates) {
            val set = t.pitches.toHashSet()
            var inChord = 0.0
            var outChord = 0.0
            for (pc in 0 until 12) {
                if (pc in set) inChord += c[pc] else outChord += c[pc]
            }
            var score = inChord / t.pitches.size - 0.5 * outChord / (12 - t.pitches.size)

            // Bass bonus: reward chords whose ROOT matches the detected bass note.
            if (bass >= 0 && t.pitches.isNotEmpty() && t.pitches[0] == bass) {
                score += 0.15
            }

            if (score > bestScore) { bestScore = score; best = t }
        }

        if (bestScore < minScore) return null
        val candidateName = best?.name ?: return null

        // ---- Hysteresis: only switch chords when clearly better ----
        val switchMargin = 0.08
        if (candidateName == currentChord) {
            currentScore = bestScore
        } else if (bestScore > currentScore + switchMargin) {
            currentChord = candidateName
            currentScore = bestScore
        }
        return currentChord?.let { ChordResult(it, currentScore) }
    }

    /**
     * Clears all rolling state. Call on silence so the next chord starts fresh
     * and isn't averaged across a gap or held back by stale hysteresis.
     */
    fun reset() {
        currentChord = null
        currentScore = 0.0
        chromaHistory.clear()
    }

    /**
     * Public chroma accessor for the engine's note-vs-chord branch (it counts
     * strong pitch classes). Uses the same padded FFT + weighted chroma so the
     * branch decision matches what detect() sees.
     */
    fun chroma(buffer: FloatArray): DoubleArray {
        val mags = FFT.magnitudePadded(buffer, padFactor = 2)
        return chromaFrom(mags, n = mags.size * 2)
    }
}