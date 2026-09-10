package com.noveltts.player.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.noveltts.player.R
import com.noveltts.player.player.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 前台播放服务：承载 MediaSession 与通知栏控制，保证锁屏/后台持续播放。
 */
class MediaPlaybackService : Service() {

    companion object {
        const val ACTION_PLAY_PAUSE = "com.noveltts.player.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.noveltts.player.ACTION_NEXT"
        const val ACTION_PREV = "com.noveltts.player.ACTION_PREV"
        const val ACTION_STOP = "com.noveltts.player.ACTION_STOP"
        const val CHANNEL_ID = "novel_playback"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MediaPlaybackService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    private var session: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "NovelTTSPlayer").also { s ->
            s.setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = PlaybackManager.play()
                override fun onPause() = PlaybackManager.pause()
                override fun onStop() = PlaybackManager.stop()
                override fun onSkipToNext() = PlaybackManager.next()
                override fun onSkipToPrevious() = PlaybackManager.prev()
            })
            s.isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> PlaybackManager.togglePlayPause()
            ACTION_NEXT -> PlaybackManager.next()
            ACTION_PREV -> PlaybackManager.prev()
            ACTION_STOP -> {
                PlaybackManager.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 裸启动仅刷新通知，绝不自动播放：
                // 否则暂停后重新 start() 会被误当成"恢复播放"而重读本段。
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification().build())
        observeAndUpdate()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 未在播放时移除任务则停止；播放中保留前台服务继续播放
        if (PlaybackManager.state.value != PlaybackManager.PlayState.PLAYING) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        observeJob?.cancel()
        session?.release()
        PlaybackManager.stop()
        super.onDestroy()
    }

    private fun observeAndUpdate() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(
                PlaybackManager.state,
                PlaybackManager.currentNovel,
                PlaybackManager.currentChapter,
                PlaybackManager.currentText,
                PlaybackManager.percent
            ) { a, b, c, d, e -> arrayOf(a, b, c, d, e) }.collect {
                updateNotification()
                updateMediaSession()
            }
        }
    }

    private fun updateNotification() {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification().build())
    }

    private fun updateMediaSession() {
        val s = session ?: return
        val playing = PlaybackManager.state.value == PlaybackManager.PlayState.PLAYING
        val paused = PlaybackManager.state.value == PlaybackManager.PlayState.PAUSED
        val pState = when {
            playing -> PlaybackStateCompat.STATE_PLAYING
            paused -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        val actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_STOP
        s.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(pState, 0L, 1f)
                .build()
        )
        val novel = PlaybackManager.currentNovel.value
        val chapter = PlaybackManager.currentChapter.value
        s.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, novel?.name ?: "有声小说")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, chapter?.title ?: "")
                .build()
        )
    }

    private fun buildNotification(): NotificationCompat.Builder {
        val playing = PlaybackManager.state.value == PlaybackManager.PlayState.PLAYING
        val novel = PlaybackManager.currentNovel.value
        val chapter = PlaybackManager.currentChapter.value
        val text = PlaybackManager.currentText.value

        val playPauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MediaPlaybackService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getService(
            this, 2,
            Intent(this, MediaPlaybackService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevIntent = PendingIntent.getService(
            this, 3,
            Intent(this, MediaPlaybackService::class.java).setAction(ACTION_PREV),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 4,
            Intent(this, MediaPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(novel?.name ?: "有声小说")
            .setContentText(chapter?.title?.takeIf { it.isNotBlank() } ?: text.take(60))
            .setStyle(MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1, 2).setShowCancelButton(true))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDeleteIntent(stopIntent)
            .addAction(R.drawable.ic_prev, "上一段", prevIntent)
            .addAction(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "暂停" else "播放", playPauseIntent)
            .addAction(R.drawable.ic_next, "下一段", nextIntent)
            .also { b ->
                if (Build.VERSION.SDK_INT >= 31) {
                    b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "小说播放", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "播放进度与控制"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}