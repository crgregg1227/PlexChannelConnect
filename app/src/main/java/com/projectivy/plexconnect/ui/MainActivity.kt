package com.projectivy.plexconnect.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.projectivy.plexconnect.channels.ChannelSyncManager
import com.projectivy.plexconnect.data.AppPreferences
import com.projectivy.plexconnect.data.BuiltInChannelType
import com.projectivy.plexconnect.data.PlaybackEngineMode
import com.projectivy.plexconnect.data.PlexCollection
import com.projectivy.plexconnect.network.PlexApiClient
import com.projectivy.plexconnect.network.PlexDiscoveryAndAuth
import com.projectivy.plexconnect.service.PlexRealtimeSyncService
import com.projectivy.plexconnect.service.SyncWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var discoveryAndAuth: PlexDiscoveryAndAuth
    private lateinit var apiClient: PlexApiClient
    private lateinit var syncManager: ChannelSyncManager

    private lateinit var statusBanner: TextView
    private lateinit var pinBox: TextView
    private lateinit var playbackModeContainer: LinearLayout
    private lateinit var channelsContainer: LinearLayout
    private lateinit var collectionsContainer: LinearLayout

    private var pinPollingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = AppPreferences(this)
        discoveryAndAuth = PlexDiscoveryAndAuth(prefs.clientIdentifier)
        apiClient = PlexApiClient(prefs)
        syncManager = ChannelSyncManager(this)

        buildTvDashboardUi()

        if (prefs.isConfigured()) {
            updateStatusText()
            SyncWorker.schedulePeriodicSync(this)
            PlexRealtimeSyncService.startService(this, forceFullSync = true)
            loadCollectionsList()
        } else {
            attemptZeroConfigDiscoveryThenPin()
        }
    }

    private fun attemptZeroConfigDiscoveryThenPin() {
        statusBanner.text = "Scanning local network for Plex Media Server (Zero-Config GDM)..."
        lifecycleScope.launch {
            val localServers = discoveryAndAuth.discoverLocalServerViaGdm()
            val authFreeServer = localServers.firstOrNull { it.isLocalAuthFree }
            if (authFreeServer != null) {
                prefs.serverName = authFreeServer.name
                prefs.serverUrl = authFreeServer.baseUrl
                prefs.machineIdentifier = authFreeServer.machineIdentifier
                prefs.isLocalNoAuth = true
                updateStatusText("Connected automatically via Local Network (${authFreeServer.name})!")
                SyncWorker.schedulePeriodicSync(this@MainActivity)
                PlexRealtimeSyncService.startService(this@MainActivity, forceFullSync = true)
                loadCollectionsList()
            } else {
                val hint = if (localServers.isNotEmpty()) {
                    "Found ${localServers.first().name} at ${localServers.first().baseUrl} (requires Plex token)."
                } else {
                    "Local server requires token authentication."
                }
                statusBanner.text = "$hint Starting plex.tv/link sign-in..."
                startPlexLinkPinFlow()
            }
        }
    }

    private fun startPlexLinkPinFlow() {
        pinPollingJob?.cancel()
        pinPollingJob = lifecycleScope.launch {
            val pinResp = discoveryAndAuth.requestPlexPin()
            if (pinResp == null) {
                pinBox.text = "Could not reach plex.tv/link. Check Wi-Fi or use Manual Server Setup."
                return@launch
            }

            pinBox.text = "Go to  plex.tv/link  and enter code:   ${pinResp.code}"
            while (isActive) {
                delay(2500L)
                val token = discoveryAndAuth.pollPlexPin(pinResp.id)
                if (!token.isNullOrBlank()) {
                    pinBox.text = "Signed in! Discovering your Plex Media Server..."
                    val servers = discoveryAndAuth.fetchServersForToken(token)
                    val chosen = servers.firstOrNull()
                    if (chosen != null) {
                        prefs.serverName = chosen.name
                        prefs.serverUrl = chosen.baseUrl
                        prefs.machineIdentifier = chosen.machineIdentifier
                        prefs.plexToken = chosen.token
                        prefs.isLocalNoAuth = false

                        pinBox.text = "✓ Linked to ${chosen.name} (${chosen.baseUrl})"
                        updateStatusText("Connected to ${chosen.name} — syncing channels to Projectivy...")
                        SyncWorker.schedulePeriodicSync(this@MainActivity)
                        PlexRealtimeSyncService.startService(this@MainActivity, forceFullSync = true)
                        loadCollectionsList()
                    } else {
                        prefs.plexToken = token
                        pinBox.text = "Token saved, but no server auto-detected. Click 'Manual Server URL / Token' below."
                    }
                    break
                }
            }
        }
    }

    private fun buildTvDashboardUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(72, 48, 72, 64)
        }

        val header = TextView(this).apply {
            text = "Plex Channel Connect v1.1 (Single-Click Play)"
            setTextColor(Color.parseColor("#E5A00D"))
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(header)

        val subHeader = TextView(this).apply {
            text = "Single-click instant playback from Projectivy • 16:9 in-progress stills & 2:3 unplayed posters"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 15f
            setPadding(0, 6, 0, 20)
        }
        container.addView(subHeader)

        statusBanner = TextView(this).apply {
            text = "Checking Plex connection..."
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#161B22"))
                cornerRadius = 12f
                setStroke(2, Color.parseColor("#30363D"))
            }
        }
        container.addView(statusBanner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })

        pinBox = TextView(this).apply {
            text = if (prefs.isConfigured()) {
                "✓ Connected to ${prefs.serverName.ifBlank { prefs.serverUrl }}"
            } else {
                "Initializing connection..."
            }
            setTextColor(Color.parseColor("#E5A00D"))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(24, 16, 24, 16)
        }
        container.addView(pinBox)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 28)
        }

        val syncNowBtn = createActionButton("⟳  Sync All Channels Now") {
            statusBanner.text = "Syncing all enabled channels & Watch Next row with Projectivy..."
            lifecycleScope.launch {
                syncManager.syncAllChannels()
                updateStatusText("All enabled channels synced to Projectivy Launcher!")
                loadCollectionsList()
                Toast.makeText(this@MainActivity, "Projectivy Channels Updated!", Toast.LENGTH_SHORT).show()
            }
        }
        actionRow.addView(syncNowBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 16 })

        val relinkPinBtn = createActionButton("🔑  Sign in with plex.tv/link") {
            startPlexLinkPinFlow()
        }
        actionRow.addView(relinkPinBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 16 })

        val manualBtn = createActionButton("⚙  Manual Server URL / Token") {
            showManualServerDialog()
        }
        actionRow.addView(manualBtn)

        container.addView(actionRow)

        val playbackTitle = TextView(this).apply {
            text = "1. Single-Click Playback Engine (Click to switch)"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 4, 0, 12)
        }
        container.addView(playbackTitle)

        playbackModeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(playbackModeContainer)
        populatePlaybackModeSelector()

        val chanTitle = TextView(this).apply {
            text = "2. Home Screen Channels & System Watch Next Row"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 24, 0, 12)
        }
        container.addView(chanTitle)

        channelsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(channelsContainer)
        populateChannelToggles()

        val collTitle = TextView(this).apply {
            text = "3. Plex Collections Channels (Toggle any collection to add its own row)"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 28, 0, 12)
        }
        container.addView(collTitle)

        collectionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(collectionsContainer)

        scroll.addView(container)
        setContentView(scroll)
        syncNowBtn.requestFocus()
    }

    private fun populatePlaybackModeSelector() {
        playbackModeContainer.removeAllViews()
        val currentMode = prefs.playbackEngineMode
        for (mode in PlaybackEngineMode.values()) {
            val isSelected = (mode == currentMode)
            val row = createToggleRow(
                title = mode.title,
                subtitle = mode.subtitle,
                isEnabled = isSelected,
                enabledLabel = "●  ACTIVE",
                disabledLabel = "○  SELECT"
            ) {
                prefs.playbackEngineMode = mode
                populatePlaybackModeSelector()
                Toast.makeText(
                    this,
                    "Playback mode set to: ${mode.title}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            playbackModeContainer.addView(row)
        }
    }

    private fun populateChannelToggles() {
        channelsContainer.removeAllViews()

        val watchNextToggle = createToggleRow(
            title = "Android TV System 'Watch Next / Play Next' Row",
            subtitle = "Syncs Continue Watching directly into Projectivy's system Play Next channel",
            isEnabled = prefs.syncWatchNextSystemRow
        ) {
            prefs.syncWatchNextSystemRow = !prefs.syncWatchNextSystemRow
            populateChannelToggles()
            lifecycleScope.launch { syncManager.syncPlaybackChannelsNow() }
        }
        channelsContainer.addView(watchNextToggle)

        for (channelType in BuiltInChannelType.values()) {
            val enabled = prefs.isChannelEnabled(channelType)
            val row = createToggleRow(
                title = channelType.displayName,
                subtitle = channelType.description,
                isEnabled = enabled
            ) {
                val nextState = !prefs.isChannelEnabled(channelType)
                prefs.setChannelEnabled(channelType, nextState)
                populateChannelToggles()
                lifecycleScope.launch {
                    if (nextState) {
                        syncManager.syncAllChannels()
                    } else {
                        syncManager.removeChannelIfPresent(channelType.idKey)
                    }
                }
            }
            channelsContainer.addView(row)
        }
    }

    private fun loadCollectionsList() {
        if (!prefs.isConfigured()) return
        lifecycleScope.launch {
            val collections = apiClient.fetchAllCollections()
            renderCollections(collections)
        }
    }

    private fun renderCollections(collections: List<PlexCollection>) {
        collectionsContainer.removeAllViews()
        if (collections.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No Plex Collections found on server (or server not yet synced)."
                setTextColor(Color.parseColor("#8B949E"))
                textSize = 15f
                setPadding(8, 8, 8, 8)
            }
            collectionsContainer.addView(emptyTv)
            return
        }

        val enabledSet = prefs.getEnabledCollectionKeys()
        for (coll in collections) {
            val isOn = enabledSet.contains(coll.ratingKey)
            val row = createToggleRow(
                title = "Collection: ${coll.title} (${coll.childCount} items)",
                subtitle = "Library: ${coll.sectionTitle}",
                isEnabled = isOn
            ) {
                val nowEnabled = prefs.toggleCollectionEnabled(coll.ratingKey)
                renderCollections(collections)
                lifecycleScope.launch {
                    if (nowEnabled) {
                        syncManager.syncAllChannels()
                    } else {
                        syncManager.removeChannelIfPresent("collection_${coll.ratingKey}")
                    }
                }
            }
            collectionsContainer.addView(row)
        }
    }

    private fun createToggleRow(
        title: String,
        subtitle: String,
        isEnabled: Boolean,
        enabledLabel: String = "☑  ENABLED",
        disabledLabel: String = "☐  DISABLED",
        onToggle: () -> Unit
    ): Button {
        val statusIcon = if (isEnabled) enabledLabel else disabledLabel
        return Button(this).apply {
            text = "$statusIcon   •   $title\n     $subtitle"
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textSize = 15f
            setPadding(28, 18, 28, 18)
            isFocusable = true

            fun styleRow(focused: Boolean) {
                val bg = when {
                    focused -> Color.parseColor("#2A313C")
                    isEnabled -> Color.parseColor("#161F2C")
                    else -> Color.parseColor("#161B22")
                }
                val border = when {
                    focused -> Color.parseColor("#E5A00D")
                    isEnabled -> Color.parseColor("#3B523E")
                    else -> Color.parseColor("#30363D")
                }
                setTextColor(if (isEnabled || focused) Color.WHITE else Color.parseColor("#8B949E"))
                background = GradientDrawable().apply {
                    setColor(bg)
                    cornerRadius = 12f
                    setStroke(if (focused) 3 else 2, border)
                }
            }

            styleRow(false)
            setOnFocusChangeListener { _, hasFocus -> styleRow(hasFocus) }
            setOnClickListener { onToggle() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 }
        }
    }

    private fun createActionButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(28, 16, 28, 16)
            isFocusable = true

            fun styleBtn(focused: Boolean) {
                setTextColor(if (focused) Color.parseColor("#0D1117") else Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(if (focused) Color.parseColor("#E5A00D") else Color.parseColor("#21262D"))
                    cornerRadius = 12f
                    setStroke(2, Color.parseColor("#E5A00D"))
                }
            }

            styleBtn(false)
            setOnFocusChangeListener { _, hasFocus -> styleBtn(hasFocus) }
            setOnClickListener { onClick() }
        }
    }

    private fun showManualServerDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val urlInput = EditText(this).apply {
            hint = "Server URL (e.g. http://192.168.1.50:32400)"
            setText(prefs.serverUrl)
        }
        val tokenInput = EditText(this).apply {
            hint = "X-Plex-Token (optional if local subnet auth disabled)"
            setText(prefs.plexToken)
        }
        layout.addView(urlInput)
        layout.addView(tokenInput)

        AlertDialog.Builder(this)
            .setTitle("Manual Plex Server Connection")
            .setView(layout)
            .setPositiveButton("Save & Sync") { _, _ ->
                val url = urlInput.text.toString().trim().trimEnd('/')
                val tok = tokenInput.text.toString().trim()
                if (url.isNotBlank()) {
                    prefs.serverUrl = url
                    prefs.plexToken = tok
                    prefs.isLocalNoAuth = tok.isBlank()
                    lifecycleScope.launch {
                        val machineId = discoveryAndAuth.fetchMachineIdentifier(url) ?: "local-server"
                        prefs.machineIdentifier = machineId
                        syncManager.syncAllChannels()
                        PlexRealtimeSyncService.startService(this@MainActivity, forceFullSync = true)
                        updateStatusText("Connected to $url and synced channels!")
                        loadCollectionsList()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateStatusText(customPrefix: String? = null) {
        val lastSync = if (prefs.lastSyncTimestamp > 0L) {
            val fmt = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
            "Last synced: ${fmt.format(Date(prefs.lastSyncTimestamp))}"
        } else {
            "Not synced yet"
        }
        val prefix = customPrefix ?: "Server: ${prefs.serverName.ifBlank { prefs.serverUrl }} (${prefs.serverUrl})"
        statusBanner.text = "$prefix  •  $lastSync  •  1-Click Play: ACTIVE"
    }

    override fun onDestroy() {
        pinPollingJob?.cancel()
        super.onDestroy()
    }
}
