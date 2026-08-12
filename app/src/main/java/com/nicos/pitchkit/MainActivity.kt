package com.nicos.pitchkit

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nicos.pitchkit.ui.theme.PitchKitTheme
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PitchKitTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val engine = TunerEngine(
                        rmsGate = 0.02        // ignore quiet sounds  ← start here
                    )
                    var resultNote by remember { mutableStateOf("-") }
                    engine.start { result ->
                        runOnUiThread {
                            resultNote = when (result) {
                                is TunerEngine.Result.Note ->
                                    "${result.name}  (${"%.0f".format(result.cents)}¢)"

                                is TunerEngine.Result.Chord -> result.name
                                TunerEngine.Result.Silence -> "—"
                            }
                            //tunerTextView.text = text
                            Log.d("AudioTuner", resultNote)
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(resultNote)
                    }

                    DisposableEffect(
                        engine
                    ) {
                        onDispose {
                            engine.stop()
                        }
                    }
                }
            }
        }
    }
}












class TunerEngine(
    sampleRate: Int = 44100,
    bufferSize: Int = 8192,
    private val rmsGate: Double = 0.1   // was 0.01. Higher = less sensitive.
) {
    private val capture = AudioCapture(sampleRate, bufferSize)
    private val yin = YinPitchDetector(sampleRate)
    private val chordDetector = ChordDetector(sampleRate)

    // Sealed class = the result is exactly one of these cases; the UI can
    // exhaustively handle each with a `when`.
    sealed class Result {
        data class Note(val name: String, val cents: Double, val freq: Float) : Result()
        data class Chord(val name: String) : Result()
        object Silence : Result()
    }

    // Keeps the last few results so we can suppress one-frame flickers.
    private val history = ArrayDeque<String>()
    private var lastStable: Result = Result.Silence
    private val requiredAgreement = 2   // was 3 — easier to reach while testing

    fun start(onResult: (Result) -> Unit) {
        capture.start { raw ->
            val buf = preProcess(raw)   // clean up the signal first

            // ---- Energy gate ----
            // RMS = root-mean-square = perceived loudness of the buffer.
            // Below a small threshold we treat it as silence and skip processing.
            val rms = sqrt(buf.map { (it * it).toDouble() }.average())
            // Only proceed if the sound is clearly above the gate.
            if (rms < rmsGate) {
                onResult(Result.Silence); return@start
            }

            // ---- Decide: single note or chord? ----
            // Count how many pitch classes are strongly present. One dominant
            // pitch class → a single note; several → a chord.
            val chroma = chordDetector.chroma(buf)
            val strongPitches = chroma.count { it > 0.50 }

            val result = if (strongPitches <= 1) {
                // Monophonic → YIN gives precise pitch + tuning in cents.
                val f = yin.detect(buf)
                NoteMapper.frequencyToNote(f)?.let {
                    Result.Note(it.name, it.cents, it.frequency)
                } ?: Result.Silence
            } else {
                // Polyphonic → template matching on the chroma vector.
                chordDetector.detect(buf)?.let { Result.Chord(it.name) } ?: Result.Silence
            }

            onResult(smooth(result))
        }
    }

    fun stop() = capture.stop()

    /**
     * Pre-processing chain to reduce noise before analysis.
     */
    private fun preProcess(raw: FloatArray): FloatArray {
        val out = raw.copyOf()

        // ---- 1. DC offset removal ----
        // Some mics add a constant bias so the waveform isn't centered on zero.
        // Subtracting the average re-centers it, which the pitch math assumes.
        val mean = out.average().toFloat()
        for (i in out.indices) out[i] -= mean

        // ---- 2. First-order high-pass filter ----
        // Attenuates low-frequency rumble (handling noise, AC hum, foot taps)
        // below ~70 Hz while leaving guitar notes intact. This recurrence is the
        // standard one-pole high-pass; alpha near 1.0 sets the cutoff low.
        var prev = 0f       // previous raw input sample
        var prevOut = 0f    // previous filtered output sample
        val alpha = 0.95f
        for (i in out.indices) {
            val cur = out[i]
            val hp = alpha * (prevOut + cur - prev)
            prev = cur
            prevOut = hp
            out[i] = hp
        }
        return out
    }

    /**
     * Debounces the output: only reports a result once it has appeared in the
     * majority of the last few frames, preventing rapid flicker between guesses.
     */
    private fun smooth(r: Result): Result {
        val key = when (r) {
            is Result.Note -> r.name
            is Result.Chord -> r.name
            Result.Silence -> "~"
        }
        history.addLast(key)
        if (history.size > 4) history.removeFirst()

        // Find the most common result in the recent window.
        val majority = history.groupingBy { it }.eachCount().maxByOrNull { it.value }

        // Only update the output if one result dominates the window.
        // Otherwise keep showing the last stable result (no flicker on transients).
        if (majority != null && majority.value >= requiredAgreement) {
            lastStable = r
        }
        return lastStable
    }
}