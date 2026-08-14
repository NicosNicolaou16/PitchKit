package com.nicos.pitchkit.tuner.models

/**
 * Per-instrument configuration. Everything the DSP pipeline would otherwise
 * hardcode for guitar lives here, so supporting a new instrument means adding a
 * preset below — not editing the detectors.
 */
data class InstrumentProfile(
    // Human-readable label, e.g. "Guitar". Useful for UI and logging.
    val name: String,
    // Lowest frequency (Hz) analysis considers; below this is rumble/noise for
    // this instrument. Guitar ~70 (low E is 82); ukulele much higher (~240).
    val minFreq: Double,
    // Highest frequency (Hz) considered for chroma; above this is mostly hiss.
    val maxFreq: Double,
    // The chord bass note won't be scanned above this (Hz). Sits just above the
    // instrument's lowest possible note.
    val bassCeiling: Double,
    // Frequency (Hz) around which harmonic down-weighting is centred — roughly the
    // middle of the instrument's fundamental range.
    val harmonicPivot: Double,
    // Standard tuning: the note each string makes when played OPEN (no fret),
    // listed low → high. A tuner uses this to show which string you're nearest.
    val openStrings: List<OpenString>,
    // Loudness gate (RMS). Frames quieter than this are treated as silence.
    val rmsGate: Double = 0.01,
    // Display flats (Bb) instead of sharps (A#). Purely cosmetic.
    val useFlats: Boolean = false,
) {
    /**
     * One open string in the instrument's standard tuning.
     * "Open" = played with no fret pressed, i.e. the string's natural pitch.
     * @param name      the note, e.g. "E2" (low E) or "A4".
     * @param frequency that note's frequency in Hz, e.g. 82.41 for low E.
     */
    data class OpenString(val name: String, val frequency: Double)

    companion object {
        /**
         * Standard 6-string guitar, E-standard tuning. Lowest open string is E2
         * (~82 Hz), so the window starts just below it. These mirror the values
         * your original guitar-only code used.
         */
        val Guitar = InstrumentProfile(
            name = "Guitar",
            minFreq = 70.0,        // just below low E (82 Hz)
            maxFreq = 5000.0,
            bassCeiling = 400.0,   // guitar chord roots stay below ~400 Hz
            harmonicPivot = 200.0, // middle-ish of the guitar's range
            openStrings = listOf(
                // 6 open strings, low → high
                OpenString("E2", 82.41),
                OpenString("A2", 110.00),
                OpenString("D3", 146.83),
                OpenString("G3", 196.00),
                OpenString("B3", 246.94),
                OpenString("E4", 329.63),
            ),
        )

        /**
         * Standard soprano/concert ukulele, GCEA tuning. Sits MUCH higher than a
         * guitar (lowest note ~C4, 262 Hz), so every boundary shifts UP — using
         * the guitar's 70 Hz floor here would let low noise pollute the chroma.
         */
        val Ukulele = InstrumentProfile(
            name = "Ukulele",
            minFreq = 240.0,       // just below C4 (262 Hz)
            maxFreq = 6000.0,
            bassCeiling = 700.0,   // uke roots sit higher than a guitar's
            harmonicPivot = 400.0, // middle-ish of the uke's higher range
            openStrings = listOf(
                // Re-entrant tuning: the G string is HIGHER than C and E, not lower.
                OpenString("G4", 392.00),
                OpenString("C4", 261.63),
                OpenString("E4", 329.63),
                OpenString("A4", 440.00),
            ),
        )

        /**
         * Standard 4-string bass, E-standard — one octave BELOW a guitar. Lowest
         * open string is E1 (~41 Hz), so every boundary shifts DOWN. Bass energy
         * concentrates low, so maxFreq can stay modest.
         */
        val Bass = InstrumentProfile(
            name = "Bass",
            minFreq = 35.0,        // just below low E1 (41 Hz)
            maxFreq = 3000.0,
            bassCeiling = 250.0,
            harmonicPivot = 100.0, // bass fundamentals are low
            openStrings = listOf(
                OpenString("E1", 41.20),
                OpenString("A1", 55.00),
                OpenString("D2", 73.42),
                OpenString("G2", 98.00),
            ),
        )
    }
}