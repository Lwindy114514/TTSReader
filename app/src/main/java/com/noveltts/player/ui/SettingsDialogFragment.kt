package com.noveltts.player.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.noveltts.player.player.PlaybackManager
import com.noveltts.player.resume.ProgressStore
import java.util.Locale

class SettingsDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val engines = queryEngines(ctx)
        val current = ProgressStore.getEngine(ctx)
        val labels = engines.map { it.label }
        val selected = engines.indexOfFirst { it.name == current }

        val builder = AlertDialog.Builder(ctx)
            .setTitle("TTS 设置")

        if (engines.isNotEmpty()) {
            builder.setSingleChoiceItems(labels.toTypedArray(), if (selected >= 0) selected else 0) { _, which ->
                val engine = engines[which].name
                PlaybackManager.switchEngine(engine)
                Toast.makeText(ctx, "已切换引擎：${engines[which].label}", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        } else {
            builder.setMessage("未检测到任何 TTS 引擎，请先安装中文语音包")
        }

        builder.setPositiveButton("中文语音…") { _, _ -> openVoiceDialog() }
        builder.setNeutralButton("系统 TTS 设置") { _, _ ->
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        }
        builder.setNegativeButton("关闭", null)
        return builder.create()
    }

    /**
     * 中文语音选择：枚举当前引擎已安装的 zh-CN 离线语音并持久化。
     * 选中后 App 每次启动都通过 setVoice 固定使用该语音，不再依赖系统默认语音。
     */
    private fun openVoiceDialog() {
        val ctx = requireContext()
        val engine = ProgressStore.getEngine(ctx)
        val loading = AlertDialog.Builder(ctx)
            .setTitle("中文语音")
            .setMessage("正在读取语音列表…")
            .setCancelable(true)
            .create()
        loading.show()

        var tts: TextToSpeech? = null
        tts = TextToSpeech(ctx, { status ->
            loading.dismiss()
            if (status != TextToSpeech.SUCCESS) {
                runCatching { tts?.shutdown() }
                Toast.makeText(ctx, "无法读取语音列表", Toast.LENGTH_SHORT).show()
                return@TextToSpeech
            }
            val t = tts
            val voices = runCatching { t?.voices }.getOrNull().orEmpty()
                .filter { it.locale == Locale.CHINA }
                .filter { !it.isNetworkConnectionRequired }
                .filter { !it.name.endsWith("-language") }
                .sortedBy { it.name }
            runCatching { t?.shutdown() }
            tts = null
            if (voices.isEmpty()) {
                Toast.makeText(ctx, "当前引擎没有可用的中文离线语音", Toast.LENGTH_SHORT).show()
                return@TextToSpeech
            }
            showVoiceChooser(voices)
        }, engine)
    }

    private fun showVoiceChooser(voices: List<Voice>) {
        val ctx = requireContext()
        val saved = ProgressStore.getVoice(ctx)
        val labels = ArrayList<String>()
        labels.add("跟随系统默认")
        val names = ArrayList<String?>()
        names.add(null)
        for (v in voices) {
            labels.add(voiceLabel(v))
            names.add(v.name)
        }
        val checked = names.indexOf(saved).coerceAtLeast(0)

        AlertDialog.Builder(ctx)
            .setTitle("选择中文语音")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { _, which ->
                val name = names[which]
                PlaybackManager.setVoice(name)
                val shown = labels[which]
                Toast.makeText(ctx, "已固定语音：$shown（重启后仍然有效）", Toast.LENGTH_LONG).show()
                dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 将引擎语音映射为可读标签；cmn-cn-x-ccd 即系统设置中的“语音II”。 */
    private fun voiceLabel(v: Voice): String {
        val fam = v.name.removePrefix("cmn-cn-x-").removeSuffix("-local").removeSuffix("-network")
        val hint = when (fam) {
            "ccd" -> "（= 系统语音 II）"
            "ssa" -> "（= 系统语音 I）"
            else -> ""
        }
        return "中文（普通话）$fam $hint".trim()
    }

    private fun queryEngines(ctx: android.content.Context): List<TextToSpeech.EngineInfo> {
        var tts: TextToSpeech? = null
        return runCatching {
            tts = TextToSpeech(ctx) { }
            tts?.getEngines() ?: emptyList()
        }.getOrDefault(emptyList()).also {
            runCatching { tts?.shutdown() }
        }
    }
}