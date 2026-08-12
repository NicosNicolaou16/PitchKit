package com.nicos.pitchkit.tuner

import kotlin.collections.get
import kotlin.compareTo
import kotlin.div
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.times

class ChordDetector(private val sampleRate: Int) {

    // A chord template = the set of pitch classes it contains, ignoring octave.
    // Pitch classes: C=0, C#=1, ... B=11.
    private data class Template(val name: String, val pitches: IntArray)

    private val templates = buildTemplates()

    /**
     * Generates every chord we can recognize by taking each interval pattern
     * and transposing it to all 12 possible roots.
     */
    private fun buildTemplates(): List<Template> {
        val roots = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val flat = mapOf("C#" to "Db", "D#" to "Eb", "F#" to "Gb", "G#" to "Ab", "A#" to "Bb")
        val list = mutableListOf<Template>()

        // Each quality is defined by SEMITONE OFFSETS from the root.
        // e.g. major = root(0) + major third(4) + perfect fifth(7).
        val qualities = listOf(
            "" to intArrayOf(0, 4, 7),        // major triad
            "m" to intArrayOf(0, 3, 7),       // minor triad (flat 3rd)
            "7" to intArrayOf(0, 4, 7, 10),    // dominant 7th
            "m7" to intArrayOf(0, 3, 7, 10),   // minor 7th
            "maj7" to intArrayOf(0, 4, 7, 11),
            "sus2" to intArrayOf(0, 2, 7),
            "sus4" to intArrayOf(0, 5, 7),    // often written just "sus"
            "dim" to intArrayOf(0, 3, 6),
            "aug" to intArrayOf(0, 4, 8)
        )
        for (r in roots.indices) {
            for ((suffix, ivals) in qualities) {
                val display = (flat[roots[r]] ?: roots[r]) + suffix
                // Transpose each interval to this root, wrapping past B back to C (% 12).
                list.add(Template(display, ivals.map { (it + r) % 12 }.toIntArray()))
            }
        }
        return list
    }

    /**
     * Builds a "chroma vector": 12 numbers, one per pitch class, summing how much
     * energy the audio has at each note REGARDLESS of octave. This is the key to
     * chord detection — a C major chord lights up bins C, E, G no matter which
     * octave the strings are played in.
     */
    fun chroma(buffer: FloatArray): DoubleArray {
        val mags = FFT.magnitude(buffer)
        val n = mags.size * 2              // original FFT size (magnitude gave us n/2)
        val chroma = DoubleArray(12)

        for (bin in 1 until mags.size) {
            // Convert this FFT bin's index to its real-world frequency.
            val freq = bin * sampleRate.toDouble() / n
            // Ignore sub-bass rumble and very high content — outside guitar's useful range.
            if (freq < 70 || freq > 5000) continue
            // Which of the 12 pitch classes does this frequency belong to?
            val midi = 69 + 12 * (ln(freq / 440.0) / ln(2.0))
            val pc = ((midi.roundToInt() % 12) + 12) % 12
            // Add this bin's strength into that pitch class's bucket.
            chroma[pc] += mags[bin]
        }

        // Normalize so the loudest pitch class = 1.0. Makes scoring volume-independent.
        val max = chroma.maxOrNull() ?: 1.0
        if (max > 0) for (i in chroma.indices) chroma[i] /= max
        return chroma
    }

    data class ChordResult(val name: String, val score: Double)

    /**
     * Scores the chroma vector against every chord template and returns the best match.
     * @param minScore the confidence floor. If even the best-scoring chord is below
     *        this, we return null ("no chord") instead of reporting a weak guess.
     *        Higher minScore = stricter = fewer false chord detections.
     */
    fun detect(buffer: FloatArray, minScore: Double = 0.20): ChordResult? {
        val c = chroma(buffer)
        // Near-total silence → nothing to match against, bail out early.
        if (c.sum() < 0.5) return null

        var best: Template? = null
        var bestScore = -1.0

        // Compare the audio's chroma vector against every chord template we built.
        for (t in templates) {
            val set = t.pitches.toHashSet()   // this chord's pitch classes, for fast lookup
            var inChord =
                0.0    // accumulated energy landing ON the chord's notes (we want this high)
            var outChord = 0.0   // accumulated energy on notes NOT in the chord (we want this low)

            // Split all 12 pitch classes into "belongs to this chord" vs "doesn't".
            for (pc in 0 until 12) {
                if (pc in set) inChord += c[pc] else outChord += c[pc]
            }

            // Score rewards energy on the chord tones and penalizes energy elsewhere.
            // Dividing each part by its count keeps chords of different sizes comparable,
            // so a 4-note 7th chord isn't unfairly favored over a 3-note triad.
            val score = inChord / t.pitches.size - 0.5 * outChord / (12 - t.pitches.size)

            // Keep track of the single best-scoring template.
            if (score > bestScore) {
                bestScore = score; best = t
            }
        }

        // ---- The strictness gate ----
        // Even the winner must clear minScore. If the best match is still weak
        // (ambiguous audio, noise, a chord we don't have a template for), we'd rather
        // say "nothing" than confidently report a wrong chord.
        if (bestScore < minScore) return null

        return best?.let { ChordResult(it.name, bestScore) }
    }
}