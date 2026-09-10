package com.noveltts.player.data

import android.content.Context
import android.net.Uri
import com.noveltts.player.util.NovelFileInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 章节索引缓存：按小说 id 落盘，携带「文件指纹」（实时 size + lastModified + 编码）。
 * 指纹一致直接复用索引（毫秒级打开），否则下次打开自动重新扫描覆盖。
 * 只存必要字段（标题 + 字节偏移），大文件多章节也不会显著占内存。
 */
object ChapterIndexCache {

    fun load(context: Context, record: NovelRecord): List<Chapter>? {
        val f = cacheFile(context, record.id) ?: return null
        if (!f.exists()) return null
        val stat = NovelFileInfo.query(context, Uri.parse(record.uri))
        val size = stat?.second ?: record.size
        val modified = stat?.third ?: record.lastModified
        return runCatching {
            val o = JSONObject(f.readText())
            if (o.optLong("size") != size) return null
            if (o.optLong("lastModified") != modified) return null
            if (o.optString("encoding") != (record.encoding ?: "")) return null
            val arr = o.optJSONArray("chapters") ?: return null
            val out = ArrayList<Chapter>(arr.length())
            for (i in 0 until arr.length()) {
                val e = arr.getJSONArray(i)
                out.add(Chapter(e.optString(0), e.optLong(1)))
            }
            out
        }.getOrNull()
    }

    fun save(context: Context, record: NovelRecord, chapters: List<Chapter>) {
        val f = cacheFile(context, record.id) ?: return
        val stat = NovelFileInfo.query(context, Uri.parse(record.uri))
        val o = JSONObject().apply {
            put("size", stat?.second ?: record.size)
            put("lastModified", stat?.third ?: record.lastModified)
            put("encoding", record.encoding ?: "")
            val arr = JSONArray()
            for (c in chapters) {
                arr.put(JSONArray().put(c.title).put(c.startByteOffset))
            }
            put("chapters", arr)
        }
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(o.toString())
        }
    }

    fun clear(context: Context, novelId: String) {
        cacheFile(context, novelId)?.delete()
    }

    private fun cacheFile(context: Context, novelId: String): File? {
        if (novelId.isBlank()) return null
        val safe = novelId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(context.filesDir, "chapters").resolve("$safe.json")
    }
}
