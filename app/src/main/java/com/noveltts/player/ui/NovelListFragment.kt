package com.noveltts.player.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.noveltts.player.R
import com.noveltts.player.data.CharsetDetector
import com.noveltts.player.data.NovelRecord
import com.noveltts.player.data.NovelRepository
import com.noveltts.player.databinding.FragmentNovelListBinding
import com.noveltts.player.databinding.ItemNovelBinding
import com.noveltts.player.util.NovelFileInfo
import com.noveltts.player.util.TimeUtil
import java.util.UUID

class NovelListFragment : Fragment() {

    interface Listener {
        fun onOpenNovel(record: NovelRecord)
        fun onShowSettings()
    }

    private var _binding: FragmentNovelListBinding? = null
    private val binding get() = _binding!!
    private val listener: Listener? get() = activity as? Listener
    private lateinit var adapter: NovelAdapter

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addNovel(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNovelListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.btnAdd.setOnClickListener {
            picker.launch(arrayOf("text/plain", "text/*", "*/*"))
        }
        binding.btnSettings.setOnClickListener {
            listener?.onShowSettings()
        }
        binding.btnLast.setOnClickListener {
            resumeLast()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refresh() {
        val list = NovelRepository.getLibrary(requireContext())
        adapter = NovelAdapter(list) { record ->
            listener?.onOpenNovel(record)
        }
        binding.recycler.adapter = adapter
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun resumeLast() {
        val ctx = requireContext()
        val id = NovelRepository.getLastId(ctx) ?: run {
            Toast.makeText(ctx, "暂无播放记录", Toast.LENGTH_SHORT).show()
            return
        }
        val record = NovelRepository.getNovel(ctx, id)
        if (record == null) {
            NovelRepository.setLast(ctx, null)
            Toast.makeText(ctx, "播放记录已失效", Toast.LENGTH_SHORT).show()
            return
        }
        listener?.onOpenNovel(record)
    }

    private fun addNovel(uri: Uri) {
        val ctx = requireContext()
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }

            val info = NovelFileInfo.query(ctx, uri)
                ?: throw IllegalStateException("无法读取该文件的信息")
            val size = if (info.second > 0) info.second else 0L
            val charset = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use {
                    CharsetDetector.detect(it, size)
                }
            }.getOrNull() ?: java.nio.charset.Charset.forName(CharsetDetector.GB18030)

            val existing = NovelRepository.findByUri(ctx, uri.toString())
            val record = NovelRecord(
                id = existing?.id ?: UUID.randomUUID().toString(),
                uri = uri.toString(),
                name = info.first,
                size = size,
                lastModified = info.third,
                encoding = charset.name(),
                addedAt = System.currentTimeMillis()
            )
            NovelRepository.addNovel(ctx, record)
            NovelRepository.setLast(ctx, record.id)
            refresh()
            Toast.makeText(ctx, "已添加：${record.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "添加失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private class NovelAdapter(
        private val items: List<NovelRecord>,
        private val onClick: (NovelRecord) -> Unit
    ) : RecyclerView.Adapter<NovelAdapter.VH>() {

        class VH(val binding: ItemNovelBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemNovelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.tvName.text = item.name
            val sizeStr = if (item.size > 0) formatSize(item.size) else "未知大小"
            holder.binding.tvInfo.text = "$sizeStr · 编码 ${item.encoding} · ${TimeUtil.friendly(item.addedAt)}"
            holder.binding.root.setOnClickListener { onClick(item) }
            holder.binding.root.setOnLongClickListener {
                showItemMenu(holder.binding.root, item)
                true
            }
        }

        override fun getItemCount(): Int = items.size

        private fun formatSize(size: Long): String {
            val mb = size / 1024.0 / 1024.0
            return if (mb >= 1) "%.1f MB".format(mb) else "${size / 1024} KB"
        }

        private fun showItemMenu(view: View, record: NovelRecord) {
            val ctx = view.context
            AlertDialog.Builder(ctx)
                .setTitle(record.name)
                .setItems(arrayOf("播放", "删除记录")) { _, which ->
                    when (which) {
                        0 -> onClick(record)
                        1 -> {
                            NovelRepository.removeNovel(ctx, record.id)
                            Toast.makeText(ctx, "已删除记录（不影响手机上的原文件）", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
        }
    }
}