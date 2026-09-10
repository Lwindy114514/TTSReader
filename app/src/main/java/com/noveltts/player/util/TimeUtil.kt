package com.noveltts.player.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeUtil {
    fun friendly(ts: Long): String {
        if (ts <= 0) return "未知"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}