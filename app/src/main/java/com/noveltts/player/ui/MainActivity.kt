package com.noveltts.player.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.noveltts.player.R
import com.noveltts.player.data.NovelRecord
import com.noveltts.player.databinding.ActivityMainBinding
import com.noveltts.player.player.PlaybackManager
import com.noveltts.player.resume.ProgressStore

class MainActivity : AppCompatActivity(), NovelListFragment.Listener {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        requestNotificationPermission()
        showNovelList()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ---------- 导航 ----------

    fun showNovelList() {
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        if (supportFragmentManager.findFragmentByTag("list") == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, NovelListFragment(), "list")
                .commit()
        }
    }

    fun showPlayer() {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (supportFragmentManager.findFragmentByTag("player") == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, PlayerFragment(), "player")
                .addToBackStack("list")
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // ---------- NovelListFragment.Listener ----------

    override fun onOpenNovel(record: NovelRecord) {
        openNovelWithResumeCheck(record)
    }

    override fun onShowSettings() {
        SettingsDialogFragment().show(supportFragmentManager, "settings")
    }

    /**
     * 打开小说前检查最近有效阅读/播放位置并提示续读（含文件变化提示）。
     * 统一使用 getResumePosition()（ProgressStore.resolveResume）：TTS 与阅读位置谁新用谁。
     */
    private fun openNovelWithResumeCheck(record: NovelRecord) {
        val progress = ProgressStore.resolveResume(this, record.id)
        if (progress == null || progress.lineByteOffset <= 0) {
            PlaybackManager.loadNovel(record, 0, 0)
            showPlayer()
            return
        }
        val changed = com.noveltts.player.util.NovelFileInfo.fileChanged(this, record)
        val msg = buildString {
            append("发现上次阅读进度：\n\n")
            append("《").append(record.name).append("》\n")
            append(progress.chapterTitle.ifEmpty { "未知章节" }).append("\n")
            append("上次阅读到 ").append("%.1f".format(progress.percent * 100)).append("%\n")
            append("上次时间：").append(com.noveltts.player.util.TimeUtil.friendly(progress.updatedAt))
            if (changed) {
                append("\n\n⚠ 该文件的大小或修改时间与上次不同，可能已被替换或修改。")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("继续阅读？")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("继续阅读") { _, _ ->
                PlaybackManager.loadNovel(record, progress.lineByteOffset, progress.subIndex)
                showPlayer()
            }
            .setNeutralButton("从头开始") { _, _ ->
                PlaybackManager.loadNovel(record, 0, 0)
                showPlayer()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}