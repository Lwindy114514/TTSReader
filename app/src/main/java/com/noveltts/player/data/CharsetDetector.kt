package com.noveltts.player.data

import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 中文文本编码探测。
 * 顺序：BOM -> UTF-8 严格校验 -> UTF-16 启发 -> 兜底 GB18030（GBK 超集，可解码绝大多数中文文本）。
 */
object CharsetDetector {

    const val GB18030 = "GB18030"

    fun detect(input: InputStream, length: Long): Charset {
        val n = minOf(length, 65536L).toInt()
        val bytes = ByteArray(n)
        var read = 0
        var r: Int
        while (read < n) {
            r = input.read(bytes, read, n - read)
            if (r < 0) break
            read += r
        }
        return detectBytes(bytes.copyOf(read))
    }

    fun detectBytes(b: ByteArray): Charset {
        if (b.size >= 3 && u(b[0]) == 0xEF && u(b[1]) == 0xBB && u(b[2]) == 0xBF) {
            return StandardCharsets.UTF_8
        }
        if (b.size >= 4 && u(b[0]) == 0x00 && u(b[1]) == 0x00 && u(b[2]) == 0xFE && u(b[3]) == 0xFF) {
            return Charset.forName("UTF-32BE")
        }
        if (b.size >= 4 && u(b[0]) == 0xFF && u(b[1]) == 0xFE && u(b[2]) == 0x00 && u(b[3]) == 0x00) {
            return Charset.forName("UTF-32LE")
        }
        if (b.size >= 2 && u(b[0]) == 0xFE && u(b[1]) == 0xFF) {
            return Charset.forName("UTF-16BE")
        }
        if (b.size >= 2 && u(b[0]) == 0xFF && u(b[1]) == 0xFE) {
            return Charset.forName("UTF-16LE")
        }

        if (isValidUtf8(b)) {
            return StandardCharsets.UTF_8
        }

        // UTF-16 without BOM heuristic: many zero bytes in one parity.
        val utf16 = detectUtf16NoBom(b)
        if (utf16 != null) return utf16

        return Charset.forName(GB18030)
    }

    private fun detectUtf16NoBom(b: ByteArray): Charset? {
        if (b.size < 64) return null
        val sample = minOf(b.size, 4096)
        var evenZero = 0
        var oddZero = 0
        var total = 0
        var i = 0
        while (i + 1 < sample) {
            val hi = u(b[i])
            val lo = u(b[i + 1])
            if (hi == 0) evenZero++
            if (lo == 0) oddZero++
            total++
            i += 2
        }
        if (total == 0) return null
        val evenRatio = evenZero.toFloat() / total
        val oddRatio = oddZero.toFloat() / total
        return when {
            evenRatio > 0.3f -> Charset.forName("UTF-16BE")
            oddRatio > 0.3f -> Charset.forName("UTF-16LE")
            else -> null
        }
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    private fun isCont(b: Byte): Boolean = (b.toInt() and 0xC0) == 0x80

    private fun isValidUtf8(b: ByteArray): Boolean {
        var i = 0
        val len = b.size
        while (i < len) {
            val c = u(b[i])
            when {
                c < 0x80 -> i++
                c in 0xC2..0xDF -> {
                    // 序列延伸出采样末尾视为合法（截断的多字节字符）
                    if (i + 1 >= len) return true
                    if (!isCont(b[i + 1])) return false
                    i += 2
                }
                c in 0xE0..0xEF -> {
                    if (i + 2 >= len) return true
                    if (!isCont(b[i + 1]) || !isCont(b[i + 2])) return false
                    i += 3
                }
                c in 0xF0..0xF4 -> {
                    if (i + 3 >= len) return true
                    if (!isCont(b[i + 1]) || !isCont(b[i + 2]) || !isCont(b[i + 3])) return false
                    i += 4
                }
                else -> return false
            }
        }
        return true
    }
}