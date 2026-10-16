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

    // Temporal-averaging state: rolling buffer of recent chroma vectors.
    private val chromaHistory = ArrayDeque<DoubleArray>()
    private val chromaWindow = 3

    // ChordDetector.kt: delete L25–26 and add at the end of the class
    private companion object {
        // Compile-time constants: one place to tune the chord scoring
        const val BASS_ROOT_BONUS = 0.15   // reward chords whose root is the lowest note
        const val SWITCH_MARGIN = 0.08     // how much a new chord must beat the current one
    }

    /**
     * Builds every recognizable chord by transposing each interval pattern to all
     * 12 roots. Chord templates are pitch-class based, so they're already
     * instrument-independent (a C major is C-E-G on any instrument). The ONLY
     * per-instrument bit is the sharp/flat display preference.
     */
    private fun buildTemplates(): List<Template> {
        val roots = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val flat = mapOf("C#" to "Db", "D#" to "Eb", "F#" to "Gb", "G#" to "Ab", "A#" to "Bb")
        val list = mutableListOf<Template>()
        val qualities = listOf(
            "" to intArrayOf(0, 4, 7),        // major triad
            "m" to intArrayOf(0, 3, 7),       // minor triad
            "7" to intArrayOf(0, 4, 7, 10),    // dominant 7th
            "m7" to intArrayOf(0, 3, 7, 10),   // minor 7th
            "maj7" to intArrayOf(0, 4, 7, 11),
            "sus2" to intArrayOf(0, 2, 7),
            "sus4" to intArrayOf(0, 5, 7),
            "dim" to intArrayOf(0, 3, 6),
            "aug" to intArrayOf(0, 4, 8)
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
     * Detects the chord from a precomputed spectrum and its raw chroma (both computed
     * once per frame by TunerEngine).
     * Pipeline: temporal averaging → bass detection → template scoring with bass bonus →
     * hysteresis → result.
     * @param mags      magnitude spectrum from FFT.magnitudePadded(buffer, padFactor = 2).
     * @param rawChroma 12-bin chroma from [chroma] for the same frame.
     */
    fun detect(
        mags: DoubleArray,
        rawChroma: DoubleArray,
        minScore: Double = 0.20
    ): ChordResult? {
        val n = mags.size * 2
        val c = smoothedChroma(rawChroma)     // reuse the chroma the engine already computed
        if (c.sum() < 0.5) return null

        val bass = detectBassPitchClass(mags, n)

        // Score every template: reward chord tones, penalize non-chord tones.
        var best: Template? = null
        var bestScore = -1.0
        var currentChordScore = -1.0   // how well the CURRENTLY shown chord fits THIS frame
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
                score += BASS_ROOT_BONUS
            }
            if (t.name == currentChord) currentChordScore = score
            if (score > bestScore) {
                bestScore = score; best = t
            }
        }

        if (bestScore < minScore) return null      // reject weak matches
        val candidateName = best?.name ?: return null

        // Hysteresis: switch only when the challenger beats the current chord
        // IN THIS SAME FRAME by a clear margin. Comparing against a stored score from
        // earlier frames made low-scoring chords (Em, F) unable to replace high-scoring
        // ones (D, C), so the old chord stayed on screen.
        if (currentChord == null ||
            candidateName == currentChord ||
            bestScore > currentChordScore + SWITCH_MARGIN
        ) {
            currentChord = candidateName
        }
        val shownScore = if (currentChord == candidateName) bestScore else currentChordScore
        return currentChord?.let { ChordResult(it, shownScore) }
    }

    /** Clears rolling state; call on silence so the next chord starts fresh. */
    fun reset() {
        currentChord = null
        chromaHistory.clear()
    }

    /** A new strum started: drop the previous chord's chroma from the average. */
    fun onNewStrum() {
        chromaHistory.clear()
    }

    /** 12-bin chroma for the note-vs-chord decision, from a precomputed spectrum. */
    fun chroma(mags: DoubleArray): DoubleArray =
        chromaFrom(mags, n = mags.size * 2)   // n = padded FFT size (mags holds n/2 bins)
}