package com.nicos.pitchkit.tuner

import com.nicos.pitchkit.tuner.models.InstrumentProfile
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class ChordDetector(
    private val sampleRate: Int,
    private val profile: InstrumentProfile,   // injected: supplies all frequency bounds
) {

    private data class Template(val name: String, val pitches: IntArray)

    private val templates = buildTemplates()

    // Hysteresis state (prevents flicker between close chords like Am/F).
    private var currentChord: String? = null
    private var currentScore = 0.0

    // Temporal-averaging state: rolling buffer of recent chroma vectors.
    private val chromaHistory = ArrayDeque<DoubleArray>()
    private val chromaWindow = 3

    /**
     * Builds every recognizable chord by transposing each interval pattern to all
     * 12 roots. Chord templates are pitch-class based, so they're already
     * instrument-independent (a C major is C-E-G on any instrument). The ONLY
     * per-instrument bit is the sharp/flat display preference.
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
                // Use the profile's sharp/flat preference for the display name.
                val rootName = if (profile.useFlats) (flat[roots[r]] ?: roots[r]) else roots[r]
                val display = rootName + suffix
                list.add(Template(display, ivals.map { (it + r) % 12 }.toIntArray()))
            }
        }
        return list
    }

    /**
     * Builds the 12-bin chroma vector from a magnitude spectrum, using the
     * profile's frequency window and harmonic pivot instead of hardcoded numbers.
     */
    private fun chromaFrom(mags: DoubleArray, n: Int): DoubleArray {
        val chroma = DoubleArray(12)
        for (bin in 1 until mags.size - 1) {
            // Only consider local maxima (actual spectral peaks).
            if (mags[bin] < mags[bin - 1] || mags[bin] < mags[bin + 1]) continue

            // Refine the peak's true frequency between bins.
            val offset = FFT.interpolatePeak(mags, bin)
            val freq = (bin + offset) * sampleRate.toDouble() / n
            // Profile-driven window instead of hardcoded 70 / 5000.
            if (freq < profile.minFreq || freq > profile.maxFreq) continue

            // Harmonic weighting centred on the instrument's pivot, not a fixed 200.
            val weight = sqrt(profile.harmonicPivot / max(freq, profile.harmonicPivot))

            val midi = 69 + 12 * (ln(freq / 440.0) / ln(2.0))
            val pc = ((midi.roundToInt() % 12) + 12) % 12
            chroma[pc] += mags[bin] * weight
        }
        // Normalize so the loudest pitch class = 1.0 (volume-independent scoring).
        val max = chroma.maxOrNull() ?: 1.0
        if (max > 0) for (i in chroma.indices) chroma[i] /= max
        return chroma
    }

    /** Averages the current chroma with recent frames to suppress the strum attack. */
    private fun smoothedChroma(current: DoubleArray): DoubleArray {
        chromaHistory.addLast(current)
        if (chromaHistory.size > chromaWindow) chromaHistory.removeFirst()
        val avg = DoubleArray(12)
        for (frame in chromaHistory) for (i in 0 until 12) avg[i] += frame[i]
        for (i in 0 until 12) avg[i] /= chromaHistory.size
        return avg
    }

    /**
     * Finds the pitch class of the lowest strong frequency — the bass note — used
     * to disambiguate chords that share most notes (Am vs F). Scans only the
     * profile's bass region (minFreq → bassCeiling).
     */
    private fun detectBassPitchClass(mags: DoubleArray, n: Int): Int {
        val maxMag = mags.maxOrNull() ?: return -1
        for (bin in 1 until mags.size) {
            val freq = bin * sampleRate.toDouble() / n
            if (freq < profile.minFreq) continue      // profile floor
            if (freq > profile.bassCeiling) break      // profile bass ceiling
            if (mags[bin] > maxMag * 0.3) {            // first prominent low bin
                val midi = 69 + 12 * (ln(freq / 440.0) / ln(2.0))
                return ((midi.roundToInt() % 12) + 12) % 12
            }
        }
        return -1
    }

    data class ChordResult(val name: String, val score: Double)

    /**
     * Detects the chord in the buffer.
     * Pipeline: padded FFT (once) → interpolated + harmonic-weighted chroma →
     * temporal averaging → bass detection → template scoring with bass bonus →
     * hysteresis → result.
     */
    fun detect(buffer: FloatArray, minScore: Double = 0.20): ChordResult? {
        // Padded FFT computed once, reused for chroma + bass.
        val mags = FFT.magnitudePadded(buffer, padFactor = 2)
        val n = mags.size * 2

        val rawChroma = chromaFrom(mags, n)
        val c = smoothedChroma(rawChroma)
        if (c.sum() < 0.5) return null      // essentially silence

        val bass = detectBassPitchClass(mags, n)

        // Score every template: reward chord tones, penalize non-chord tones.
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
            // Bass bonus: reward chords whose root matches the detected bass note.
            if (bass >= 0 && t.pitches.isNotEmpty() && t.pitches[0] == bass) {
                score += 0.15
            }
            if (score > bestScore) { bestScore = score; best = t }
        }

        if (bestScore < minScore) return null      // reject weak matches
        val candidateName = best?.name ?: return null

        // Hysteresis: only switch chords when the challenger is clearly better.
        val switchMargin = 0.08
        if (candidateName == currentChord) {
            currentScore = bestScore
        } else if (bestScore > currentScore + switchMargin) {
            currentChord = candidateName
            currentScore = bestScore
        }
        return currentChord?.let { ChordResult(it, currentScore) }
    }

    /** Clears rolling state; call on silence so the next chord starts fresh. */
    fun reset() {
        currentChord = null
        currentScore = 0.0
        chromaHistory.clear()
    }

    /** Public chroma accessor for the engine's note-vs-chord branch. */
    fun chroma(buffer: FloatArray): DoubleArray {
        val mags = FFT.magnitudePadded(buffer, padFactor = 2)
        return chromaFrom(mags, n = mags.size * 2)
    }
}