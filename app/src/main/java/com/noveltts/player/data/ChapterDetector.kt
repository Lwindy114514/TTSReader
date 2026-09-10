package com.noveltts.player.data

/**
 * 章节标题识别器（纯逻辑、无 Android 依赖、可独立测试/扩展）。
 *
 * 识别原则：
 *  - 只接受「独立整行」，且标题必须锚定在行首（允许首尾装饰符/空白）。
 *  - 有标题长度上限，正文大段/长句不会被当成标题。
 *  - 「他看完了第三章的内容之后…」这类以正文开头的句子永远不会命中（不锚定且过长）。
 *
 * 新增章节格式只需扩展下面的 pattern 列表，不必改动调用方/UI。
 */
object ChapterDetector {

    data class Hit(val title: String)

    private const val MAX_TITLE_LEN = 60
    private const val REJECT_LEN = 90

    /** 行首/行尾可能出现的装饰字符（标题独占一行时常见）。 */
    private val ORNAMENTS = setOf(
        ' ', '\t', '\u3000', // 空格/制表/全角空格
        '─', '═', '━', '—', '-', '=', '–', '—', '～', '~',
        '·', '•', '.', '＊', '*', '#', '※'
    )

    /** 数字字符集（阿拉伯 + 全角 + 中文）。 */
    private val NUM = "[0-9０-９零〇一二三四五六七八九十百千两]+"

    /** 章/回/节/卷/部/集/篇/幕 等单位词。 */
    private val UNIT = "[章节回卷部集篇幕]"

    /** 带「第」前缀：第1章 / 第十二章 / 第001章 / 第〇一章… */
    private val P_DI = Regex("^第$NUM$UNIT")

    /** 不带「第」的卷/部等：卷一、第二部（第二部由 P_DI 命中，此处兜底卷一、上卷等也可加规则）。 */
    private val P_PURE_NUM = Regex("^[卷部集篇册]$NUM")

    /** 英文：Chapter/Vol(ume)/Book/Act/Part + 数字。 */
    private val P_EN = Regex("^(?:[Cc]hapter|[Vv]ol(?:ume)?|[Bb]ook|[Aa]ct|[Pp]art)\\s*\\d+")

    /** 无数字的独立篇目标题（出现即视为章节/分卷分隔）。按需在此扩展。 */
    private val BARE_WORDS = listOf(
        "序章", "序言", "楔子", "引子", "前言", "题记",
        "尾声", "终章", "番外", "番外篇", "外传", "后记", "大结局",
        "正文", "卷首", "尾声之章"
    )

    /** 标题核心（数字前缀/篇目标记词）匹配器，均要求锚定行首。 */
    private val CORE_PATTERNS = listOf(P_DI, P_PURE_NUM, P_EN)

    /** 句内标点：标题核心之后的「长尾巴」若含这些标点，多半是正文句子而非标题。 */
    private val SENTENCE_PUNCT = setOf('，', '。', '、', '！', '？', '…', '；')

    /** 判断一行是否为章节标题；非标题返回 null。lineText 允许含前后空白。 */
    fun detect(lineText: String): Hit? {
        val raw = lineText.trim()
        if (raw.isEmpty()) return null
        val line = stripOrnaments(raw)
        if (line.isEmpty()) return null
        if (line.length > REJECT_LEN) return null

        val coreLen = coreLength(line) ?: return null
        val tail = line.substring(coreLen).trim()
        // 正文句子常形如「第二部份为靠近中央…处，被称为…」：核心词后带长尾巴且含句读 → 判为正文
        if (tail.length > 15 && tail.any { it in SENTENCE_PUNCT }) return null

        // 标题保留装饰清理后、去掉多余空白后的整行（截断展示上限）
        val title = collapse(line).take(MAX_TITLE_LEN)
        return Hit(title)
    }

    /** 返回行首命中的标题核心长度；不命中返回 null。 */
    private fun coreLength(line: String): Int? {
        for (p in CORE_PATTERNS) {
            p.find(line)?.let { return it.range.last + 1 }
        }
        // 独立篇目标记词：取命中的最长词
        var best = -1
        for (w in BARE_WORDS) {
            if (line.startsWith(w) && w.length > best) best = w.length
        }
        return if (best > 0) best else null
    }

    /** 去掉首尾的空白与装饰符（非空白、非正文符号）。 */
    private fun stripOrnaments(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && s[start] in ORNAMENTS) start++
        while (end > start && s[end - 1] in ORNAMENTS) end--
        return s.substring(start, end)
    }

    private fun collapse(s: String): String =
        s.replace(Regex("[ \\t\\u3000]+"), " ").trim()
}
