package com.nicos.pitchkit

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

class MainActivity : ComponentActivity() {
    //private val audioTuner = AudioTuner()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PitchKitTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    //val tuningResult = PitchConverter.getNoteFromFrequency(136.00f/*floatBuffer.lastOrNull() ?: 130.81f*/)
                    PureKotlinChordDetector().startListening {
                        runOnUiThread {
                            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                        }
                    }
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
}



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
        // This array is the "bucket" we hand to the microphone to fill up
        val audioBuffer = ShortArray(bufferSize)

        while (isRecording) {
            // This line blocks (pauses the thread) until the buffer is full
            val readResult = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0

            if (readResult > 0) {
                // SUCCESS! You now have a full buffer of audio data.

                var tuningResult: TuningResult? = null
                // Convert the ShortArray to a FloatArray (most pitch algorithms expect floats)
                val floatBuffer = FloatArray(readResult)
                for (i in 0 until readResult) {
                    // Normalize the 16-bit PCM values (-32768 to 32767) to be between -1.0 and 1.0
                    floatBuffer[i] = audioBuffer[i] / 32768.0f
                    //Log.d("AudioTuner", floatBuffer[i].toString())
                }
                // -> HERE IS WHERE YOU CALL YOUR YIN OR PITCH DETECTION ALGORITHM <-
                // For now, we'll just use the last sample to simulate a result (not accurate)
                tuningResult = PitchConverter.getNoteFromFrequency(440f/*floatBuffer.lastOrNull() ?: 130.81f*/)
                Log.d("AudioTuner", tuningResult?.noteName ?: "no note found")
                updatePitch(tuningResult)
            }
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