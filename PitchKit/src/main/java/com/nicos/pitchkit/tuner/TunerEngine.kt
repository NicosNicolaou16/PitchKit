package com.nicos.pitchkit.tuner

import com.nicos.pitchkit.tuner.models.InstrumentProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.sqrt

/**
 * @param profile    the instrument to tune/detect for. Defaults to guitar so
 *                   existing callers keep working unchanged.
 * @param sampleRate the microphone's sample rate.
 * @param bufferSize the number of samples per frame.
 */
internal class TunerEngine(
    private val profile: InstrumentProfile = InstrumentProfile.Guitar,
    sampleRate: Int = 44100,
    bufferSize: Int = 8192,
) {
    private val capture = AudioCapture(sampleRate, bufferSize)
    private val yin = YinPitchDetector(sampleRate)

    // Pass the profile down so the detector uses this instrument's frequency bounds.
    private val chordDetector = ChordDetector(sampleRate, profile)

    // Loudness gate now comes from the profile rather than being hardcoded.
    private val rmsGate = profile.rmsGate

    // Exactly one of these per frame; the UI handles each with a `when`.
    sealed class Result {
        data class Note(val name: String, val cents: Double, val freq: Float) : Result()
        data class Chord(val name: String) : Result()
        object Silence : Result()
    }

    // Debounce state to suppress one-frame flickers.
    private val history = ArrayDeque<String>()
    private var lastStable: Result = Result.Silence
    private val requiredAgreement = 2

    fun start() = callbackFlow<Result> {
        capture.start(scope = this) { raw ->
            val buf = preProcess(raw)   // clean the signal first

            // Energy gate: skip frames quieter than the profile's rmsGate.
            val rms = sqrt(buf.map { (it * it).toDouble() }.average())
            if (rms < rmsGate) {
                chordDetector.reset()   // clear hysteresis so the next chord starts clean
                trySend(Result.Silence)
                return@start
            }

            // Decide single note vs chord by counting strongly-present pitch classes.
            val chroma = chordDetector.chroma(buf)
            val strongPitches = chroma.count { it > 0.50 }

            val result = if (strongPitches <= 1) {
                // Monophonic → YIN for precise pitch + cents. Uses the profile's
                // flat/sharp preference for the note name.
                val f = yin.detect(buf)
                NoteMapper.frequencyToNote(f, useFlats = profile.useFlats)?.let {
                    Result.Note(it.name, it.cents, it.frequency)
                } ?: Result.Silence
            } else {
                // Polyphonic → chord template matching.
                chordDetector.detect(buf)?.let { Result.Chord(it.name) } ?: Result.Silence
            }

            trySend(smooth(result))
        }
        awaitClose { stop() }   // flow cancelled → release the mic
    }
        .buffer(capacity = Channel.CONFLATED)  // keep only the latest frame under load
        .flowOn(Dispatchers.IO)                // all audio work off the main thread

    fun stop() = capture.stop()

    /** Pre-processing: DC-offset removal + a one-pole high-pass to cut rumble. */
    private fun preProcess(raw: FloatArray): FloatArray {
        val out = raw.copyOf()
        // Remove DC bias so the waveform is centred on zero (pitch math assumes this).
        val mean = out.average().toFloat()
        for (i in out.indices) out[i] -= mean
        // First-order high-pass: attenuates low-frequency rumble.
        var prev = 0f;
        var prevOut = 0f
        val alpha = 0.95f
        for (i in out.indices) {
            val cur = out[i]
            val hp = alpha * (prevOut + cur - prev)
            prev = cur; prevOut = hp
            out[i] = hp
        }
        return out
    }

    /** Debounces output: only updates once one result dominates the recent window. */
    private fun smooth(r: Result): Result {
        val key = when (r) {
            is Result.Note -> r.name
            is Result.Chord -> r.name
            Result.Silence -> "~"
        }
        history.addLast(key)
        if (history.size > 4) history.removeFirst()
        val majority = history.groupingBy { it }.eachCount().maxByOrNull { it.value }
        if (majority != null && majority.value >= requiredAgreement) {
            lastStable = r
        }
        return lastStable
    }
}