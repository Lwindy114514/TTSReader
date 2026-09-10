package com.noveltts.player.player

import android.content.Context
import android.net.Uri
import com.noveltts.player.data.Chapter
import com.noveltts.player.data.ChapterIndexCache
import com.noveltts.player.data.NovelReader
import com.noveltts.player.data.NovelRecord
import com.noveltts.player.data.NovelRepository
import com.noveltts.player.data.PlaySegment
import com.noveltts.player.resume.ProgressStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

/**
 * 全局播放核心（单例）：状态机 + 分段队列 + TTS 驱动。
 * Activity 与前台 Service 共用此实例，保证后台播放与断点保存一致。
 */
object PlaybackManager : TtsManager.Listener {

    enum class PlayState { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    private const val REFILL = 6
    private const val AUTO_SAVE_MS = 3000L

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(PlayState.IDLE)
    val state: StateFlow<PlayState> = _state.asStateFlow()

    private val _currentNovel = MutableStateFlow<NovelRecord?>(null)
    val currentNovel: StateFlow<NovelRecord?> = _currentNovel.asStateFlow()

    private val _currentChapter = MutableStateFlow<Chapter?>(null)
    val currentChapter: StateFlow<Chapter?> = _currentChapter.asStateFlow()

    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()

    private val _percent = MutableStateFlow(0.0)
    val percent: StateFlow<Double> = _percent.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var tts: TtsManager? = null
    private var reader: NovelReader? = null
    private var chapters: List<Chapter> = emptyList()
    private val queue = ArrayDeque<PlaySegment>()
    private var current: PlaySegment? = null
    private var cursor = 0L
    private var novel: NovelRecord? = null
    private var pendingStart = false
    private var pendingAutoResume = false
    private var saveJob: Job? = null
    private var lastSpeakMs = 0L
    private var lastCallbackMs = 0L
    private var stalledRetries = 0
    private var lastRetryMs = 0L
    private var pendingSubIndex = 0

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val ctx: Context get() = appContext

    // ---------- 对外操作 ----------

    fun loadNovel(record: NovelRecord, resumeOffset: Long?, resumeSubIndex: Int) {
        scope.launch {
            _state.value = PlayState.LOADING
            reader?.close()
            reader = null
            chapters = emptyList()
            queue.clear()
            current = null
            cursor = 0L
            pendingSubIndex = 0
            novel = record
            _currentNovel.value = record
            NovelRepository.setLast(ctx, record.id)
            _currentChapter.value = null
            _currentText.value = ""
            _percent.value = 0.0
            _errorMessage.value = null
            stopAutoSave()
            try {
                val opened = withContext(Dispatchers.IO) {
                    val r = NovelReader.open(ctx, Uri.parse(record.uri), charsetFor(record))
                    var chs = ChapterIndexCache.load(ctx, record)
                    if (chs == null) {
                        chs = r.buildChapterIndex()
                        ChapterIndexCache.save(ctx, record, chs)
                    }
                    Pair(r, chs)
                }
                reader = opened.first
                chapters = opened.second
                cursor = resumeOffset ?: 0L
                refillQueue()
                current = if (queue.isNotEmpty()) queue.removeFirst() else null
                android.util.Log.d(
                    "NovelTTS",
                    "[Checkpoint] restore offset=${resumeOffset ?: 0L} subIndex=$resumeSubIndex " +
                        "firstSeg=${current?.lineByteOffset}/${current?.subIndex} text=${current?.text?.take(24)?.replace('\n', ' ')}"
                )
                _state.value = PlayState.IDLE
                if (current != null) updateUi(current!!)
                else {
                    _state.value = PlayState.ERROR
                    _errorMessage.value = "文件读取完成但未找到可播放内容"
                }
            } catch (e: Exception) {
                _state.value = PlayState.ERROR
                _errorMessage.value = "打开小说失败：${e.message}"
            }
        }
    }

    fun play() {
        if (_state.value == PlayState.PLAYING) return
        if (novel == null) {
            _errorMessage.value = "请先打开一部小说"
            return
        }
        ensureTts()
        if (!(tts?.ready == true)) {
            _state.value = PlayState.PLAYING
            startAutoSave()
            pendingStart = true
            return
        }
        _state.value = PlayState.PLAYING
        startAutoSave()
        startSpeaking()
    }

    fun pause() {
        if (_state.value != PlayState.PLAYING) return
        _state.value = PlayState.PAUSED
        tts?.stop()
        stopAutoSave()
        saveProgressNow()
    }

    fun togglePlayPause() {
        if (_state.value == PlayState.PLAYING) pause() else play()
    }

    fun stop() {
        _state.value = PlayState.IDLE
        tts?.stop()
        stopAutoSave()
        saveProgressNow()
    }

    fun next() {
        val seg = current ?: return
        val base = seg.lineByteOffset
        while (queue.isNotEmpty() && queue.first().lineByteOffset == base) queue.removeFirst()
        if (queue.isEmpty()) refillQueue()
        while (queue.isNotEmpty() && queue.first().lineByteOffset == base) queue.removeFirst()
        if (queue.isEmpty()) {
            onNovelEnd()
            return
        }
        current = queue.removeFirst()
        updateUi(current!!)
        if (_state.value == PlayState.PLAYING) {
            markSpeak()
            tts?.speak(current!!.text, newUtteranceId())
            saveProgressNow()
        } else {
            saveReaderNow()
        }
    }

    /** 上一段：按“上一 Reading Block”跳转（同一章节内上一个阅读块起点）。 */
    fun prev() {
        val r = reader ?: return
        val seg = current ?: return
        val p = r.previousBlockStart(seg.lineByteOffset, chapters) ?: return
        queue.clear()
        cursor = p
        pendingSubIndex = 0
        refillQueue()
        if (queue.isEmpty()) return
        current = queue.removeFirst()
        updateUi(current!!)
        if (_state.value == PlayState.PLAYING) {
            markSpeak()
            tts?.speak(current!!.text, newUtteranceId())
            saveProgressNow()
        } else {
            saveReaderNow()
        }
    }

    // ---------- 章节目录：查询与跳转 ----------

    fun tocChapters(): List<Chapter> = chapters

    /** 当前所在章节下标；无小说/无章节时返回 -1。 */
    fun currentChapterIndex(): Int {
        if (chapters.isEmpty()) return -1
        val cur = current ?: return -1
        return cur.chapterIndex.coerceIn(0, chapters.lastIndex)
    }

    /**
     * 跳到指定章节：重建播放/阅读队列并从该章开头继续（不破坏原播放状态）。
     * 若之前正在播放则从目标章开始朗读；否则仅把阅读位置切到目标章开头。
     */
    fun jumpToChapter(index: Int) {
        if (index !in chapters.indices) return
        val wasPlaying = _state.value == PlayState.PLAYING
        val target = chapters[index].startByteOffset
        android.util.Log.d("NovelTTS", "[ChapterJump] to index=$index offset=$target (${chapters[index].title})")
        scope.launch {
            _errorMessage.value = null
            resetPlaybackTo(target)
            if (current == null) return@launch
            updateUi(current!!)
            // 行为区分：播放中跳章→记 TTS 位置；未播放跳章（阅读行为）→记阅读位置
            if (wasPlaying) saveProgressNow() else saveReaderNow()
            if (wasPlaying) {
                _state.value = PlayState.PLAYING
                startAutoSave()
                val t = tts
                if (t?.ready == true) {
                    markSpeak()
                    t.speak(current!!.text, newUtteranceId())
                } else {
                    pendingStart = true
                }
            }
        }
    }

    /** 下一章；成功返回 true，已是末章返回 false。 */
    fun nextChapter(): Boolean = moveChapter(1)

    /** 上一章；成功返回 true，已是首章返回 false。 */
    fun prevChapter(): Boolean = moveChapter(-1)

    private fun moveChapter(delta: Int): Boolean {
        if (chapters.isEmpty()) return false
        val cur = current ?: return false
        val idx = cur.chapterIndex.coerceIn(0, chapters.lastIndex)
        val target = idx + delta
        if (target !in chapters.indices) return false
        jumpToChapter(target)
        return true
    }

    /** 彻底重置队列并从 [offset] 重新建立位置（不改播放/暂停状态）。 */
    private fun resetPlaybackTo(offset: Long) {
        runCatching { tts?.stop() }
        stopAutoSave()
        queue.clear()
        current = null
        cursor = offset
        pendingSubIndex = 0
        refillQueue()
        current = if (queue.isNotEmpty()) queue.removeFirst() else null
    }

    fun setSpeed(v: Float) {
        ProgressStore.setSpeed(ctx, v)
        tts?.setSpeed(v)
    }

    fun setPitch(v: Float) {
        ProgressStore.setPitch(ctx, v)
        tts?.setPitch(v)
    }

    fun switchEngine(engine: String?) {
        ProgressStore.setEngine(ctx, engine)
        pendingAutoResume = true
        pendingStart = false
        runCatching { tts?.shutdown() }
        tts = null
        ensureTts()
    }

    fun setVoice(voice: String?) {
        ProgressStore.setVoice(ctx, voice)
        val t = tts ?: return
        pendingAutoResume = _state.value == PlayState.PLAYING
        pendingStart = false
        runCatching { t.shutdown() }
        tts = null
        ensureTts()
    }

    fun currentVoiceName(): String? = ProgressStore.getVoice(ctx)

    fun currentSpeed(): Float = ProgressStore.getSpeed(ctx)
    fun currentPitch(): Float = ProgressStore.getPitch(ctx)

    fun clearError() {
        _errorMessage.value = null
    }

    fun release() {
        stopAutoSave()
        saveProgressNow()
        tts?.shutdown()
        tts = null
        reader?.close()
        reader = null
    }

    // ---------- 内部 ----------

    private fun ensureTts() {
        if (tts == null) {
            val t = TtsManager(ctx, this)
            tts = t
            t.init(ProgressStore.getEngine(ctx), ProgressStore.getVoice(ctx))
        }
    }

    private fun startSpeaking() {
        val t = tts ?: return
        if (!t.ready) {
            pendingStart = true
            return
        }
        if (current == null) {
            if (queue.isEmpty()) refillQueue()
            if (queue.isNotEmpty()) current = queue.removeFirst()
        }
        val seg = current ?: run {
            onNovelEnd()
            return
        }
        markSpeak()
        t.speak(seg.text, newUtteranceId())
        _errorMessage.value = null
        updateUi(seg)
    }

    private fun advance() {
        if (_state.value != PlayState.PLAYING) return
        if (queue.isEmpty()) refillQueue()
        if (queue.isEmpty()) {
            onNovelEnd()
            return
        }
        current = queue.removeFirst()
        val t = tts ?: return
        if (t.ready) {
            markSpeak()
            t.speak(current!!.text, newUtteranceId())
            updateUi(current!!)
        } else {
            pendingStart = true
        }
    }

    private fun refillQueue() {
        val r = reader ?: return
        if (queue.size >= REFILL) return
        val res = r.readBlocksFrom(cursor, REFILL * 2, chapters)
        for (s in res.segments) queue.addLast(s)
        cursor = res.nextReadOffset
    }

    private fun onNovelEnd() {
        current = null
        _state.value = PlayState.IDLE
        stopAutoSave()
        saveProgressNow()
        _errorMessage.value = "已播放到小说结尾"
    }

    private fun updateUi(seg: PlaySegment) {
        _currentText.value = seg.text
        val ch = if (chapters.isEmpty()) null else chapters.getOrNull(seg.chapterIndex.coerceIn(0, chapters.size - 1))
        _currentChapter.value = ch
        val r = reader
        if (r != null && r.size > 0) {
            _percent.value = (seg.lineByteOffset.toDouble() / r.size).coerceIn(0.0, 1.0)
        }
    }

    private fun saveProgressNow() {
        val n = novel ?: return
        val seg = current ?: return
        val r = reader
        val pct = if (r != null && r.size > 0) seg.lineByteOffset.toDouble() / r.size else 0.0
        val ch = if (chapters.isEmpty()) null else chapters.getOrNull(seg.chapterIndex.coerceIn(0, chapters.size - 1))
        android.util.Log.d(
            "NovelTTS",
            "[Checkpoint] save offset=${seg.lineByteOffset} subIndex=${seg.subIndex} " +
                "text=${seg.text.take(24).replace('\n', ' ')}"
        )
        ProgressStore.saveProgress(
            ctx,
            com.noveltts.player.data.PlaybackProgress(
                novelId = n.id,
                lineByteOffset = seg.lineByteOffset,
                subIndex = seg.subIndex,
                chapterIndex = seg.chapterIndex,
                chapterTitle = ch?.title ?: "",
                percent = pct,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 记录「阅读位置」（用户手动阅读/跳转所在），与 TTS 位置独立存储、独立时间戳。 */
    private fun saveReaderNow() {
        val n = novel ?: return
        val seg = current ?: return
        val r = reader
        val pct = if (r != null && r.size > 0) seg.lineByteOffset.toDouble() / r.size else 0.0
        val ch = if (chapters.isEmpty()) null else chapters.getOrNull(seg.chapterIndex.coerceIn(0, chapters.size - 1))
        android.util.Log.d(
            "NovelTTS",
            "[ReaderPos] save offset=${seg.lineByteOffset} subIndex=${seg.subIndex} " +
                "text=${seg.text.take(24).replace('\n', ' ')}"
        )
        ProgressStore.saveReaderProgress(
            ctx,
            com.noveltts.player.data.PlaybackProgress(
                novelId = n.id,
                lineByteOffset = seg.lineByteOffset,
                subIndex = seg.subIndex,
                chapterIndex = seg.chapterIndex,
                chapterTitle = ch?.title ?: "",
                percent = pct,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun startAutoSave() {
        stopAutoSave()
        saveJob = scope.launch {
            while (isActive) {
                delay(AUTO_SAVE_MS)
                saveProgressNow()
                checkStall()
            }
        }
    }

    private fun stopAutoSave() {
        saveJob?.cancel()
        saveJob = null
    }

    private fun newUtteranceId(): String = "utt-" + System.nanoTime()

    private fun charsetFor(record: NovelRecord): Charset? {
        val enc = record.encoding
        if (enc.isNullOrBlank() || enc == "auto") return null
        return runCatching { Charset.forName(enc) }.getOrNull()
    }

    // ---------- TtsManager.Listener ----------

    override fun onTtsReady() {
        tts?.setSpeed(ProgressStore.getSpeed(ctx))
        tts?.setPitch(ProgressStore.getPitch(ctx))
        if (pendingAutoResume) {
            pendingAutoResume = false
            pendingStart = false
            if (_state.value == PlayState.PLAYING && current != null) {
                _errorMessage.value = null
                markSpeak()
                tts?.speak(current!!.text, newUtteranceId())
            }
        } else if (pendingStart) {
            pendingStart = false
            if (_state.value == PlayState.PLAYING) startSpeaking()
        }
    }

    override fun onTtsFailed(reason: String) {
        _errorMessage.value = reason
        if (_state.value == PlayState.PLAYING) {
            // 保留播放状态，等待用户处理或自动重试
        }
    }

    override fun onUtteranceDone(utteranceId: String) {
        lastCallbackMs = System.currentTimeMillis()
        stalledRetries = 0
        if (_state.value == PlayState.PLAYING) advance()
    }

    override fun onUtteranceError(utteranceId: String, errorCode: Int) {
        lastCallbackMs = System.currentTimeMillis()
        if (_state.value != PlayState.PLAYING) return
        _errorMessage.value = "TTS 播放异常（错误码 $errorCode），正在尝试恢复…"
        retryTts()
    }

    private fun retryTts() {
        pendingAutoResume = true
        pendingStart = false
        runCatching { tts?.shutdown() }
        tts = null
        ensureTts()
    }

    /**
     * 引擎卡死看门狗：长时间无任何 TTS 回调（不结束也不报错）时强制重建引擎。
     * 连续多次无响应则停止并提示切换引擎，避免无限静默。
     */
    private fun checkStall() {
        if (_state.value != PlayState.PLAYING) return
        if (tts?.ready != true) return
        val now = System.currentTimeMillis()
        val last = maxOf(lastSpeakMs, lastCallbackMs)
        if (now - last < stallTimeoutMs()) return
        if (now - lastRetryMs < 30_000L) return
        lastRetryMs = now
        stalledRetries++
        if (stalledRetries >= 3) {
            _errorMessage.value = "TTS 引擎长时间无响应，请点击右上角 TTS 按钮切换其他引擎"
            _state.value = PlayState.PAUSED
            stopAutoSave()
            saveProgressNow()
            return
        }
        _errorMessage.value = "TTS 引擎无响应，正在尝试恢复…"
        retryTts()
    }

    private fun stallTimeoutMs(): Long {
        val speed = ProgressStore.getSpeed(ctx).coerceIn(0.3f, 5.0f)
        return (90_000L * (1.0f / speed)).toLong().coerceIn(30_000L, 180_000L)
    }

    private fun markSpeak() {
        lastSpeakMs = System.currentTimeMillis()
    }
}