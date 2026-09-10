package com.noveltts.player.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.noveltts.player.data.NovelRecord
import java.io.File

/**
 * 通过 ContentResolver 查询 SAF 文件的名称、大小、修改时间。
 */
object NovelFileInfo {

    fun query(context: Context, uri: Uri): Triple<String, Long, Long>? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                val modIdx = c.getColumnIndex("last_modified")
                val name = if (nameIdx >= 0) c.getString(nameIdx) else uri.lastPathSegment ?: "未知文件"
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else -1L
                val modified = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L
                Triple(name, size, modified)
            }
        }.getOrNull()
    }

    /** 检测文件是否已发生变化（大小或修改时间与记录不符）。 */
    fun fileChanged(context: Context, record: NovelRecord): Boolean {
        val info = query(context, Uri.parse(record.uri)) ?: return false
        if (info.second >= 0 && record.size > 0 && info.second != record.size) return true
        if (info.third > 0 && record.lastModified > 0 && info.third != record.lastModified) return true
        return false
    }
}