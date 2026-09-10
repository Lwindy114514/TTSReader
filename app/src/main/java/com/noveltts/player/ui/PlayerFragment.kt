package com.noveltts.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.noveltts.player.databinding.FragmentPlayerBinding
import com.noveltts.player.player.PlaybackManager
import com.noveltts.player.player.PlaybackManager.PlayState
import com.noveltts.player.service.MediaPlaybackService
import kotlinx.coroutines.launch

/**
 * 播放 / 阅读页：一次只显示一个「Reading Block」（已合并的短段落或完整长段落），
 * 该块也是 TTS 一次朗读、上一段/下一段一次跳转、断点定位的统一单位。
 */
class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val speed = PlaybackManager.currentSpeed()
        val pitch = PlaybackManager.currentPitch()
        binding.sbSpeed.progress = ((speed - 0.5f) * 20f).toInt().coerceIn(0, 30)
        binding.sbPitch.progress = ((pitch - 0.5f) * 20f).toInt().coerceIn(0, 30)

        binding.btnPlay.setOnClickListener {
            PlaybackManager.togglePlayPause()
            if (PlaybackManager.state.value == PlayState.PLAYING) {
                MediaPlaybackService.start(requireContext())
            }
        }
        binding.btnPrev.setOnClickListener { PlaybackManager.prev() }
        binding.btnNext.setOnClickListener { PlaybackManager.next() }
        binding.btnToc.setOnClickListener {
            TocSheetFragment().show(parentFragmentManager, "toc")
        }
        binding.btnPrevChapter.setOnClickListener {
            if (!PlaybackManager.prevChapter()) {
                Toast.makeText(requireContext(), "已经是第一章", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnNextChapter.setOnClickListener {
            if (!PlaybackManager.nextChapter()) {
                Toast.makeText(requireContext(), "已经是最后一章", Toast.LENGTH_SHORT).show()
            }
        }

        binding.sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) PlaybackManager.setSpeed(0.5f + progress / 20f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.sbPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) PlaybackManager.setPitch(0.5f + progress / 20f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { PlaybackManager.state.collect { renderState(it) } }
                launch { PlaybackManager.currentNovel.collect { binding.tvNovelName.text = it?.name ?: "" } }
                launch { PlaybackManager.currentChapter.collect { binding.tvChapter.text = it?.title ?: "" } }
                launch { PlaybackManager.percent.collect { binding.tvProgress.text = "进度：%.1f%%".format(it * 100) } }
                launch { PlaybackManager.currentText.collect { binding.tvText.text = it } }
                launch { PlaybackManager.errorMessage.collect { renderError(it) } }
            }
        }
    }

    private fun renderState(s: PlayState) {
        binding.btnPlay.text = when (s) {
            PlayState.PLAYING -> "暂停"
            PlayState.PAUSED -> "继续"
            else -> "播放"
        }
    }

    private fun renderError(msg: String?) {
        if (msg.isNullOrBlank()) {
            binding.tvError.visibility = View.GONE
            binding.tvError.text = ""
        } else {
            binding.tvError.visibility = View.VISIBLE
            binding.tvError.text = msg
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
