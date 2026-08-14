package com.nicos.pitchkit.tuner

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

internal class AudioCapture(
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
        if (recorder == null) return   // already stopped — no-op
        running = false        // signals the loop to exit
        recorder?.stop()
        recorder?.release()    // release hardware — mandatory, or the mic stays locked
        recorder = null
    }
}