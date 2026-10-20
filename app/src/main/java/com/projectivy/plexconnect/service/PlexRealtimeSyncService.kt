package com.projectivy.plexconnect.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.projectivy.plexconnect.R
import com.projectivy.plexconnect.channels.ChannelSyncManager
import com.projectivy.plexconnect.data.AppPreferences
import com.projectivy.plexconnect.network.PlexApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class PlexRealtimeSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: AppPreferences
    private lateinit var syncManager: ChannelSyncManager
    private lateinit var apiClient: PlexApiClient

    private var webSocket: WebSocket? = null
    private var wsReconnectJob: Job? = null
    private var rapidPollJob: Job? = null
    private var debounceSyncJob: Job? = null

    private var audioManager: AudioManager? = null
    private var wasAudioPlaying = false

    private val audioCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val isPlayingNow = !configs.isNullOrEmpty()
            if (wasAudioPlaying && !isPlayingNow) {
                triggerDebouncedSync(delayMs = 400L)
                triggerDebouncedSync(delayMs = 2500L)
            }
            wasAudioPlaying = isPlayingNow
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        syncManager = ChannelSyncManager(this)
        apiClient = PlexApiClient(prefs)

        startForegroundNotification()
        registerAudioStopDetector()
        startWebSocketLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FORCE_FULL_SYNC -> {
                serviceScope.launch {
                    syncManager.syncAllChannels()
                }
            }
            ACTION_PLEX_SESSION_STARTED -> {
                startRapidPostPlaybackWatcher()
            }
            else -> {
                serviceScope.launch {
                    syncManager.syncAllChannels()
                }
            }
        }
        return START_STICKY
    }

    private fun startWebSocketLoop() {
        wsReconnectJob?.cancel()
        wsReconnectJob = serviceScope.launch {
            while (isActive) {
                if (prefs.isConfigured()) {
                    connectPlexWebSocket()
                }
                delay(30_000L)
            }
        }
    }

    private fun connectPlexWebSocket() {
        if (webSocket != null) return
        val base = prefs.serverUrl.trimEnd('/')
        if (base.isBlank()) return

        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "ws://$base"
        }

        val tokenQuery = if (prefs.plexToken.isNotBlank()) "?X-Plex-Token=${prefs.plexToken}" else ""
        val wsUrl = "$wsBase/:/websockets/notifications$tokenQuery"

        val request = Request.Builder()
            .url(wsUrl)
            .header("X-Plex-Client-Identifier", prefs.clientIdentifier)
            .header("X-Plex-Product", "Plex Channel Connect")
            .build()

        webSocket = apiClient.httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handlePlexWebSocketMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@PlexRealtimeSyncService.webSocket = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this@PlexRealtimeSyncService.webSocket = null
            }
        })
    }

    private fun handlePlexWebSocketMessage(rawJson: String) {
        try {
            val root = JSONObject(rawJson)
            val container = root.optJSONObject("NotificationContainer") ?: return
            val type = container.optString("type", "")

            when (type) {
                "playing" -> {
                    val sessions = container.optJSONArray("PlaySessionStateNotification")
                    if (sessions != null && sessions.length() > 0) {
                        val state = sessions.getJSONObject(0).optString("state", "")
                        if (state == "stopped" || state == "paused") {
                            triggerDebouncedSync(delayMs = 300L)
                        } else {
                            triggerDebouncedSync(delayMs = 4000L)
                        }
                    }
                }
                "timeline" -> {
                    triggerDebouncedSync(delayMs = 1500L)
                }
                "activity" -> {
                    triggerDebouncedSync(delayMs = 3000L)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun triggerDebouncedSync(delayMs: Long) {
        debounceSyncJob?.cancel()
        debounceSyncJob = serviceScope.launch {
            delay(delayMs)
            syncManager.syncPlaybackChannelsNow()
        }
    }

    private fun startRapidPostPlaybackWatcher() {
        rapidPollJob?.cancel()
        rapidPollJob = serviceScope.launch {
            repeat(36) {
                delay(10_000L)
                syncManager.syncPlaybackChannelsNow()
            }
        }
    }

    private fun registerAudioStopDetector() {
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.registerAudioPlaybackCallback(
                audioCallback,
                Handler(Looper.getMainLooper())
            )
        } catch (_: Exception) {
        }
    }

    private fun startForegroundNotification() {
        val channelId = "plex_channel_sync"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chan = NotificationChannel(
                channelId,
                getString(R.string.channel_notification_title),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.channel_notification_title))
            .setContentText(getString(R.string.channel_notification_desc))
            .setSmallIcon(R.drawable.app_icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        try {
            audioManager?.unregisterAudioPlaybackCallback(audioCallback)
        } catch (_: Exception) {
        }
        webSocket?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_FORCE_FULL_SYNC = "com.projectivy.plexconnect.FORCE_FULL_SYNC"
        const val ACTION_PLEX_SESSION_STARTED = "com.projectivy.plexconnect.PLEX_SESSION_STARTED"

        fun startService(context: Context, forceFullSync: Boolean = false) {
            val intent = Intent(context, PlexRealtimeSyncService::class.java).apply {
                if (forceFullSync) action = ACTION_FORCE_FULL_SYNC
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun notifyPlexSessionStarted(context: Context) {
            val intent = Intent(context, PlexRealtimeSyncService::class.java).apply {
                action = ACTION_PLEX_SESSION_STARTED
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
