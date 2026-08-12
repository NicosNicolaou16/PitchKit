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
    private val rmsGate: Double = 0.05   // was 0.01. Higher = less sensitive.
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

// Older version


class PureKotlinChordDetector {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    private val sampleRate = 44100
    private val bufferSize = 4096 // MUST be a power of 2 for Cooley-Tukey FFT

    private val chromaVector = FloatArray(12)

    @SuppressLint("MissingPermission")
    fun startListening(onChordDetected: (String) -> Unit) {
        if (isRecording) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        audioRecord?.startRecording()
        isRecording = true

        recordingThread = Thread {
            val audioBuffer = ShortArray(bufferSize)

            // We use two separate arrays for the Real and Imaginary parts of the complex numbers
            val real = FloatArray(bufferSize)
            val imag = FloatArray(bufferSize)

            while (isRecording) {
                val readResult = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0

                if (readResult == bufferSize) {

                    // 1. Prepare data: Convert PCM to Float and clear imaginary array
                    for (i in 0 until bufferSize) {
                        real[i] = audioBuffer[i] / 32768.0f
                        imag[i] = 0f
                    }

                    // 2. Run our custom FFT math
                    computeFFT(real, imag)

                    // 3. Process into chords
                    val detectedChord = processFFTIntoChord(real, imag)

                    if (detectedChord != "Unknown") {
                        onChordDetected(detectedChord)
                    }
                }
            }
        }
        recordingThread?.start()
    }

    /**
     * Cooley-Tukey Radix-2 FFT Algorithm in pure Kotlin.
     * Modifies the 'real' and 'imag' arrays in-place.
     */
    private fun computeFFT(real: FloatArray, imag: FloatArray) {
        val n = real.size

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                // Swap real parts
                val tempReal = real[i]
                real[i] = real[j]
                real[j] = tempReal

                // Swap imaginary parts
                val tempImag = imag[i]
                imag[i] = imag[j]
                imag[j] = tempImag
            }
            var m = n / 2
            while (m <= j) {
                j -= m
                m /= 2
            }
            j += m
        }

        // Danielson-Lanczos logic (The Butterfly operations)
        var length = 2
        while (length <= n) {
            val halfLength = length / 2
            val angle = -2.0 * PI / length
            val wReal = cos(angle).toFloat()
            val wImag = sin(angle).toFloat()

            for (i in 0 until n step length) {
                var currentWReal = 1.0f
                var currentWImag = 0.0f

                for (k in 0 until halfLength) {
                    val index = i + k
                    val matchIndex = index + halfLength

                    val tempReal = currentWReal * real[matchIndex] - currentWImag * imag[matchIndex]
                    val tempImag = currentWReal * imag[matchIndex] + currentWImag * real[matchIndex]

                    real[matchIndex] = real[index] - tempReal
                    imag[matchIndex] = imag[index] - tempImag

                    real[index] += tempReal
                    imag[index] += tempImag

                    // Update phase
                    val nextWReal = currentWReal * wReal - currentWImag * wImag
                    currentWImag = currentWReal * wImag + currentWImag * wReal
                    currentWReal = nextWReal
                }
            }
            length *= 2
        }
    }

    private fun processFFTIntoChord(real: FloatArray, imag: FloatArray): String {
        for (i in 0 until 12) chromaVector[i] = 0f

        val binResolution = sampleRate.toFloat() / bufferSize

        // Only iterate through the first half (Nyquist limit)
        for (i in 0 until bufferSize / 2) {

            // Calculate magnitude: sqrt(real^2 + imag^2)
            val magnitude = sqrt(real[i] * real[i] + imag[i] * imag[i])
            val frequency = i * binResolution

            if (frequency > 70f && frequency < 1200f && magnitude > 5.0f) {
                val pitch = 69.0 + 12.0 * log2(frequency / 440.0)
                val noteIndex = (pitch.roundToInt() + 12000) % 12
                chromaVector[noteIndex] += magnitude
            }
        }

        val topNotes = chromaVector.withIndex()
            .filter { it.value > 0f }
            .sortedByDescending { it.value }
            .take(3)
            .map { it.index }

        if (topNotes.size < 3) return "Unknown"

        return ChordDictionary.identifyChord(topNotes)
    }

    fun stopListening() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}

object ChordDictionary {

    // The 12 notes, matching your PitchConverter
    private val NOTE_NAMES = arrayOf(
        "C", "C#", "D", "D#", "E", "F",
        "F#", "G", "G#", "A", "A#", "B"
    )

    // Define the formulas for how different types of chords are built.
    // The numbers are intervals (semitones) from the root note.
    private enum class ChordType(val suffix: String, val intervals: List<Int>) {
        MAJOR("", listOf(0, 4, 7)),                 // Root, Major 3rd, Perfect 5th
        MINOR("m", listOf(0, 3, 7)),                // Root, Minor 3rd, Perfect 5th
        DIMINISHED("dim", listOf(0, 3, 6)),         // Root, Minor 3rd, Flat 5th
        AUGMENTED("aug", listOf(0, 4, 8)),          // Root, Major 3rd, Sharp 5th
        SUSPENDED_4("sus4", listOf(0, 5, 7)),       // Root, Perfect 4th, Perfect 5th
        DOMINANT_7("7", listOf(0, 4, 7, 10)),       // Root, Maj 3rd, Perf 5th, Min 7th
        MINOR_7("m7", listOf(0, 3, 7, 10)),         // Root, Min 3rd, Perf 5th, Min 7th
        MAJOR_7("maj7", listOf(0, 4, 7, 11))        // Root, Maj 3rd, Perf 5th, Maj 7th
    }

    // This map will hold our generated dictionary.
    // Key: A Set of integers representing the notes (e.g., [0, 4, 7])
    // Value: The chord name (e.g., "C Major")
    private val chordMap = mutableMapOf<Set<Int>, String>()

    init {
        generateAllChords()
    }

    private fun generateAllChords() {
        // 1. Loop through every root note (0 = C, 1 = C#, etc.)
        for (rootIndex in 0 until 12) {
            val rootNoteName = NOTE_NAMES[rootIndex]

            // 2. Apply every chord formula to that root note
            for (type in ChordType.values()) {

                // Calculate the actual note indices for this specific chord
                val chordNotes = type.intervals.map { interval ->
                    (rootIndex + interval) % 12
                }.toSet()

                // Generate the name (e.g., "A" + "m" = "Am")
                val chordName = rootNoteName + type.suffix

                // Save to dictionary
                chordMap[chordNotes] = chordName
            }
        }
    }

    /**
     * Looks up a chord based on a list of note indices detected by your FFT algorithm.
     * @param detectedNotes A list of note indices (e.g., [9, 0, 4] for A, C, E)
     * @return The chord name, or "Unknown" if it doesn't match standard formulas.
     */
    fun identifyChord(detectedNotes: List<Int>): String {
        // We convert the input to a Set before looking it up.
        // This is crucial because a Set ignores order.
        // [9, 0, 4] and [0, 4, 9] will both correctly identify as "Am".
        val noteSet = detectedNotes.toSet()

        return chordMap[noteSet] ?: "Unknown"
    }
}

/// Old one
class AudioTuner {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    // Standard audio settings for pitch detection
    private val sampleRate = 44100 // 44.1 kHz is standard
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO // Tuners only need 1 channel
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT // 16-bit depth is plenty accurate

    // Calculate the minimum buffer size the device allows
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission") // Ensure you check runtime permissions before calling this!
    fun startListening(updatePitch: (TuningResult?) -> Unit) {
        if (isRecording) return

        // 1. Initialize the AudioRecord object
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioTuner", "AudioRecord failed to initialize.")
            return
        }

        // 2. Start the microphone
        audioRecord?.startRecording()
        isRecording = true

        // 3. Start a background thread to read the data continuously
        recordingThread = Thread {
            readAudioData(updatePitch)
        }
        recordingThread?.start()
    }

    private fun readAudioData(updatePitch: (TuningResult?) -> Unit) {
        val audioBuffer = ShortArray(bufferSize)

        while (isRecording) {
            val readResult = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0

            if (readResult > 0) {
                val floatBuffer = FloatArray(readResult)
                var sumSquares = 0f

                for (i in 0 until readResult) {
                    val sample = audioBuffer[i] / 32768.0f
                    floatBuffer[i] = sample
                    sumSquares += sample * sample
                }

                // Calculate Volume using RMS (Root Mean Square)
                val rms = kotlin.math.sqrt(sumSquares / readResult)

                // NOISE GATE: 0.01 is a solid baseline for a quiet room.
                // If the app isn't picking up your guitar, lower this to 0.005f
                if (rms < 0.01f) {
                    continue
                }

                // Run the YIN Pitch Detection Algorithm
                val detectedFrequency = detectPitchYin(floatBuffer, sampleRate)

                // Ensure it's a valid guitar frequency (Standard Bass E is ~41Hz, High E is ~330Hz)
                if (detectedFrequency > 40f && detectedFrequency < 1000f) {
                    val tuningResult = PitchConverter.getNoteFromFrequency(detectedFrequency)
                    if (tuningResult != null) {
                        updatePitch(tuningResult)
                    }
                }
            }
        }
    }

    /**
     * Pure Kotlin YIN Algorithm
     * Extremely accurate for single-string instruments. Prevents octave errors.
     */
    private fun detectPitchYin(buffer: FloatArray, sampleRate: Int): Float {
        // We only use half the buffer so we don't go out of bounds when sliding
        val halfBufferSize = buffer.size / 2
        val yinBuffer = FloatArray(halfBufferSize)

        // Step 1: Difference Function
        // Instead of multiplying, we measure how different the wave is from itself
        for (tau in 0 until halfBufferSize) {
            for (i in 0 until halfBufferSize) {
                val delta = buffer[i] - buffer[i + tau]
                yinBuffer[tau] += delta * delta
            }
        }

        // Step 2: Cumulative Mean Normalized Difference Function
        // This math specifically prevents the algorithm from locking onto false harmonics
        yinBuffer[0] = 1f
        var runningSum = 0f
        for (tau in 1 until halfBufferSize) {
            runningSum += yinBuffer[tau]
            yinBuffer[tau] = yinBuffer[tau] * tau / runningSum
        }

        // Step 3: Absolute Threshold
        var bestTau = -1
        val threshold = 0.20f // Standard YIN threshold

        for (tau in 2 until halfBufferSize) {
            if (yinBuffer[tau] < threshold) {
                // We found a dip, now find the lowest exact point of this dip
                var currentTau = tau
                while (currentTau + 1 < halfBufferSize && yinBuffer[currentTau + 1] < yinBuffer[currentTau]) {
                    currentTau++
                }
                bestTau = currentTau
                break
            }
        }

        // Step 4: Fallback
        // If the sound wasn't clean enough to cross the 20% threshold, grab the absolute lowest point
        if (bestTau == -1) {
            var minVal = Float.MAX_VALUE
            for (tau in 2 until halfBufferSize) {
                if (yinBuffer[tau] < minVal) {
                    minVal = yinBuffer[tau]
                    bestTau = tau
                }
            }
        }

        // Convert the period (tau) back into a Hertz frequency
        return if (bestTau > 0) {
            sampleRate.toFloat() / bestTau
        } else {
            -1f
        }
    }

    fun stopListening() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordingThread = null
    }
}

// Data class to hold the result to send to your UI
data class TuningResult(
    val noteName: String,     // e.g., "A", "C#", "E"
    val octave: Int,          // e.g., 2, 3, 4
    val centsDeviation: Float // e.g., -15.5 (flat) or +5.2 (sharp)
)

object PitchConverter {

    // The 12 notes in Western music
    private val NOTE_NAMES = arrayOf(
        "C", "C#", "D", "D#", "E", "F",
        "F#", "G", "G#", "A", "A#", "B"
    )

    /**
     * Converts a raw frequency in Hertz to a musical note.
     * @param frequency The Hz value from your pitch detection algorithm (e.g. 110.5f)
     * @return TuningResult containing the note name and cents off.
     */
    fun getNoteFromFrequency(frequency: Float): TuningResult? {
        // Ignore background noise or frequencies that are too low/high for a guitar
        if (frequency < 20f || frequency > 4000f) {
            return null
        }

        // 1. Calculate the continuous MIDI pitch number (A4 = 440Hz = MIDI 69)
        val pitch = 69.0 + 12.0 * log2(frequency / 440.0)

        // 2. Find the closest perfect note (round to nearest integer)
        val targetPitch = pitch.roundToInt()

        // 3. Calculate how far off the pitch is in cents (100 cents = 1 half-step)
        val cents = ((pitch - targetPitch) * 100.0).toFloat()

        // 4. Map to a specific note name (Modulo 12)
        // Add 12000 before modulo to prevent negative numbers if pitch is extremely low
        val noteIndex = (targetPitch + 12000) % 12
        val noteName = NOTE_NAMES[noteIndex]

        // 5. Calculate the octave
        val octave = (targetPitch / 12) - 1

        return TuningResult(noteName, octave, cents)
    }
}