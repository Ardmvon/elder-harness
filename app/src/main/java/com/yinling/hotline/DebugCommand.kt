package com.yinling.hotline

import android.content.Context
import kotlinx.coroutines.launch

/**
 * Debug-only entry point, so a task can be driven from a computer without typing the key on the
 * phone. Only active in debuggable builds and only when the intent asks for it explicitly.
 *
 * ```
 * adb shell am start -n com.yinling.hotline/.MainActivity --ez developer_mode true \
 *   --es apikey sk-xxx --es endpoint https://api.deepseek.com --es model deepseek-chat \
 *   --ez dev_mode true --ez start true --es goal "打开美团点一份外卖"
 * ```
 */
object DebugCommand {
    /** Keys declared as booleans; every other key is read as a string. */
    private val BOOLEAN_EXTRAS = setOf(
        EXTRA_ENABLE, EXTRA_VISION, EXTRA_AUTO_CONFIRM, EXTRA_START, EXTRA_CLEAR_LOG, EXTRA_RESTORE,
        EXTRA_CENSUS, EXTRA_PAIR, EXTRA_CHECK_IN, EXTRA_VOICE_TEST,
    )

    /**
     * Typed access to the intent extras.
     *
     * Two traps this exists to avoid:
     * - reading a String key with `getBooleanExtra` returns a non-null `false`, so a `?:` fallback
     *   to `getStringExtra` never runs;
     * - reading an absent Boolean key also returns `false`, which is indistinguishable from an
     *   explicit `false`. Deciding "did the caller pass this?" therefore requires [has], not a
     *   null check on the value.
     */
    class Extras(private val intent: android.content.Intent?) {
        fun has(key: String): Boolean = intent?.hasExtra(key) == true

        fun value(key: String): Any? = when {
            intent == null -> null
            key in BOOLEAN_EXTRAS -> intent.getBooleanExtra(key, false)
            else -> intent.getStringExtra(key)
        }
    }

    fun extras(intent: android.content.Intent?): Extras = Extras(intent)

    const val EXTRA_ENABLE = "developer_mode"
    const val EXTRA_API_KEY = "apikey"
    const val EXTRA_ENDPOINT = "endpoint"
    const val EXTRA_MODEL = "model"
    const val EXTRA_VISION = "vision"
    const val EXTRA_AUTO_CONFIRM = "dev_mode"
    const val EXTRA_GOAL = "goal"
    const val EXTRA_START = "start"
    const val EXTRA_CLEAR_LOG = "clear_log"

    /** Answers a pending question, as if the person typed it. */
    const val EXTRA_ANSWER = "answer"

    /** Observes the current screen once and logs what the tree offered versus what we show. */
    const val EXTRA_CENSUS = "census"

    /** Restores the most recent saved task instead of starting a new one. */
    const val EXTRA_RESTORE = "restore"

    /** Trusted-circle server address, for wiring up a local server during development. */
    const val EXTRA_SERVER = "server"

    /** Elder's name as the circle will see it. */
    const val EXTRA_ELDER = "elder"

    /** Pairs with the server on launch, so a device can be set up without typing a URL. */
    const val EXTRA_PAIR = "pair"

    /** Sends one heartbeat on launch and logs whatever came back. */
    const val EXTRA_CHECK_IN = "check_in"

    /**
     * End-to-end voice self-test: speaks a sentence out loud, records it, sends it to the server's
     * recogniser and logs both the audio size and the text. Lets the whole chain be verified without
     * a person holding the phone.
     */
    const val EXTRA_VOICE_TEST = "selftest_voice"

    /** @return true when the extras asked for a task to be started. */
    fun apply(context: Context, app: HotlineApp, extras: Extras): Boolean {
        if (!BuildConfig.DEBUG) return false
        if (extras.value(EXTRA_ENABLE) != true) return false

        val session = app.session
        val prefs = context.getSharedPreferences("hotline", 0)
        val edit = prefs.edit()

        (extras.value(EXTRA_ENDPOINT) as? String)?.takeIf { it.isNotBlank() }?.let { edit.putString("endpoint", it.trim()) }
        (extras.value(EXTRA_SERVER) as? String)?.takeIf { it.isNotBlank() }?.let { edit.putString("server_url", it.trim().trimEnd('/')) }
        (extras.value(EXTRA_ELDER) as? String)?.takeIf { it.isNotBlank() }?.let { edit.putString("elder_name", it.trim()) }
        (extras.value(EXTRA_MODEL) as? String)?.takeIf { it.isNotBlank() }?.let { edit.putString("model", it.trim()) }
        // Only touch a switch when the caller actually passed it, otherwise a debug launch would
        // silently reset the person's own settings.
        if (extras.has(EXTRA_VISION)) edit.putBoolean("vision", extras.value(EXTRA_VISION) == true)
        if (extras.has(EXTRA_AUTO_CONFIRM)) edit.putBoolean("auto_confirm", extras.value(EXTRA_AUTO_CONFIRM) == true)
        // The extra is also the developer switch; persist it so the full trace really is written.
        edit.putBoolean("developer_mode", true)

        // The key stays in memory by design, so it is never written to preferences.
        (extras.value(EXTRA_API_KEY) as? String)?.takeIf { it.isNotBlank() }?.let { session.apiKey = it.trim() }
        edit.apply()

        // Re-read so the session object reflects exactly what was just written.
        session.developerMode = prefs.getBoolean("developer_mode", false)
        session.autoConfirm = prefs.getBoolean("auto_confirm", false)
        session.visionEnabled = prefs.getBoolean("vision", false)
        LoopLog.enabled = session.developerMode
        if (extras.value(EXTRA_CLEAR_LOG) == true) LoopLog.clear()

        if (extras.value(EXTRA_VOICE_TEST) == true) {
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
            ).launch {
                // Everything in one runCatching: an exception in a bare `launch` reaches the default
                // handler and kills the process, which is how this test looked like it simply stopped.
                runCatching {
                    val server = ServerClient(app)
                    val ready = runCatching { server.refreshSpeechStatus() }.getOrDefault(false)
                    LoopLog.event("[voice] 自测开始（服务端识别配置=$ready）")
                    // Louder, so the microphone can hear the phone's own voice at all.
                    runCatching {
                        val audio = app.getSystemService(android.content.Context.AUDIO_SERVICE)
                            as android.media.AudioManager
                        audio.setStreamVolume(
                            android.media.AudioManager.STREAM_MUSIC,
                            audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),
                            0,
                        )
                    }
                    app.speaker.say("打开微信给女儿发一条消息说我到家了")
                    kotlinx.coroutines.delay(1500)
                    val started = app.recorder.start(android.media.MediaRecorder.AudioSource.MIC)
                    LoopLog.event("[voice] 录音启动=$started")
                    kotlinx.coroutines.delay(6000)
                    val pcm = app.recorder.stop()
                    // Keep the raw audio of the self-test only: it is the one way to tell "the
                    // microphone heard nothing" apart from "the recogniser read nothing".
                    runCatching {
                        java.io.File(app.filesDir, "selftest.pcm").writeBytes(pcm)
                    }
                    LoopLog.event("[voice] 录到 ${pcm.size} 字节，开始上传")
                    val heard = server.transcribe(pcm)
                    LoopLog.event("[voice] 自测结束 识别=$heard")
                }.onFailure { error ->
                    LoopLog.event("[voice] 自测异常：${error.javaClass.simpleName} ${error.message}")
                }
            }
        }

        // Runs after the log is configured, otherwise the pairing result is written into a log that
        // is not being kept yet — which is exactly how the first attempt looked like it did nothing.
        if (extras.value(EXTRA_PAIR) == true || extras.value(EXTRA_CHECK_IN) == true) {
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
            ).launch {
                val server = ServerClient(app)
                if (extras.value(EXTRA_PAIR) == true) {
                    runCatching { server.pair() }
                        .onFailure { LoopLog.event("[server] 配对失败：${it.message}") }
                }
                if (extras.value(EXTRA_CHECK_IN) == true) {
                    val pending = runCatching { server.heartbeat("debug") }.getOrDefault(emptyList())
                    LoopLog.event("[server] 调试心跳返回 ${pending.size} 条：$pending")
                }
            }
        }

        // Never log the key itself, not even a prefix: this file stays on the device.
        LoopLog.event(
            "debug command: dev=${session.developerMode} vision=${session.visionEnabled} " +
                "endpoint=${session.endpoint} model=${session.model} " +
                "key=${if (session.apiKey.isBlank()) "未设置" else "已设置(${session.apiKey.length}位)"}",
        )

        val goal = (extras.value(EXTRA_GOAL) as? String)?.trim().orEmpty()
        val start = extras.value(EXTRA_START) != false
        if (goal.isNotBlank() && start) session.start(goal)

        if (extras.value(EXTRA_CENSUS) == true) {
            LoopLog.event("[debug] census on current screen")
            session.censusOnce()
            return true
        }

        // Answering an outstanding question continues the task without restarting it.
        val answer = (extras.value(EXTRA_ANSWER) as? String)?.trim().orEmpty()
        if (answer.isNotBlank() && goal.isBlank()) {
            LoopLog.event("[debug] answering: ${answer.take(30)}")
            session.answerQuestion(answer)
            return true
        }

        val restore = extras.value(EXTRA_RESTORE) == true && goal.isBlank()
        if (restore) {
            session.history().firstOrNull { it.unfinished }?.let { saved ->
                LoopLog.event("[debug] restoring newest unfinished session ${saved.id}")
                session.restore(saved.id)
                return true
            }
            LoopLog.event("[debug] no unfinished session to restore")
        }
        return goal.isNotBlank() && start
    }
}
