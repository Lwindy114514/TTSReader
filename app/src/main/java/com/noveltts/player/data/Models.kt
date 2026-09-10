package com.noveltts.player.data

import org.json.JSONObject

data class Chapter(
    val title: String,
    val startByteOffset: Long
)

data class NovelRecord(
    val id: String,
    val uri: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val encoding: String,
    val addedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("uri", uri)
        put("name", name)
        put("size", size)
        put("lastModified", lastModified)
        put("encoding", encoding)
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = NovelRecord(
            o.optString("id"),
            o.optString("uri"),
            o.optString("name"),
            o.optLong("size"),
            o.optLong("lastModified"),
            o.optString("encoding"),
            o.optLong("addedAt")
        )
    }
}

data class PlaySegment(
    val text: String,
    val lineByteOffset: Long,
    val subIndex: Int,
    val chapterIndex: Int
)

data class PlaybackProgress(
    val novelId: String,
    val lineByteOffset: Long,
    val subIndex: Int,
    val chapterIndex: Int,
    val chapterTitle: String,
    val percent: Double,
    val updatedAt: Long
)