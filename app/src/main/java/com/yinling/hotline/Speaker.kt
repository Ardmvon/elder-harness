package com.yinling.hotline

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Says out loud what the assistant wants to convey.
 *
 * This is the difference between an app that assumes the person can read a small screen and one that
 * works for someone who cannot. It matters most exactly where the app is most useful: the conclusion
 * of a task, a question it needs answered, and the notice that it can no longer see the screen.
 *
 * The system engine is used on purpose — offline, free, already installed, and it follows the volume
 * the person has set. Nothing is sent anywhere.
 */
class Speaker(private val app: HotlineApp) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    /** Engines already attempted, so a failure moves on instead of looping. */
    private val triedEngines = mutableSetOf<String>()

    /** Which engine is speaking, for the settings screen. */
    private var engineName: String = ""
    private val prefs = app.getSharedPreferences("hotline", 0)

    /** True from utterance start until done/error/stop; used to pause the microphone idle timer. */
    @Volatile
    var isSpeaking: Boolean = false
        private set

    private val mutableSpeaking = MutableStateFlow(false)

    /** UI-facing version of [isSpeaking], so Compose and the overlay can redraw. */
    val speaking: StateFlow<Boolean> = mutableSpeaking.asStateFlow()

    /** Called when speech starts or stops. The listener may arrive on a TTS binder thread. */
    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    /** The utterance currently allowed to change [isSpeaking]; older flushed ones are ignored. */
    @Volatile
    private var currentUtterance: String? = null
    private var utteranceSerial = 0

    /** Fallback so a ROM that forgets onDone cannot leave the UI stuck on "speaking" forever. */
    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var speakingTimeout: Runnable? = null

    /** On by default: the people this is for are the least likely to go looking for the switch. */
    var enabled: Boolean
        get() = prefs.getBoolean("speak_replies", true)
        set(value) {
            prefs.edit().putBoolean("speak_replies", value).apply()
            if (!value) stop()
        }

    fun prepare() {
        if (engine != null) return
        engine = TextToSpeech(app) { status -> onInit(status, enginePackage = null) }
    }

    /**
     * Chinese ROMs ship an engine without marking it the default: `tts_default_synth` is unset, so
     * `TextToSpeech(context)` fails while a perfectly good engine sits installed (here
     * `com.oplus.ttsaccessibilityengine`). Asking that failed instance for its engine list cannot
     * work either — the list comes from the service it never connected to. So ask the platform which
     * packages declare a TTS service, and bind to them one by one by name.
     */
    private fun onInit(status: Int, enginePackage: String?) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            engineName = enginePackage ?: "系统默认"
            runCatching { engine?.language = Locale.SIMPLIFIED_CHINESE }
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    LoopLog.event("[speak] 开始朗读")
                    if (utteranceId == currentUtterance) setSpeaking(true)
                }

                override fun onDone(utteranceId: String?) {
                    LoopLog.event("[speak] 朗读完成")
                    finishSpeaking(utteranceId)
                }

                @Deprecated("older signature is still the one that fires on some ROMs")
                override fun onError(utteranceId: String?) {
                    LoopLog.event("[speak] 朗读失败")
                    finishSpeaking(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    LoopLog.event("[speak] 朗读失败 code=$errorCode")
                    finishSpeaking(utteranceId)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    finishSpeaking(utteranceId)
                }
            })
            LoopLog.event("[speak] 使用引擎：$engineName")
            pending?.let { text -> pending = null; speakNow(text) }
            return
        }

        val next = installedEngines().firstOrNull { it !in triedEngines }
        if (next == null) {
            LoopLog.event("[speak] 没有可用的语音引擎（试过 ${triedEngines.joinToString()}）")
            return
        }
        triedEngines += next
        engine?.shutdown()
        engine = TextToSpeech(app, TextToSpeech.OnInitListener { s -> onInit(s, next) }, next)
    }

    /** Packages that declare a text-to-speech service, as the platform sees them. */
    private fun installedEngines(): List<String> = runCatching {
        app.packageManager
            .queryIntentServices(android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    }.getOrDefault(emptyList())

    /** Speaks [text], replacing whatever was being said: the newest news is the relevant one. */
    fun say(text: String) {
        if (text.isBlank()) return
        if (!enabled) return
        prepare()
        if (!ready) {
            // The engine takes a moment to come up; keep the latest line and speak it when it does.
            pending = text
            return
        }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        // Quotes and brackets are for reading; spoken aloud they are just noise.
        val clean = text
            .replace(Regex("[\\p{So}\\p{Cn}]"), "")
            .replace(Regex("[「」“”\"'（）()【】\\[\\]]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isBlank()) return
        val utterance = "hotline_${++utteranceSerial}"
        currentUtterance = utterance
        // Do not wait for onStart: some engines are late, and the UI should show "speaking" as
        // soon as we hand the line to TTS. The timeout is the safety net if no callback arrives.
        setSpeaking(true)
        speakingTimeout?.let { handler.removeCallbacks(it) }
        val timeout = Runnable {
            if (currentUtterance == utterance) {
                LoopLog.event("[speak] 朗读回调超时，强制结束状态")
                setSpeaking(false)
                speakingTimeout = null
            }
        }
        speakingTimeout = timeout
        handler.postDelayed(timeout, MAX_SPEAK_MS)
        engine?.speak(clean.take(240), TextToSpeech.QUEUE_FLUSH, null, utterance)
    }

    private fun finishSpeaking(utteranceId: String?) {
        if (utteranceId != currentUtterance) return
        speakingTimeout?.let { handler.removeCallbacks(it) }
        speakingTimeout = null
        setSpeaking(false)
    }

    private fun setSpeaking(speaking: Boolean) {
        if (isSpeaking == speaking) return
        isSpeaking = speaking
        mutableSpeaking.value = speaking
        onSpeakingChanged?.invoke(speaking)
    }

    /** For the settings screen: whether this phone can speak at all. */
    fun status(): String = when {
        ready -> "可用（引擎：$engineName）"
        engine == null -> "还没试过"
        else -> "这台手机的语音引擎没能启动，可在系统设置→无障碍→文字转语音里选一个"
    }

    private companion object {
        /** Longest reasonable line is 240 characters; 90s is a generous callback fallback. */
        const val MAX_SPEAK_MS = 90_000L
    }

    fun stop() {
        currentUtterance = null
        speakingTimeout?.let { handler.removeCallbacks(it) }
        speakingTimeout = null
        engine?.stop()
        setSpeaking(false)
    }

    fun release() {
        currentUtterance = null
        speakingTimeout?.let { handler.removeCallbacks(it) }
        speakingTimeout = null
        engine?.shutdown()
        engine = null
        ready = false
        setSpeaking(false)
    }
}
