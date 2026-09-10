package com.noveltts.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小说列表持久化。删除「小说记录」不会删除用户手机上的原始文件。
 */
object NovelRepository {

    private const val PREFS = "novel_library"
    private const val KEY_LIBRARY = "library"
    private const val KEY_LAST = "last_novel_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getLibrary(context: Context): List<NovelRecord> {
        val raw = prefs(context).getString(KEY_LIBRARY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { NovelRecord.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun addNovel(context: Context, record: NovelRecord) {
        val list = getLibrary(context).toMutableList()
        list.removeAll { it.uri == record.uri }
        list.add(0, record)
        save(context, list)
    }

    fun removeNovel(context: Context, id: String) {
        val list = getLibrary(context).filterNot { it.id == id }
        save(context, list)
        if (getLastId(context) == id) setLast(context, null)
    }

    fun getNovel(context: Context, id: String): NovelRecord? =
        getLibrary(context).firstOrNull { it.id == id }

    fun findByUri(context: Context, uri: String): NovelRecord? =
        getLibrary(context).firstOrNull { it.uri == uri }

    fun setLast(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_LAST, id).apply()
    }

    fun getLastId(context: Context): String? =
        prefs(context).getString(KEY_LAST, null)

    private fun save(context: Context, list: List<NovelRecord>) {
        val arr = JSONArray()
        for (r in list) arr.put(r.toJson())
        prefs(context).edit().putString(KEY_LIBRARY, arr.toString()).apply()
    }
}