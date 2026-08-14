package com.nicos.pitchkit.tuner

/**
 * The public result of tuner detection. This is the type library consumers
 * receive — the internal TunerEngine and its own Result type stay hidden.
 */
sealed class TuningResult {
    /**
     * A single detected note.
     * @param name  note name, e.g. "E" or "A#".
     * @param freq  detected frequency in Hz.
     * @param cents deviation from perfect pitch (-50..+50); 0 = in tune.
     */
    data class Note(
        val name: String,
        val freq: Float,
        val cents: Double,
    ) : TuningResult()

    /**
     * A detected chord, e.g. "Am" or "Cmaj7".
     * @param name the chord name.
     */
    data class Chord(val name: String) : TuningResult()

    /** No sound / below the detection threshold. */
    object Silence : TuningResult()
}