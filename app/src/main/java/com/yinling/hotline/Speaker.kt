package com.yinling.hotline

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
                }

                override fun onDone(utteranceId: String?) {
                    LoopLog.event("[speak] 朗读完成")
                }

                @Deprecated("older signature is still the one that fires on some ROMs")
                override fun onError(utteranceId: String?) {
                    LoopLog.event("[speak] 朗读失败")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    LoopLog.event("[speak] 朗读失败 code=$errorCode")
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
        engine?.speak(clean.take(240), TextToSpeech.QUEUE_FLUSH, null, "hotline")
    }

    /** For the settings screen: whether this phone can speak at all. */
    fun status(): String = when {
        ready -> "可用（引擎：$engineName）"
        engine == null -> "还没试过"
        else -> "这台手机的语音引擎没能启动，可在系统设置→无障碍→文字转语音里选一个"
    }

    fun stop() {
        engine?.stop()
    }

    fun release() {
        engine?.shutdown()
        engine = null
        ready = false
    }
}
