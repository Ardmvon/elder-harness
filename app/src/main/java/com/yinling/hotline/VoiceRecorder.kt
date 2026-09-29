package com.yinling.hotline

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * Records one utterance as raw PCM, which is exactly what the recogniser wants.
 *
 * `AudioRecord` rather than `MediaRecorder` on purpose: iFlytek takes raw 16 kHz mono PCM or mp3, and
 * an AAC/AMR file would have to be transcoded before anyone could read it. The source is
 * `VOICE_RECOGNITION` so the platform does not apply the aggressive noise processing meant for calls.
 */
class VoiceRecorder {

    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_SECONDS = 30

        /** Above this the room counts as "someone is talking"; tuned by hand on a quiet desk. */
        private const val SPEECH_RMS = 1400.0

        /** Pause that ends the utterance. */
        private const val SILENCE_MILLIS = 900

        /** Speech shorter than this is a cough or a door, not a sentence. */
        private const val MIN_SPEECH_MILLIS = 350

        private fun rmsOf(chunk: ByteArray, length: Int): Double {
            var sum = 0.0
            var index = 0
            while (index + 1 < length) {
                val sample = ((chunk[index + 1].toInt() shl 8) or (chunk[index].toInt() and 0xFF)).toShort()
                sum += sample.toDouble() * sample
                index += 2
            }
            val count = (length / 2).coerceAtLeast(1)
            return kotlin.math.sqrt(sum / count)
        }
    }

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val collected = ByteArrayOutputStream()

    @Volatile
    var isRecording: Boolean = false
        private set

    /** Loudest chunk of the last recording; kept for tuning the speech gate from the log. */
    @Volatile
    private var lastPeakRms = 0.0

    /**
     * @param source `VOICE_RECOGNITION` for a person talking to the phone (it is tuned for speech and
     *   cancels the phone's own output), `MIC` when the sound comes from the phone itself — which is
     *   what the self-test needs, since echo cancellation would otherwise delete the test sentence.
     * @param onAutoStop called when the person has clearly stopped talking, so the caller can finish
     *   the utterance without asking for a second tap. This is the first step away from a
     *   walkie-talkie: one tap, speak, and it ends by itself.
     * @return false when the phone refuses to hand over the microphone.
     */
    fun start(
        source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
        onAutoStop: (() -> Unit)? = null,
        /** Called once, the moment a voice appears: this is what makes interruption possible. */
        onSpeechStart: (() -> Unit)? = null,
    ): Boolean {
        if (isRecording) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val bufferSize = minBuffer * 2
        val recorder = try {
            AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (_: SecurityException) {
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        collected.reset()
        lastPeakRms = 0.0
        record = recorder
        isRecording = true
        recorder.startRecording()
        val limit = SAMPLE_RATE * 2 * MAX_SECONDS
        worker = Thread {
            val chunk = ByteArray(3200)
            var heardVoice = false
            var silentMillis = 0
            var spokeMillis = 0
            while (isRecording) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read > 0) {
                    synchronized(collected) {
                        if (collected.size() < limit) collected.write(chunk, 0, read)
                    }
                    // Crude energy gate: enough to end an utterance when the person stops, without
                    // pretending to be a real VAD. Speech has to be seen first, so a quiet room does
                    // not end the recording before anything was said.
                    val rms = rmsOf(chunk, read)
                    if (rms > lastPeakRms) lastPeakRms = rms
                    val millis = read / 2 * 1000 / SAMPLE_RATE
                    if (rms > SPEECH_RMS) {
                        if (!heardVoice) onSpeechStart?.invoke()
                        heardVoice = true
                        spokeMillis += millis
                        silentMillis = 0
                    } else if (heardVoice) {
                        silentMillis += millis
                        if (silentMillis >= SILENCE_MILLIS && spokeMillis >= MIN_SPEECH_MILLIS) {
                            onAutoStop?.invoke()
                            break
                        }
                    }
                } else if (read < 0) {
                    break
                }
            }
        }.also { it.start() }
        LoopLog.event("[voice] 开始录音")
        return true
    }

    /** Stops and returns what was captured; empty when nothing was heard. */
    fun stop(): ByteArray {
        if (!isRecording && record == null) return ByteArray(0)
        isRecording = false
        worker?.join(600)
        worker = null
        record?.let { recorder ->
            runCatching { recorder.stop() }
            recorder.release()
        }
        record = null
        val bytes = synchronized(collected) { collected.toByteArray() }
        LoopLog.event(
            "[voice] 录音结束 ${bytes.size / 2 / (SAMPLE_RATE / 1000)}ms 峰值音量=${lastPeakRms.toInt()}",
        )
        return bytes
    }

    fun cancel() {
        stop()
        synchronized(collected) { collected.reset() }
    }
}
