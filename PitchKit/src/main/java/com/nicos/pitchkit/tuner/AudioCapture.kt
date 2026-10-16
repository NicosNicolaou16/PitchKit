package com.nicos.pitchkit.tuner

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException

/** Thrown (and reported via onError) when the microphone can't be used. */
internal class AudioCaptureException(message: String) : RuntimeException(message)

internal class AudioCapture(
    val sampleRate: Int = 44100,
    val bufferSize: Int = 8192,
) {
    // The coroutine that owns the recorder. The recorder itself is no longer a field:
    // it lives only inside this coroutine, so no other thread can touch it.
    private var job: Job? = null

    @SuppressLint("MissingPermission") // caller must hold RECORD_AUDIO
    fun start(
        scope: CoroutineScope,
        onError: (Throwable) -> Unit,      // new: report failures instead of crashing the host app
        onBuffer: (FloatArray) -> Unit,
    ) {
        // Guard against double start: a second AudioRecord would leak and keep the mic locked
        if (job?.isActive == true) return

        job = scope.launch(Dispatchers.IO) {
            // Moved out of the field initializer: AudioRecord isn't touched until start(),
            // which also lets TunerEngine be constructed in plain JVM unit tests
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            // Negative values are error codes (ERROR / ERROR_BAD_VALUE), not sizes
            if (minBuf <= 0) {
                onError(AudioCaptureException("Unsupported audio config (minBuf=$minBuf)"))
                return@launch
            }

            // The constructor itself can throw (e.g. SecurityException if permission was revoked)
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, bufferSize * 2),   // 2× headroom so short stalls don't drop audio
                )
            } catch (e: Exception) {
                onError(e)
                return@launch
            }

            try {
                // AudioRecord doesn't throw when the mic is busy; it silently ends up UNINITIALIZED,
                // and startRecording() would then throw IllegalStateException
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw AudioCaptureException("AudioRecord failed to initialize (mic busy?)")
                }
                recorder.startRecording()
                // startRecording() can also fail silently (e.g. another app holds the mic)
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw AudioCaptureException("AudioRecord failed to start recording")
                }

                val shortBuf = ShortArray(bufferSize)   // raw 16-bit samples
                val floatBuf = FloatArray(bufferSize)   // normalized -1..1 for the DSP
                while (isActive) {
                    val read = recorder.read(shortBuf, 0, bufferSize)
                    when {
                        read > 0 -> {
                            for (i in 0 until read) floatBuf[i] = shortBuf[i] / 32768f
                            onBuffer(floatBuf.copyOf(read))   // copy: floatBuf is reused next loop
                        }
                        // Nothing yet: give other coroutines a chance instead of busy-looping
                        read == 0 -> yield()
                        // Negative = error (ERROR_DEAD_OBJECT after a headset change, etc.).
                        // Before, this case was ignored and the loop spun at 100% CPU
                        else -> throw AudioCaptureException("AudioRecord.read failed: $read")
                    }
                }
            } catch (e: CancellationException) {
                throw e                 // normal stop: let cancellation propagate, don't report it
            } catch (e: Exception) {
                onError(e)              // real failure → surfaced to the caller
            } finally {
                // Runs on the same thread that was reading, so release() can never
                // run while read() is still blocked (a known native crash)
                runCatching { recorder.stop() }   // stop() throws if recording never started
                recorder.release()                // mandatory, or the mic stays locked
            }
        }
    }

    fun stop() {
        // Only cancel; cleanup happens in the finally block above
        job?.cancel()
        job = null
    }
}