package com.noveltts.player.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 超大文本文件读取器：基于 FileChannel 随机访问 + 增量解码，
 * 绝不把整个文件读入内存。所有断点使用「精确行起始字节偏移」。
 */
class NovelReader private constructor(
    private val pfd: ParcelFileDescriptor,
    private val channel: FileChannel,
    val charset: Charset,
    val size: Long
) : Closeable {

    companion object {
        private val sentenceBoundary = Regex("(?<=[。！？…；!?;])")

        // ---- Reading Block 生成参数 ----
        private const val STANDALONE_CHARS = 200    // 超过则视为“长段落”，单独成块
        private const val GROUP_MAX_CHARS = 250     // 短段落合并的字符上限
        private const val GROUP_MAX_PARAS = 10      // 短段落合并的段落数上限
        private const val ULTRA_PARAGRAPH = 1500    // 超过则按句读切分为多个朗读片段
        private const val ULTRA_PART = 700

        fun open(context: Context, uri: Uri, charset: Charset? = null): NovelReader {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("无法打开文件：$uri")
            val ch = FileInputStream(pfd.fileDescriptor).channel
            val statSize = pfd.statSize
            val size = if (statSize > 0) statSize else ch.size()
            val enc = charset ?: run {
                val ins = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("无法读取文件内容：$uri")
                ins.use { CharsetDetector.detect(it, size) }
            }
            return NovelReader(pfd, ch, enc, size)
        }
    }

    /** 单次流式扫描建立章节索引，只保留标题与字节偏移，内存占用极小。 */
    fun buildChapterIndex(): List<Chapter> {
        val chapters = ArrayList<Chapter>()
        val it = LineIterator(channel, charset)
        while (true) {
            val line = it.next() ?: break
            if (line.text.isNotBlank()) {
                val hit = ChapterDetector.detect(line.text)
                if (hit != null && (chapters.isEmpty() || chapters.last().startByteOffset != line.startOffset)) {
                    chapters.add(Chapter(hit.title, line.startOffset))
                }
            }
        }
        return chapters
    }

    // ================= Reading Block（阅读/朗读的统一逻辑单元） =================
    // 长段落独立成块；连续的短段落合并成块；章节标题独立成块；块不跨章节。
    // 只影响“运行时组合”，不修改 TXT 原始段落结构。

    /** 段落是否为一章的首段（即章节标题段落）。 */
    private fun isHeading(chapters: List<Chapter>?, lineStart: Long): Boolean {
        if (chapters.isNullOrEmpty()) return false
        val ci = chapterIndexOf(chapters, lineStart)
        return chapters[ci].startByteOffset == lineStart
    }

    /**
     * 从 [offset] 向前生成若干个 Reading Block（以 [PlaySegment] 表达，text=合并文本，
     * lineByteOffset=块内首个原始段落偏移，subIndex=0）。流式、不跨章节、不改变原文。
     */
    fun readBlocksFrom(
        offset: Long,
        maxSegments: Int,
        chapters: List<Chapter>? = null
    ): SegmentsResult {
        val out = ArrayList<PlaySegment>()
        val it = LineIterator(channel, charset, offset)
        var nextRead = offset
        var eof = false

        val grp = ArrayList<String>()
        var grpStart = 0L
        var grpCi = -1
        var grpChars = 0

        fun flush() {
            if (grp.isNotEmpty()) {
                out.add(PlaySegment(grp.joinToString("\n\n"), grpStart, 0, grpCi))
                grp.clear()
                grpChars = 0
            }
        }

        fun emitStandalone(text: String, ls: Long, ci: Int) {
            if (text.length > ULTRA_PARAGRAPH) {
                // 极端超长段落：按句读切分片段，避免引擎压力；各片段仍以同一段落偏移。
                for (p in splitIntoParts(text)) out.add(PlaySegment(p, ls, 0, ci))
            } else {
                out.add(PlaySegment(text, ls, 0, ci))
            }
        }

        while (true) {
            val line = it.next()
            if (line == null) { eof = true; break }
            val ls = line.startOffset
            val text = line.text.trim()
            val nl = it.nextLineStart
            nextRead = nl
            if (text.isEmpty()) continue
            val ci = chapterIndexOf(chapters, ls)

            if (isHeading(chapters, ls)) {
                // 章节标题：强制独立成块（若之前有开放短段组先结束）
                flush()
                emitStandalone(text, ls, ci)
                if (out.size >= maxSegments) break
                continue
            }

            if (text.length > STANDALONE_CHARS) {
                flush()
                emitStandalone(text, ls, ci)
                if (out.size >= maxSegments) break
                continue
            }

            // 可合并段落
            if (grp.isEmpty()) {
                grpStart = ls
                grpCi = ci
            } else if (ci != grpCi) {
                flush()
                grpStart = ls
                grpCi = ci
            }
            if (grp.isNotEmpty() &&
                (grpChars + text.length > GROUP_MAX_CHARS || grp.size >= GROUP_MAX_PARAS)
            ) {
                flush()
                grpStart = ls
                grpCi = ci
            }
            grp.add(text)
            grpChars += text.length
        }
        flush()
        if (eof) nextRead = channel.size()
        return SegmentsResult(out, nextRead)
    }

    /**
     * 返回 [offset] 所属章节内、位于其所在块之前的那个 Reading Block 的起始段落偏移；
     * 若当前块就是本章第一个块（例如位于章节标题），返回 null。
     * 通过从本章标题顺序重建分块确定，保证与正向生成一致。
     */
    fun previousBlockStart(offset: Long, chapters: List<Chapter>?): Long? {
        if (chapters.isNullOrEmpty()) return null
        val ci = chapterIndexOf(chapters, offset)
        val chStart = chapters[ci].startByteOffset
        val chEnd = chapters.getOrNull(ci + 1)?.startByteOffset ?: channel.size()

        val it = LineIterator(channel, charset, chStart)
        val grp = ArrayList<String>()
        var grpStart = -1L
        var grpChars = 0
        var prevBlockStart: Long? = null
        var found = false

        while (true) {
            val line = it.next() ?: break
            val ls = line.startOffset
            if (ls >= chEnd && ls > chStart) break
            if (ls > offset) break
            val text = line.text.trim()
            if (text.isEmpty()) continue
            if (ls == offset) { found = true; break }

            val isHead = isHeading(chapters, ls)
            val standalone = isHead || text.length > STANDALONE_CHARS

            if (standalone) {
                if (grp.isNotEmpty()) grp.clear()
                grpChars = 0
                prevBlockStart = ls
                continue
            }

            if (grp.isEmpty()) {
                grpStart = ls
                grp.add(text)
                grpChars = text.length
            } else if (grpChars + text.length > GROUP_MAX_CHARS || grp.size >= GROUP_MAX_PARAS) {
                prevBlockStart = grpStart
                grpStart = ls
                grp.clear()
                grpChars = 0
                grp.add(text)
                grpChars += text.length
            } else {
                grp.add(text)
                grpChars += text.length
            }
        }
        if (!found) {
            // 走到 offset 前仍在短段组内：该组起点即“上一块”
            if (grp.isNotEmpty() && grpStart >= 0) return grpStart
            return prevBlockStart
        }
        // offset 本身是某块起点：返回其前一块起点
        return if (grp.isEmpty()) prevBlockStart else grpStart
    }

    /** 按句读把超长文本切分为 ≤ [ULTRA_PART] 的片段。 */
    private fun splitIntoParts(text: String): List<String> {
        if (text.length <= ULTRA_PART) return listOf(text)
        val parts = text.split(sentenceBoundary)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (p in parts) {
            if (p.isBlank()) continue
            if (cur.isNotEmpty() && cur.length + p.length > ULTRA_PART) {
                out.add(cur.toString().trim())
                cur.clear()
            }
            cur.append(p)
        }
        if (cur.isNotEmpty()) out.add(cur.toString().trim())
        return out
    }

    /**
     * 从指定字节偏移向前读取若干 TTS 播放片段。
     * [startSubIndex] 用于续读同一行时跳过已消费的 chunk（见 [SegmentsResult.nextSubIndex]）。
     * 保证「不重复、不丢失」：已完整消费的行从下一行起点继续读取。
     */
    fun readSegmentsFrom(
        offset: Long,
        maxSegments: Int,
        maxBytes: Long = 256L * 1024,
        chapters: List<Chapter>? = null,
        startSubIndex: Int = 0
    ): SegmentsResult {
        val result = ArrayList<PlaySegment>()
        val it = LineIterator(channel, charset, offset)
        val stopAt = offset + maxBytes
        var nextRead = offset
        var nextSub = 0
        var eof = false
        var partial = false
        while (true) {
            val line = it.next()
            if (line == null) {
                eof = true
                break
            }
            val lineStart = line.startOffset
            if (lineStart > stopAt && result.isNotEmpty()) {
                nextRead = lineStart
                nextSub = 0
                break
            }
            val text = line.text.trim()
            if (text.isEmpty()) {
                nextRead = it.nextLineStart
                nextSub = 0
                continue
            }
            val ci = chapterIndexOf(chapters, lineStart)
            val chunks = splitChunks(text)
            val skipIdx = if (lineStart == offset) startSubIndex else 0
            for (i in skipIdx until chunks.size) {
                if (result.size >= maxSegments) {
                    nextRead = lineStart
                    nextSub = i
                    partial = true
                    break
                }
                result.add(PlaySegment(chunks[i], lineStart, i, ci))
            }
            if (partial) break
            nextRead = it.nextLineStart
            nextSub = 0
            if (result.size >= maxSegments) break
        }
        if (eof) {
            nextRead = channel.size()
            nextSub = 0
        }
        return SegmentsResult(result, nextRead, nextSub)
    }

    fun chapterIndexOf(chapters: List<Chapter>?, offset: Long): Int {
        if (chapters.isNullOrEmpty()) return 0
        var lo = 0
        var hi = chapters.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (chapters[mid].startByteOffset <= offset) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** 返回给定偏移之前那一个「非空行」的起始字节偏移；无则返回 null。 */
    fun previousLineStart(offset: Long): Long? {
        if (offset <= 0) return null
        var nl = lastIndexOfNewline(offset - 1)
        if (nl < 0) return null
        while (true) {
            // nl 是某个换行符位置；该行内容区间为 [prevNl+1, nl)，跳过空行
            val prevNl = lastIndexOfNewline(nl - 1)
            if (prevNl < 0) return 0
            val start = prevNl + 1
            if (!isBlankRange(start, nl)) return start
            nl = prevNl
        }
    }

    /** 判断 [start, end) 字节区间是否全为空白（空白/制表/回车/换行）。 */
    private fun isBlankRange(start: Long, end: Long): Boolean {
        var pos = start
        val bufSize = 4096
        while (pos < end) {
            val toRead = minOf(bufSize.toLong(), end - pos).toInt()
            val bb = ByteBuffer.allocate(toRead)
            val n = channel.read(bb, pos)
            if (n <= 0) return true
            val bytes = bb.array()
            for (i in 0 until n) {
                val b = bytes[i]
                if (b != 0x20.toByte() && b != 0x09.toByte() && b != 0x0D.toByte() && b != 0x0A.toByte()) return false
            }
            pos += n
        }
        return true
    }

    /** 从 position 向前搜索最近一个 '\n' 的字节偏移，找不到返回 -1。 */
    private fun lastIndexOfNewline(position: Long): Long {
        var pos = position
        val bufSize = 64 * 1024
        while (pos >= 0) {
            val readStart = maxOf(0L, pos - bufSize + 1)
            val toRead = (pos - readStart + 1).toInt()
            val bb = ByteBuffer.allocate(toRead)
            val n = channel.read(bb, readStart)
            if (n <= 0) return -1
            val bytes = bb.array()
            var i = n - 1
            while (i >= 0) {
                if (bytes[i] == 0x0A.toByte()) return readStart + i
                i--
            }
            pos = readStart - 1
        }
        return -1
    }

    private fun splitChunks(text: String, maxChars: Int = 300): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val parts = text.split(sentenceBoundary)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (p in parts) {
            if (p.isBlank()) continue
            if (cur.isNotEmpty() && cur.length + p.length > maxChars) {
                out.add(cur.toString().trim())
                cur.clear()
            }
            cur.append(p)
        }
        if (cur.isNotEmpty()) out.add(cur.toString().trim())
        return out
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { pfd.close() }
    }
}

/** 分段读取结果：片段列表 + 下一批应继续读取的字节偏移 + 续读行的 chunk 起始下标。 */
data class SegmentsResult(
    val segments: List<PlaySegment>,
    val nextReadOffset: Long,
    val nextSubIndex: Int = 0
)

/** 前向逐行迭代器，逐行字节精确、支持跨窗口拼接多字节字符。 */
class LineIterator(
    private val channel: FileChannel,
    private val charset: Charset,
    startOffset: Long = 0L
) {
    class Line(val text: String, val startOffset: Long)

    private val BUF = 256 * 1024
    private val MAX_LINE_BYTES = 8L * 1024 * 1024

    private var buf = ByteArray(0)
    private var bufStart = 0L
    private var posInBuf = 0
    private var carry = ByteArray(0)
    private var lineStart = startOffset
    private var readPos = startOffset
    private var eof = false

    /** 下一次 next() 将返回的行的起始字节偏移（当前已消费内容之后的位置）。 */
    val nextLineStart: Long get() = lineStart

    fun next(): Line? {
        while (true) {
            if (posInBuf >= buf.size) {
                if (eof) {
                    if (carry.isNotEmpty()) {
                        val start = lineStart
                        val text = decodeLine(carry)
                        carry = ByteArray(0)
                        return Line(text, start)
                    }
                    return null
                }
                if (!loadNextWindow()) {
                    eof = true
                    continue
                }
            }
            val nl = indexOfNewline(buf, posInBuf)
            if (nl >= 0) {
                val len = carry.size + (nl - posInBuf)
                val lineBytes = ByteArray(len)
                if (carry.isNotEmpty()) System.arraycopy(carry, 0, lineBytes, 0, carry.size)
                System.arraycopy(buf, posInBuf, lineBytes, carry.size, nl - posInBuf)
                val start = lineStart
                lineStart = bufStart + (nl + 1)
                posInBuf = nl + 1
                carry = ByteArray(0)
                return Line(decodeLine(lineBytes), start)
            } else {
                val addLen = buf.size - posInBuf
                val newCarry = ByteArray(carry.size + addLen)
                if (carry.isNotEmpty()) System.arraycopy(carry, 0, newCarry, 0, carry.size)
                System.arraycopy(buf, posInBuf, newCarry, carry.size, addLen)
                carry = newCarry
                posInBuf = buf.size
                if (carry.size >= MAX_LINE_BYTES) {
                    val start = lineStart
                    val text = decodeLine(carry)
                    carry = ByteArray(0)
                    return Line(text, start)
                }
            }
        }
    }

    private fun loadNextWindow(): Boolean {
        val bytes = ByteArray(BUF)
        val n = channel.read(ByteBuffer.wrap(bytes), readPos)
        if (n <= 0) return false
        buf = if (n == BUF) bytes else bytes.copyOf(n)
        bufStart = readPos
        posInBuf = 0
        readPos += n
        return true
    }

    private fun indexOfNewline(buf: ByteArray, from: Int): Int {
        var i = from
        while (i < buf.size) {
            if (buf[i] == 0x0A.toByte()) return i
            i++
        }
        return -1
    }

    private fun decodeLine(b: ByteArray): String {
        var len = b.size
        if (len > 0 && b[len - 1] == 0x0D.toByte()) len--
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.IGNORE)
            .onUnmappableCharacter(CodingErrorAction.IGNORE)
        return decoder.decode(ByteBuffer.wrap(b, 0, len)).toString()
    }
}