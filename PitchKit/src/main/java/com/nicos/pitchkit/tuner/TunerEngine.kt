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
    private val sampleRate: Int = 44100,
    bufferSize: Int = 8192,
) {
    private val capture = AudioCapture(sampleRate, bufferSize)
    private val yin = YinPitchDetector(
        sampleRate = sampleRate,
        minFreq = profile.minFreq,
        maxFreq = profile.maxFreq
    )

    // Pass the profile down so the detector uses this instrument's frequency bounds.
    private val chordDetector = ChordDetector(sampleRate, profile)

    // Loudness gate now comes from the profile rather than being hardcoded.
    private val rmsGate = profile.rmsGate

    // Cutoff at half the instrument's floor: guitar ≈ 35 Hz, bass ≈ 17.5 Hz
    private val highPass = HighPassFilter(cutoffHz = profile.minFreq / 2, sampleRate = sampleRate)

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

    // Previous frame's loudness, used to detect a new strum (sudden energy jump)
    private var prevRms = 0.0

    private companion object {
        // 1.5 = +3.5 dB. Lower it (e.g. 1.3) if soft strums aren't detected;
        // raise it if sustained chords keep resetting
        const val ONSET_RATIO = 1.5
    }

    fun start() = callbackFlow<Result> {
        // A new recording session must not inherit anything from the previous one:
        highPass.reset()              // filter memory (prevIn/prevOut) → avoids a click on the first buffer
        chordDetector.reset()         // chord hysteresis + chroma history
        history.clear()               // debounce window
        // otherwise the old note is shown until the first new majority
        lastStable =
            Result.Silence
        prevRms = 0.0                 // no previous frame yet in this session

        capture.start(scope = this, onError = { close(it) }) { raw ->
            val buf = highPass.process(raw) // clean the signal first

            // Energy gate: skip frames quieter than the profile's rmsGate.
            var sumSq = 0.0
            for (s in buf) sumSq += s * s
            val rms = sqrt(sumSq / buf.size)

            // New strum = energy jumps by 50%+ over the previous frame. Clear the chroma
            // average so the old chord doesn't blend into the new one for ~2 frames.
            if (prevRms > 0.0 && rms > prevRms * ONSET_RATIO) chordDetector.onNewStrum()
            prevRms = rms

            if (rms < rmsGate) {
                chordDetector.reset()   // clear hysteresis so the next chord starts clean
                trySend(Result.Silence)
                return@start
            }

            // One FFT per frame, shared by the note/chord decision and chord detection
            val mags = FFT.magnitudePadded(buf, padFactor = 2)
            // Decide single note vs chord by counting strongly-present pitch classes.
            val chroma = chordDetector.chroma(mags = mags)
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
                chordDetector.detect(mags = mags, rawChroma = chroma)?.let { Result.Chord(it.name) }
                    ?: Result.Silence
            }

            trySend(smooth(result))
        }
        awaitClose { stop() }   // flow canceled → release the mic
    }
        .buffer(capacity = Channel.CONFLATED)  // keep only the latest frame under load
        .flowOn(Dispatchers.IO)                // all audio work off the main thread

    fun stop() = capture.stop()

    /** Debounces output: only updates once one result dominates the recent window. */
    private fun smooth(r: Result): Result {
        val key = when (r) {
            is Result.Note -> r.name
            is Result.Chord -> r.name
            Result.Silence -> "~"
        }
        history.addLast(key)
        if (history.size > 4) history.removeFirst()
        if (history.count { it == key } >= requiredAgreement) {
            lastStable = r   // also refreshes cents/freq while the same note is held
        }
        return lastStable
    }
}