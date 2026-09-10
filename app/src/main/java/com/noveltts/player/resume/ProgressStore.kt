package com.noveltts.player.resume

import android.content.Context
import com.noveltts.player.data.PlaybackProgress
import org.json.JSONObject

/**
 * 断点续播与播放参数持久化。
 * 播放中周期性自动保存；暂停/停止/退出时同步 commit 保存，保证可靠性。
 */
object ProgressStore {

    private const val PREFS = "progress"
    private const val KEY_PROGRESS_PREFIX = "progress_"
    private const val KEY_READER_PREFIX = "reader_"
    private const val KEY_SPEED = "speed"
    private const val KEY_PITCH = "pitch"
    private const val KEY_ENGINE = "tts_engine"
    private const val KEY_VOICE = "tts_voice"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- TTS 播放位置（保留原字段，兼容旧版断点） ----

    fun saveProgress(context: Context, p: PlaybackProgress) {
        val json = JSONObject().apply {
            put("novelId", p.novelId)
            put("lineByteOffset", p.lineByteOffset)
            put("subIndex", p.subIndex)
            put("chapterIndex", p.chapterIndex)
            put("chapterTitle", p.chapterTitle)
            put("percent", p.percent)
            put("updatedAt", p.updatedAt)
        }
        prefs(context).edit().putString(KEY_PROGRESS_PREFIX + p.novelId, json.toString()).commit()
    }

    fun loadProgress(context: Context, novelId: String): PlaybackProgress? =
        load(KEY_PROGRESS_PREFIX + novelId, context, novelId)

    fun clearProgress(context: Context, novelId: String) {
        prefs(context).edit().remove(KEY_PROGRESS_PREFIX + novelId).apply()
    }

    // ---- 阅读位置（独立于 TTS 播放位置） ----

    /** 保存阅读位置（用户手动阅读/滚动后所在位置）。与 TTS 位置分开存储，互不覆盖。 */
    fun saveReaderProgress(context: Context, p: PlaybackProgress) {
        val json = JSONObject().apply {
            put("novelId", p.novelId)
            put("lineByteOffset", p.lineByteOffset)
            put("subIndex", p.subIndex)
            put("chapterIndex", p.chapterIndex)
            put("chapterTitle", p.chapterTitle)
            put("percent", p.percent)
            put("updatedAt", p.updatedAt)
        }
        prefs(context).edit().putString(KEY_READER_PREFIX + p.novelId, json.toString()).commit()
    }

    fun loadReaderProgress(context: Context, novelId: String): PlaybackProgress? =
        load(KEY_READER_PREFIX + novelId, context, novelId)

    fun clearReaderProgress(context: Context, novelId: String) {
        prefs(context).edit().remove(KEY_READER_PREFIX + novelId).apply()
    }

    /**
     * 统一的「继续阅读」位置：取 TTS 位置与阅读位置中较新者。
     * 两者都没有 → null；只有其一 → 返回其一（兼容只有旧版 TTS 断点的小说）；
     * 时间相同 → 稳定选择 TTS 位置。
     */
    fun resolveResume(context: Context, novelId: String): PlaybackProgress? {
        val tts = loadProgress(context, novelId)
        val reader = loadReaderProgress(context, novelId)
        return when {
            tts == null -> reader
            reader == null -> tts
            reader.updatedAt > tts.updatedAt -> reader
            else -> tts
        }
    }

    private fun load(key: String, context: Context, novelId: String): PlaybackProgress? {
        val raw = prefs(context).getString(key, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            PlaybackProgress(
                novelId = o.optString("novelId", novelId),
                lineByteOffset = o.optLong("lineByteOffset"),
                subIndex = o.optInt("subIndex"),
                chapterIndex = o.optInt("chapterIndex"),
                chapterTitle = o.optString("chapterTitle"),
                percent = o.optDouble("percent"),
                updatedAt = o.optLong("updatedAt")
            )
        }.getOrNull()
    }

    // ---- 播放参数 ----

    fun getSpeed(context: Context): Float =
        prefs(context).getFloat(KEY_SPEED, 1.0f)

    fun setSpeed(context: Context, v: Float) {
        prefs(context).edit().putFloat(KEY_SPEED, v).commit()
    }

    fun getPitch(context: Context): Float =
        prefs(context).getFloat(KEY_PITCH, 1.0f)

    fun setPitch(context: Context, v: Float) {
        prefs(context).edit().putFloat(KEY_PITCH, v).commit()
    }

    fun getEngine(context: Context): String? =
        prefs(context).getString(KEY_ENGINE, null)

    fun setEngine(context: Context, engine: String?) {
        prefs(context).edit().putString(KEY_ENGINE, engine).commit()
    }

    fun getVoice(context: Context): String? =
        prefs(context).getString(KEY_VOICE, null)

    fun setVoice(context: Context, voice: String?) {
        prefs(context).edit().putString(KEY_VOICE, voice).commit()
    }
}