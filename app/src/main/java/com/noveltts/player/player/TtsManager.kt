package com.noveltts.player.player

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 基于 Android 标准 TextToSpeech API 的 TTS 管理器。
 * 不写死引擎，兼容系统/厂商/联网 TTS。
 */
class TtsManager(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onTtsReady()
        fun onTtsFailed(reason: String)
        fun onUtteranceDone(utteranceId: String)
        fun onUtteranceError(utteranceId: String, errorCode: Int)
    }

    private var tts: TextToSpeech? = null
    var engineName: String? = null
        private set

    var ready: Boolean = false
        private set

    private var preferredVoice: String? = null

    /**
     * 初始化并绑定 TTS 引擎。
     * [engine] 为 null 时使用系统默认引擎；[voiceName] 为已选定的语音名（Voice.name），
     * 非空时初始化成功后通过 setVoice 固定使用该语音，避免依赖系统默认语音（每次启动会重置）。
     */
    fun init(engine: String?, voiceName: String?) {
        engineName = engine
        preferredVoice = voiceName
        ready = false
        shutdown()
        val newTts = TextToSpeech(context.applicationContext, { status ->
            if (status != TextToSpeech.SUCCESS) {
                listener.onTtsFailed("TTS 初始化失败 (status=$status)")
                return@TextToSpeech
            }
            val t = this@TtsManager.tts
            if (t == null) return@TextToSpeech
            val langResult = t.setLanguage(Locale.CHINA)
            when (langResult) {
                TextToSpeech.LANG_MISSING_DATA,
                TextToSpeech.LANG_NOT_SUPPORTED -> {
                    // 部分引擎对 Locale.CHINA 返回不支持，回退尝试
                    val r2 = t.setLanguage(Locale("zh"))
                    if (r2 == TextToSpeech.LANG_MISSING_DATA || r2 == TextToSpeech.LANG_NOT_SUPPORTED) {
                        listener.onTtsFailed("当前 TTS 引擎没有可用的中文语音，请在系统设置中安装或更换 TTS 引擎")
                        return@TextToSpeech
                    }
                }
            }
            applyVoiceInternal(t, preferredVoice)
            try {
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != null) listener.onUtteranceDone(utteranceId)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId != null) listener.onUtteranceError(utteranceId, TextToSpeech.ERROR)
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId != null) listener.onUtteranceError(utteranceId, errorCode)
                    }
                })
            } catch (_: Throwable) {
            }
            ready = true
            android.util.Log.d("NovelTTS", "tts ready voice=${t.voice?.name}")
            listener.onTtsReady()
        }, engine)
        this.tts = newTts
    }

    fun setEngine(engine: String?) {
        if (engineName == engine) return
        shutdown()
        init(engine, preferredVoice)
    }

    /** 运行时切换语音；[name] 为 null/空 表示恢复为引擎默认语音。返回是否生效。 */
    fun applyVoice(name: String?): Boolean {
        val t = tts ?: return false
        if (!ready) return false
        val ok = applyVoiceInternal(t, name)
        preferredVoice = name
        return ok
    }

    private fun applyVoiceInternal(t: TextToSpeech, name: String?): Boolean {
        if (name.isNullOrBlank()) {
            return runCatching { t.setLanguage(Locale.CHINA) }.getOrDefault(TextToSpeech.ERROR) != TextToSpeech.ERROR
        }
        val target = runCatching { t.voices?.firstOrNull { it.name == name } }.getOrNull() ?: return false
        val res = runCatching { t.setVoice(target) }.getOrDefault(TextToSpeech.ERROR)
        return res == TextToSpeech.SUCCESS
    }

    fun speak(text: String, utteranceId: String) {
        val t = tts ?: return
        if (!ready) return
        val params = Bundle()
        val res = runCatching {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }.getOrDefault(TextToSpeech.ERROR)
        if (res == TextToSpeech.ERROR) {
            listener.onUtteranceError(utteranceId, TextToSpeech.ERROR)
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun setSpeed(v: Float) {
        runCatching { tts?.setSpeechRate(v.coerceIn(0.1f, 5.0f)) }
    }

    fun setPitch(v: Float) {
        runCatching { tts?.setPitch(v.coerceIn(0.1f, 3.0f)) }
    }

    fun shutdown() {
        ready = false
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }
}