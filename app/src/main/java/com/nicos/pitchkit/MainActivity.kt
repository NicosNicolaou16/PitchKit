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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.nicos.pitchkit.ui.theme.PitchKitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PitchKitTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->

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
    fun startListening() {
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
            readAudioData()
        }
        recordingThread?.start()
    }

    private fun readAudioData() {
        // This array is the "bucket" we hand to the microphone to fill up
        val audioBuffer = ShortArray(bufferSize)

        while (isRecording) {
            // This line blocks (pauses the thread) until the buffer is full
            val readResult = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0

            if (readResult > 0) {
                // SUCCESS! You now have a full buffer of audio data.

                // Convert the ShortArray to a FloatArray (most pitch algorithms expect floats)
                val floatBuffer = FloatArray(readResult)
                for (i in 0 until readResult) {
                    // Normalize the 16-bit PCM values (-32768 to 32767) to be between -1.0 and 1.0
                    floatBuffer[i] = audioBuffer[i] / 32768.0f
                }

                // -> HERE IS WHERE YOU CALL YOUR YIN OR PITCH DETECTION ALGORITHM <-
                // val frequency = myPitchDetector.process(floatBuffer, sampleRate)
                // val noteResult = PitchConverter.getNoteFromFrequency(frequency)
                // updateUI(noteResult)
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