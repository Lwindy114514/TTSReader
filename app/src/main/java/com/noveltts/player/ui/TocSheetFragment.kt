package com.noveltts.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.noveltts.player.data.Chapter
import com.noveltts.player.databinding.FragmentTocSheetBinding
import com.noveltts.player.databinding.ItemChapterBinding
import com.noveltts.player.player.PlaybackManager

/**
 * 章节目录（BottomSheet）。RecyclerView + ListAdapter 保证上千章节也能流畅滚动。
 * 打开时标记并滚动到当前正在播放/阅读的章节；点击章节立即跳转。
 */
class TocSheetFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentTocSheetBinding? = null
    private val binding get() = _binding!!
    private var adapter: ChapterAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = FragmentTocSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val chapters = PlaybackManager.tocChapters()
        val current = PlaybackManager.currentChapterIndex()

        binding.rvChapters.layoutManager = LinearLayoutManager(requireContext())
        binding.tvSheetTitle.text =
            if (chapters.isNotEmpty()) "章节目录 · ${chapters.size} 章" else "章节目录"

        if (chapters.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvChapters.visibility = View.GONE
            return
        }

        adapter = ChapterAdapter(current.coerceAtLeast(0)) { pos ->
            PlaybackManager.jumpToChapter(pos)
            dismiss()
        }
        binding.rvChapters.adapter = adapter
        adapter?.submitList(chapters)
        binding.rvChapters.post {
            binding.rvChapters.scrollToPosition(current.coerceAtLeast(0))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        adapter = null
        _binding = null
    }

    private class ChapterAdapter(
        private val currentIndex: Int,
        private val onClick: (Int) -> Unit
    ) : ListAdapter<Chapter, ChapterAdapter.VH>(DIFF) {

        class VH(val b: ItemChapterBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemChapterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            val isCurrent = position == currentIndex
            holder.b.tvChapterTitle.text = item.title
            holder.b.tvMarker.visibility = if (isCurrent) View.VISIBLE else View.GONE
            holder.b.rowRoot.setBackgroundColor(if (isCurrent) 0x1A3F51B5 else 0x00000000)
            holder.b.rowRoot.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(pos)
            }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<Chapter>() {
                override fun areItemsTheSame(a: Chapter, b: Chapter) =
                    a.startByteOffset == b.startByteOffset

                override fun areContentsTheSame(a: Chapter, b: Chapter) =
                    a.title == b.title && a.startByteOffset == b.startByteOffset
            }
        }
    }
}
