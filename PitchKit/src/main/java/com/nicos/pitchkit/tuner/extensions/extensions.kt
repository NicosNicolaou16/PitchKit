package com.nicos.pitchkit.tuner.extensions

import com.nicos.pitchkit.tuner.TunerEngine
import com.nicos.pitchkit.tuner.TuningResult

// Internal: bridges the private engine result to the public API type.
internal fun TunerEngine.Result.toPublic(): TuningResult = when (this) {
    is TunerEngine.Result.Note -> TuningResult.Note(name = name, cents = cents, freq = freq)
    is TunerEngine.Result.Chord -> TuningResult.Chord(name = name)
    TunerEngine.Result.Silence -> TuningResult.Silence
}