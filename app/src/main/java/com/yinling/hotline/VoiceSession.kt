package com.yinling.hotline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A conversation, rather than a walkie-talkie.
 *
 * The old flow was: tap, speak, tap again, wait. That is a radio. Here the microphone is opened once
 * and stays open for the length of a conversation, so the person can simply talk — including while
 * the assistant is speaking, which is what interruption means.
 *
 * Two things make that possible on this hardware:
 *
 * * the microphone source cancels the phone's own speaker, so the assistant does not hear itself;
 * * an energy gate on the incoming audio decides when a sentence starts and ends, so nobody has to
 *   press anything to mark the end of one.
 *
 * It closes itself after [IDLE_TIMEOUT_MS] without anyone speaking: an open microphone is a promise
 * about privacy and a tax on the battery, and neither should be indefinite.
 */
class VoiceSession(
    private val app: HotlineApp,
    private val transcribe: suspend (ByteArray) -> String?,
) {

    enum class State {
        /** Microphone closed. */
        OFF,

        /** Open and waiting for someone to speak. */
        LISTENING,

        /** Someone is speaking right now. */
        RECORDING,

        /** Turning the last sentence into text. */
        TRANSCRIBING,
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Where a recognised instruction goes. Set by [SessionController]. */
    var onInstruction: (suspend (String) -> Unit)? = null

    /** Raised when the session closes, so the screens can stop showing "listening". */
    var onStateChanged: (() -> Unit)? = null

    private var idleJob: Job? = null

    val isActive: Boolean get() = mutableState.value != State.OFF

    fun toggle() {
        if (isActive) stop() else start()
    }

    fun start() {
        if (isActive) return
        openMicrophone()
    }

    /** Keeps the conversation window open while the assistant is talking, so a reply is expected. */
    fun expectReply() {
        if (isActive) armIdleTimer()
    }

    fun stop() {
        idleJob?.cancel()
        idleJob = null
        if (app.recorder.isRecording) app.recorder.stop()
        set(State.OFF)
    }

    private fun openMicrophone() {
        val opened = app.recorder.start(
            // Interruption: the person speaking is the reason for the assistant to stop talking.
            onSpeechStart = {
                app.speaker.stop()
                set(State.RECORDING)
            },
            onAutoStop = { finishUtterance() },
        )
        if (!opened) {
            LoopLog.event("[voice] 麦克风打不开")
            set(State.OFF)
            return
        }
        set(State.LISTENING)
        armIdleTimer()
    }

    /** Called from the recorder's worker thread when the person stops talking. */
    private fun finishUtterance() {
        val pcm = app.recorder.stop()
        if (!isActive) return
        if (pcm.size < 8000) {
            reopen()
            return
        }
        set(State.TRANSCRIBING)
        scope.launch {
            val heard = transcribe(pcm)
            if (!heard.isNullOrBlank()) {
                onInstruction?.invoke(heard)
            }
            if (isActive) reopen()
        }
    }

    /** Back to listening, which is what makes this a conversation instead of a one-shot command. */
    private fun reopen() {
        if (!isActive) return
        openMicrophone()
    }

    private fun armIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            LoopLog.event("[voice] 会话超时，关麦")
            stop()
        }
    }

    private fun set(next: State) {
        if (mutableState.value == next) return
        mutableState.value = next
        onStateChanged?.invoke()
    }

    private companion object {
        /** How long the microphone stays open with nobody talking. */
        const val IDLE_TIMEOUT_MS = 20_000L
    }
}
