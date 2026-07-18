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
import kotlin.math.log2
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val audioTuner = AudioTuner()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PitchKitTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    audioTuner.startListening({ result ->
                        runOnUiThread {
                            Toast.makeText(this, result?.noteName ?: "no note found", Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }
        }
    }
}

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
                tuningResult = PitchConverter.getNoteFromFrequency(floatBuffer.lastOrNull() ?: 0f)
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