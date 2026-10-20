package com.projectivy.plexconnect.ui

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.projectivy.plexconnect.channels.ChannelSyncManager
import com.projectivy.plexconnect.data.AppPreferences
import com.projectivy.plexconnect.network.PlexApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class DirectVideoPlayerActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var apiClient: PlexApiClient
    private var exoPlayer: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var statusOverlay: TextView

    private var ratingKey: String = ""
    private var mediaTitle: String = ""
    private var durationMs: Long = 0L
    private var initialOffsetMs: Long = 0L
    private var hlsFallbackUrl: String = ""
    private var triedHlsFallback = false

    private var timelineJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = AppPreferences(this)
        apiClient = PlexApiClient(prefs)

        ratingKey = intent.getStringExtra("ratingKey") ?: run {
            finish()
            return
        }
        mediaTitle = intent.getStringExtra("title") ?: "Plex Video"
        durationMs = intent.getLongExtra("duration", 0L)
        initialOffsetMs = intent.getLongExtra("viewOffset", 0L)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = true
            controllerAutoShow = true
            controllerShowTimeoutMs = 4000
            isFocusable = true
        }
        root.addView(playerView)

        loadingSpinner = ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E5A00D"))
        }
        root.addView(
            loadingSpinner,
            FrameLayout.LayoutParams(100, 100, Gravity.CENTER)
        )

        statusOverlay = TextView(this).apply {
            text = if (initialOffsetMs > 0L) {
                val mins = (initialOffsetMs / 60000L).coerceAtLeast(1L)
                "Loading $mediaTitle (Resuming at ${mins}m)..."
            } else {
                "Loading $mediaTitle..."
            }
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(48, 36, 48, 36)
            setBackgroundColor(Color.parseColor("#99000000"))
        }
        root.addView(
            statusOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = 40
                leftMargin = 48
            }
        )

        setContentView(root)
        initializeAndStartPlayer()
    }

    private fun initializeAndStartPlayer() {
        val player = ExoPlayer.Builder(this).build()
        exoPlayer = player
        playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        loadingSpinner.visibility = View.VISIBLE
                    }
                    Player.STATE_READY -> {
                        loadingSpinner.visibility = View.GONE
                        statusOverlay.visibility = View.GONE
                        if (player.duration != C.TIME_UNSET && player.duration > 0) {
                            durationMs = player.duration
                        }
                    }
                    Player.STATE_ENDED -> {
                        reportCurrentTimeline("stopped", durationMs)
                        finish()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                reportCurrentTimeline(if (isPlaying) "playing" else "paused", pos)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!triedHlsFallback && hlsFallbackUrl.isNotBlank()) {
                    triedHlsFallback = true
                    statusOverlay.visibility = View.VISIBLE
                    statusOverlay.text = "Switching to Plex Universal Stream..."
                    val currentPos = player.currentPosition.coerceAtLeast(initialOffsetMs)
                    player.setMediaItem(MediaItem.fromUri(Uri.parse(hlsFallbackUrl)))
                    player.prepare()
                    if (currentPos > 0L) {
                        player.seekTo(currentPos)
                    }
                    player.playWhenReady = true
                } else {
                    Toast.makeText(
                        this@DirectVideoPlayerActivity,
                        "Playback error: ${error.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        lifecycleScope.launch {
            val resolved = apiClient.resolvePlaybackUrls(ratingKey, initialOffsetMs)
            if (resolved == null) {
                Toast.makeText(
                    this@DirectVideoPlayerActivity,
                    "Could not load stream URL from Plex Server",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }

            mediaTitle = resolved.title
            if (resolved.durationMs > 0L) durationMs = resolved.durationMs
            initialOffsetMs = resolved.viewOffsetMs
            hlsFallbackUrl = resolved.hlsTranscodeUrl

            player.setMediaItem(MediaItem.fromUri(Uri.parse(resolved.directPlayUrl)))
            player.prepare()
            if (initialOffsetMs > 0L) {
                player.seekTo(initialOffsetMs)
            }
            player.playWhenReady = true
            startPeriodicTimelineReporting()
        }
    }

    private fun startPeriodicTimelineReporting() {
        timelineJob?.cancel()
        timelineJob = lifecycleScope.launch {
            while (isActive) {
                delay(5000L)
                val p = exoPlayer ?: continue
                if (p.isPlaying) {
                    val pos = p.currentPosition.coerceAtLeast(0L)
                    val dur = if (p.duration > 0) p.duration else durationMs
                    apiClient.reportTimelineToPlex(ratingKey, "playing", pos, dur)
                }
            }
        }
    }

    private fun reportCurrentTimeline(state: String, positionMs: Long) {
        val dur = durationMs.coerceAtLeast(1L)
        CoroutineScope(Dispatchers.IO).launch {
            apiClient.reportTimelineToPlex(ratingKey, state, positionMs, dur)
            if (state == "stopped" || state == "paused") {
                ChannelSyncManager(applicationContext).syncPlaybackChannelsNow()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val p = exoPlayer
            if (p != null && !playerView.isControllerFullyVisible) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (p.isPlaying) p.pause() else p.play()
                        playerView.showController()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        p.seekTo((p.currentPosition + 15_000L).coerceAtMost(p.duration.coerceAtLeast(0L)))
                        playerView.showController()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        p.seekTo((p.currentPosition - 15_000L).coerceAtLeast(0L))
                        playerView.showController()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        timelineJob?.cancel()
        exoPlayer?.let { player ->
            val finalPos = player.currentPosition.coerceAtLeast(0L)
            player.playWhenReady = false
            reportCurrentTimeline("stopped", finalPos)
            player.release()
        }
        exoPlayer = null
        super.onStop()
    }
}
