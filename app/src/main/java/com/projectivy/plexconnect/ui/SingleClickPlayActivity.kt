package com.projectivy.plexconnect.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.projectivy.plexconnect.playback.PlexLauncher

class SingleClickPlayActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ratingKey = intent.getStringExtra("ratingKey")
            ?: intent.data?.lastPathSegment
            ?: run {
                finish()
                return
            }

        val title = intent.getStringExtra("title") ?: "Plex Media"
        val subtitle = intent.getStringExtra("subtitle") ?: ""
        val duration = intent.getLongExtra("duration", 0L)
        val viewOffset = intent.getLongExtra("viewOffset", 0L)
        val partKeyPath = intent.getStringExtra("partKeyPath") ?: ""

        PlexLauncher.playMediaSingleClick(
            context = this,
            ratingKey = ratingKey,
            title = title,
            subtitle = subtitle,
            durationMs = duration,
            viewOffsetMs = viewOffset,
            partKeyPath = partKeyPath
        )

        finish()
    }
}
