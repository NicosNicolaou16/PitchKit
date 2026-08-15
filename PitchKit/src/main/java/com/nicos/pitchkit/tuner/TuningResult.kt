package com.nicos.pitchkit.tuner

/**
 * The public result of tuner detection. This is the type library consumers
 * receive — the internal TunerEngine and its own Result type stay hidden.
 */
sealed class TuningResult {
    /**
     * A single detected note.
     * @param name  note name, e.g. "E" or "A#".
     * @param cents deviation from perfect pitch (-50..+50); 0 = in tune.
     * @param freq  detected frequency in Hz.
     */
    data class Note(
        val name: String,
        val cents: Double,
        val freq: Float,
    ) : TuningResult()

    /**
     * A detected chord, e.g. "Am" or "Cmaj7".
     * @param name the chord name.
     */
    data class Chord(val name: String) : TuningResult()

    /** No sound / below the detection threshold. */
    object Silence : TuningResult()
}