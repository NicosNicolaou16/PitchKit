package com.nicos.pitchkit.tuner.helpers

import kotlin.math.abs
import kotlin.math.ln

// Target frequency for each peg, used to separate the two E strings.
private val stringFreqs = mapOf(
    "E" to 82.41,   // low E, 6th string
    "A" to 110.00,
    "D" to 146.83,
    "G" to 196.00,
    "B" to 246.94,
    "e" to 329.63,  // high e, 1st string
)

// How far the detected freq is from a target, in cents (log scale).
fun centsFrom(resultFreq: Double, target: Double): Double =
    if (resultFreq <= 0) Double.MAX_VALUE
    else 1200.0 * (ln(resultFreq / target) / ln(2.0))

// A peg is "current" when the detected note letter matches AND (for the two E
// strings) the frequency is near THAT E's octave. Non-E strings only need the
// letter, but checking frequency for all of them is harmless and more robust.
fun isPegActive(resultFreq: Double, resultNote: String, peg: String): Boolean {
    if (resultNote == "-") return false
    val target = stringFreqs[peg] ?: return false
    // Case-sensitive letter check so "E" and "e" don't cross-match by name...
    val letterMatches = resultNote.equals(peg, ignoreCase = true)
    if (!letterMatches) return false
    // ...then frequency must be within ~1 semitone of THIS peg's octave.
    return abs(centsFrom(resultFreq = resultFreq, target = target)) < 100
}