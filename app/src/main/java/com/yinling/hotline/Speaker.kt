package com.yinling.hotline

import android.speech.tts.TextToSpeech
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
        engine = TextToSpeech(app) { status -> onInit(status, explicitEngine = false) }
    }

    /**
     * Chinese ROMs often ship a speech engine without setting it as the default, so the plain
     * `TextToSpeech(context)` reports failure while a perfectly good engine sits installed (here:
     * `com.oplus.ttsaccessibilityengine`, with `tts_default_synth` unset). Ask what exists and bind
     * to it by name before giving up on speaking.
     */
    private fun onInit(status: Int, explicitEngine: Boolean) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            runCatching { engine?.language = Locale.SIMPLIFIED_CHINESE }
            pending?.let { text -> pending = null; speakNow(text) }
            return
        }
        val installed = runCatching { engine?.getEngines().orEmpty() }.getOrNull().orEmpty()
        LoopLog.event(
            "[speak] 引擎初始化失败 status=$status 已安装=${installed.joinToString { it.name }}",
        )
        val pick = installed.firstOrNull()
        if (explicitEngine || pick == null) {
            LoopLog.event("[speak] 没有可用的语音引擎，播报关闭")
            return
        }
        engine?.shutdown()
        engine = TextToSpeech(
            app,
            TextToSpeech.OnInitListener { next -> onInit(next, explicitEngine = true) },
            pick.name,
        )
    }

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
        val clean = text.replace(Regex("[\\p{So}\\p{Cn}]"), "").trim()
        if (clean.isBlank()) return
        engine?.speak(clean.take(240), TextToSpeech.QUEUE_FLUSH, null, "hotline")
    }

    /** For the settings screen: whether this phone can speak at all. */
    fun status(): String = when {
        ready -> "可用"
        engine == null -> "还没试过"
        else -> "这台手机没有可用的语音引擎（可在系统设置→无障碍→文字转语音里安装一个）"
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
