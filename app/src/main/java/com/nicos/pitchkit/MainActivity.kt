package com.nicos.pitchkit

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.nicos.pitchkit.ui.theme.PitchKitTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment

class MainActivity : ComponentActivity() {
    //private val audioTuner = AudioTuner()

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
                //val tuningResult = PitchConverter.getNoteFromFrequency(136.00f/*floatBuffer.lastOrNull() ?: 130.81f*/)
                /* PureKotlinChordDetector().startListening {
                     runOnUiThread {
                         Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                     }
                 }*/
                //Log.d("AudioTuner", tuningResult?.noteName + " " + tuningResult?.octave + " " + tuningResult?.centsDeviation)
                /*audioTuner.startListening({ result ->
                    runOnUiThread {
                        Toast.makeText(this, result?.noteName ?: "no note found", Toast.LENGTH_SHORT).show()
                    }
                })*/
            }
        }
    }
}


class AudioCapture(
    val sampleRate: Int = 44100,        // 44.1 kHz — standard, captures up to ~22 kHz (Nyquist)
    val bufferSize: Int = 8192,         // MUST be power of 2 for the FFT. ~186ms of audio.
) {
    // Bigger buffer = better low-freq resolution (needed for
    // low E at 82 Hz) but slower updates.
    private var recorder: AudioRecord? = null

    @Volatile
    private var running = false   // @Volatile: read/written from two threads (UI + capture)

    // The OS tells us the smallest buffer it will accept for these settings.
    // If we request less than this, AudioRecord fails to initialize.
    private val minBuf = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    @SuppressLint("MissingPermission") // caller must hold RECORD_AUDIO permission at runtime
    fun start(onBuffer: (FloatArray) -> Unit) {
        // Internal OS buffer should be larger than our read chunk so audio isn't
        // dropped if our processing thread briefly stalls. We double it for headroom.
        val recordBuf = maxOf(minBuf, bufferSize * 2)

        recorder = AudioRecord(
            // MIC = raw mic. VOICE_RECOGNITION often disables OS-level noise
            // suppression/AGC, which is BETTER for us — we want the untouched signal.
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,        // guitar is mono; one channel is enough
            AudioFormat.ENCODING_PCM_16BIT,     // 16-bit samples: values -32768..32767
            recordBuf
        )
        recorder?.startRecording()
        running = true

        // All audio reading + processing happens on THIS background thread,
        // never the UI thread (reading blocks until samples are available).
        Thread {
            val shortBuf = ShortArray(bufferSize)   // raw 16-bit samples from hardware
            val floatBuf = FloatArray(bufferSize)   // normalized copy for DSP math
            while (running) {
                // read() blocks until it fills the buffer (or returns fewer samples).
                val read = recorder?.read(shortBuf, 0, bufferSize) ?: 0
                if (read > 0) {
                    // Convert 16-bit int range to floating-point -1.0..1.0.
                    // DSP algorithms are simpler and avoid overflow in float.
                    for (i in 0 until read) {
                        floatBuf[i] = shortBuf[i] / 32768f
                    }
                    // Hand a copy to the consumer (copy because we reuse floatBuf next loop).
                    onBuffer(floatBuf.copyOf(read))
                }
            }
        }.start()
    }

    fun stop() {
        running = false        // signals the loop to exit
        recorder?.stop()
        recorder?.release()    // release hardware — mandatory, or the mic stays locked
        recorder = null
    }
}

object FFT {
    /**
     * In-place iterative radix-2 Cooley–Tukey FFT.
     * Converts a signal from the TIME domain (amplitude over time) to the
     * FREQUENCY domain (how much of each frequency is present).
     * re = real parts (your samples), im = imaginary parts (start at 0).
     * Both arrays are overwritten with the result.
     */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n == 1) return
        require(n and (n - 1) == 0) { "length must be power of 2" }
        // (n and n-1 == 0) is a bit trick that's true only for powers of 2.

        // ---- STAGE 1: Bit-reversal permutation ----
        // The FFT algorithm produces outputs in "bit-reversed" index order.
        // To get normal order, we pre-shuffle inputs: index i swaps with the
        // index formed by reversing i's bits (e.g. 001 <-> 100).
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            // This loop computes the bit-reversed value of i incrementally.
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {   // swap only once per pair
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        // ---- STAGE 2: Butterfly computation ----
        // We combine the signal in stages, doubling the sub-transform size each
        // pass: 2, 4, 8, ... n. Each stage merges pairs using a "twiddle factor"
        // (a rotating complex number = a point on the unit circle).
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len          // angle step for this stage
            val wRe = Math.cos(ang)                 // twiddle base (real)
            val wIm = Math.sin(ang)                 // twiddle base (imaginary)
            var i = 0
            while (i < n) {
                // cur = current twiddle factor, starts at 1 (angle 0) and rotates.
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    // The "butterfly": take two values, one gets rotated by the
                    // twiddle, then we compute their sum and difference.
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    // b = second value * twiddle  (complex multiplication)
                    val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    // Combine: sum goes to first slot, difference to second.
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe
                    im[i + k + len / 2] = aIm - bIm
                    // Rotate the twiddle factor by the base angle (complex multiply).
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1     // double the sub-transform size
        }
    }

    /**
     * Returns the magnitude spectrum: how strong each frequency bin is.
     * Only the first n/2 bins are meaningful (the rest are mirror images —
     * a property of real-valued input signals).
     */
    fun magnitude(samples: FloatArray): DoubleArray {
        // Use the largest power of 2 that fits, since FFT requires it.
        val n = Integer.highestOneBit(samples.size)
        val re = DoubleArray(n)
        val im = DoubleArray(n)   // imaginary starts all zero: input is real audio

        // ---- Windowing (Hann window) ----
        // Chopping a continuous signal into a finite buffer creates fake
        // frequencies at the edges ("spectral leakage"). Multiplying by a window
        // that tapers to zero at both ends greatly reduces this artifact.
        for (i in 0 until n) {
            val w = 0.5 * (1 - Math.cos(2 * Math.PI * i / (n - 1)))  // Hann curve
            re[i] = samples[i] * w
        }

        transform(re, im)

        // Magnitude of each complex bin = sqrt(real² + imag²) = frequency strength.
        // hypot() computes that safely without overflow.
        val mags = DoubleArray(n / 2)
        for (i in 0 until n / 2) {
            mags[i] = Math.hypot(re[i], im[i])
        }
        return mags
    }
}

class YinPitchDetector(
    private val sampleRate: Int,
    private val threshold: Double = 0.15   // lower = stricter; 0.10–0.15 works well for guitar
) {
    /**
     * Detects the fundamental frequency of a SINGLE note.
     * YIN works in the time domain by finding the period at which the signal
     * most closely repeats itself. It resists the "octave errors" that plague
     * simple FFT peak-picking (where a harmonic is mistaken for the fundamental).
     * Returns Hz, or -1 if no clear pitch.
     */
    fun detect(buffer: FloatArray): Float {
        val tau = buffer.size / 2     // max lag we test (tau = "period" candidate)
        val yin = DoubleArray(tau)

        // ---- STEP 1: Difference function ----
        // For each candidate period t, measure how DIFFERENT the signal is from
        // itself shifted by t samples. If the signal repeats every t samples
        // (i.e. t is the true period), this difference is near zero.
        for (t in 1 until tau) {
            var sum = 0.0
            for (i in 0 until tau) {
                val delta = buffer[i] - buffer[i + t]
                sum += delta * delta     // squared difference
            }
            yin[t] = sum
        }

        // ---- STEP 2: Cumulative mean normalization ----
        // The raw difference is always ~0 at t=0, which we must ignore. This step
        // normalizes each value against the running average so we can use a fixed
        // threshold, and avoids picking the trivial t=0 dip.
        yin[0] = 1.0
        var runningSum = 0.0
        for (t in 1 until tau) {
            runningSum += yin[t]
            yin[t] *= t / runningSum
        }

        // ---- STEP 3: Absolute threshold ----
        // Find the FIRST period where the normalized difference dips below the
        // threshold — this is the fundamental (not a harmonic). We then keep
        // descending into that dip to find its true lowest point.
        var tauEstimate = -1
        var t = 2
        while (t < tau) {
            if (yin[t] < threshold) {
                // walk down to the local minimum of this valley
                while (t + 1 < tau && yin[t + 1] < yin[t]) t++
                tauEstimate = t
                break
            }
            t++
        }
        if (tauEstimate == -1) return -1f   // no repeating pattern found = no pitch

        // ---- STEP 4: Parabolic interpolation ----
        // The true period rarely lands exactly on an integer sample. Fitting a
        // parabola through the dip and its two neighbors gives sub-sample accuracy,
        // which matters a lot for showing tuning in cents.
        val betterTau = parabolicInterp(yin, tauEstimate)

        // frequency = sampleRate / period
        return (sampleRate / betterTau).toFloat()
    }

    /** Fits a parabola through 3 points around the dip to refine the minimum. */
    private fun parabolicInterp(yin: DoubleArray, tau: Int): Double {
        val x0 = if (tau > 0) tau - 1 else tau            // left neighbor
        val x2 = if (tau + 1 < yin.size) tau + 1 else tau // right neighbor
        // Edge cases where a neighbor doesn't exist: just pick the smaller point.
        if (x0 == tau) return if (yin[tau] <= yin[x2]) tau.toDouble() else x2.toDouble()
        if (x2 == tau) return if (yin[tau] <= yin[x0]) tau.toDouble() else x0.toDouble()
        val s0 = yin[x0];
        val s1 = yin[tau];
        val s2 = yin[x2]
        // Standard vertex-of-parabola formula. denom==0 means flat → no shift.
        val denom = 2 * (2 * s1 - s2 - s0)
        return if (denom == 0.0) tau.toDouble() else tau + (s2 - s0) / denom
    }
}

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

        val nearest = Math.round(midi).toInt()      // closest actual note
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
    private fun log2(x: Double) = Math.log(x) / Math.log(2.0)
}

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
            val midi = 69 + 12 * (Math.log(freq / 440.0) / Math.log(2.0))
            val pc = ((Math.round(midi).toInt() % 12) + 12) % 12
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

class TunerEngine(
    private val sampleRate: Int = 44100,
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
            val rms = Math.sqrt(buf.map { (it * it).toDouble() }.average())
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