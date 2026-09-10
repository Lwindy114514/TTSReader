package com.noveltts.player

import android.app.Application
import com.noveltts.player.player.PlaybackManager

class NovelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PlaybackManager.init(this)
    }
}