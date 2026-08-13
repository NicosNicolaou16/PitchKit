package com.nicos.pitchkit.tuner

import kotlin.math.ln
import kotlin.math.roundToInt

object NoteMapper {
    // 12 semitones. Index 0 = C, matching the MIDI note numbering below.
    private val names = arrayOf(
        "C", "C#", "D", "D#", "E", "F",
        "F#", "G", "G#", "A", "A#", "B"
    )

    // If you'd rather display flats (Bb instead of A#), map sharp → flat here.
    private val flatAliases = mapOf(
        "A#" to "Bb", "C#" to "Db", "D#" to "Eb", "F#" to "Gb", "G#" to "Ab"
    )

    data class NoteResult(
        val name: String,            // e.g. "E"
        val nameWithOctave: String,  // e.g. "E2"
        val cents: Double,           // how far off perfect tuning (-50..+50). 0 = in tune.
        val frequency: Float
    )

    /**
     * Converts a frequency to the nearest musical note.
     * Music is logarithmic: every octave DOUBLES the frequency, and each octave
     * has 12 equal semitones. We use the MIDI standard where A4 (440 Hz) = note 69.
     */
    fun frequencyToNote(freq: Float, useFlats: Boolean = false): NoteResult? {
        if (freq <= 0) return null

        // Convert frequency to a fractional MIDI number.
        // log2(freq/440) tells us how many octaves above/below A4 we are;
        // ×12 converts octaves to semitones; +69 shifts to MIDI numbering.
        val midi = 69 + 12 * log2((freq / 440.0))

        val nearest = midi.roundToInt()      // closest actual note
        // The leftover fraction, ×100, is the tuning error in cents
        // (100 cents = 1 semitone). This drives the tuner needle.
        val cents = (midi - nearest) * 100.0

        // Map the MIDI number to a name + octave.
        val noteIdx = ((nearest % 12) + 12) % 12    // (+12)%12 guards against negatives
        val octave = nearest / 12 - 1               // MIDI octave convention
        var name = names[noteIdx]
        if (useFlats) name = flatAliases[name] ?: name

        return NoteResult(name, "$name$octave", cents, freq)
    }

    // Kotlin's stdlib has log2, but this makes the base-change explicit.
    private fun log2(x: Double) = ln(x) / ln(2.0)
}