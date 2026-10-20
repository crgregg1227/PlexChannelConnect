package com.projectivy.plexconnect.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.projectivy.plexconnect.data.AppPreferences
import com.projectivy.plexconnect.data.PlaybackEngineMode
import com.projectivy.plexconnect.data.PlexClientPlayer
import com.projectivy.plexconnect.network.PlexApiClient
import com.projectivy.plexconnect.network.PlexDiscoveryAndAuth
import com.projectivy.plexconnect.service.PlexRealtimeSyncService
import com.projectivy.plexconnect.ui.DirectVideoPlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

object PlexLauncher {

    private const val PLEX_PACKAGE = "com.plexapp.android"

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun playMediaSingleClick(
        context: Context,
        ratingKey: String,
        title: String,
        subtitle: String,
        durationMs: Long,
        viewOffsetMs: Long,
        partKeyPath: String
    ) {
        val prefs = AppPreferences(context)
        PlexRealtimeSyncService.notifyPlexSessionStarted(context)

        if (prefs.playbackEngineMode == PlaybackEngineMode.BUILT_IN_EXOPLAYER) {
            val playerIntent = Intent(context, DirectVideoPlayerActivity::class.java).apply {
                putExtra("ratingKey", ratingKey)
                putExtra("title", title)
                putExtra("subtitle", subtitle)
                putExtra("duration", durationMs)
                putExtra("viewOffset", viewOffsetMs)
                putExtra("partKeyPath", partKeyPath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(playerIntent)
            return
        }

        launchOfficialPlexWithCompanionCast(
            context = context,
            prefs = prefs,
            ratingKey = ratingKey,
            viewOffsetMs = viewOffsetMs
        )
    }

    private fun launchOfficialPlexWithCompanionCast(
        context: Context,
        prefs: AppPreferences,
        ratingKey: String,
        viewOffsetMs: Long
    ) {
        val machineId = prefs.machineIdentifier
        var openedPlex = false

        // Try server:// scheme deep-link first, then standard package launch
        try {
            val deepUri = Uri.parse("plex://server://$machineId/com.plexapp.plugins.library/library/metadata/$ratingKey")
            val deepIntent = Intent(Intent.ACTION_VIEW, deepUri).apply {
                setPackage(PLEX_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(deepIntent)
            openedPlex = true
        } catch (_: Exception) {
        }

        if (!openedPlex) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(PLEX_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                openedPlex = true
            }
        }

        if (!openedPlex) {
            Toast.makeText(
                context,
                "Official Plex app not found — opening Built-In Player",
                Toast.LENGTH_SHORT
            ).show()
            val fallback = Intent(context, DirectVideoPlayerActivity::class.java).apply {
                putExtra("ratingKey", ratingKey)
                putExtra("viewOffset", viewOffsetMs)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
            return
        }

        Toast.makeText(context, "Starting playback in Plex...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            val api = PlexApiClient(prefs)
            val discovery = PlexDiscoveryAndAuth(prefs.clientIdentifier)
            val playQueueId = createServerPlayQueue(prefs, ratingKey)

            for (attempt in 1..8) {
                delay(if (attempt == 1) 1200L else 1500L)

                val candidates = mutableListOf<PlexClientPlayer>()
                if (prefs.targetPlexClientId.isNotBlank()) {
                    candidates.add(
                        PlexClientPlayer(
                            name = "Configured TV Player",
                            machineIdentifier = prefs.targetPlexClientId,
                            address = "127.0.0.1",
                            port = 32500
                        )
                    )
                }
                candidates.addAll(discovery.discoverLocalPlayersViaGdm())
                candidates.addAll(api.fetchAvailablePlexClients())

                val uniquePlayers = candidates.distinctBy { it.machineIdentifier }
                var succeeded = false
                for (player in uniquePlayers) {
                    if (sendCompanionPlayMediaCommand(prefs, player, ratingKey, playQueueId, viewOffsetMs)) {
                        prefs.targetPlexClientId = player.machineIdentifier
                        succeeded = true
                        break
                    }
                }
                if (succeeded) break
            }
        }
    }

    private fun createServerPlayQueue(prefs: AppPreferences, ratingKey: String): Long {
        return try {
            val baseUrl = prefs.serverUrl.trimEnd('/')
            val machineId = prefs.machineIdentifier
            if (baseUrl.isBlank() || machineId.isBlank()) return 0L

            val itemUri = Uri.encode("server://$machineId/com.plexapp.plugins.library/library/metadata/$ratingKey")
            val pqUrl = "$baseUrl/playQueues?type=video&uri=$itemUri&shuffle=0&repeat=0&continuous=1&own=1"
            val reqBuilder = Request.Builder()
                .url(pqUrl)
                .post("".toRequestBody())
                .header("Accept", "application/json")
                .header("X-Plex-Client-Identifier", prefs.clientIdentifier)
                .header("X-Plex-Product", "Plex Channel Connect")

            if (prefs.plexToken.isNotBlank()) {
                reqBuilder.header("X-Plex-Token", prefs.plexToken)
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return 0L
                val json = JSONObject(resp.body?.string() ?: "{}")
                json.optJSONObject("MediaContainer")?.optLong("playQueueID", 0L) ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun sendCompanionPlayMediaCommand(
        prefs: AppPreferences,
        player: PlexClientPlayer,
        ratingKey: String,
        playQueueId: Long,
        viewOffsetMs: Long
    ): Boolean {
        val baseUrl = prefs.serverUrl.trimEnd('/')
        val serverMachineId = prefs.machineIdentifier
        if (baseUrl.isBlank() || serverMachineId.isBlank()) return false

        val parsedServer = try { URI(baseUrl) } catch (_: Exception) { return false }
        val protocol = parsedServer.scheme ?: "http"
        val serverHost = parsedServer.host ?: return false
        val serverPort = if (parsedServer.port > 0) parsedServer.port else 32400

        val containerKey = if (playQueueId > 0L) {
            "/playQueues/$playQueueId"
        } else {
            "/library/metadata/$ratingKey"
        }

        val queryParams = "type=video" +
            "&key=${Uri.encode("/library/metadata/$ratingKey")}" +
            "&containerKey=${Uri.encode(containerKey)}" +
            "&offset=$viewOffsetMs" +
            "&machineIdentifier=$serverMachineId" +
            "&protocol=$protocol" +
            "&address=$serverHost" +
            "&port=$serverPort" +
            "&commandID=1" +
            (if (prefs.plexToken.isNotBlank()) "&token=${prefs.plexToken}&X-Plex-Token=${prefs.plexToken}" else "")

        val targetEndpoints = listOf(
            "http://127.0.0.1:${player.port}/player/playback/playMedia?$queryParams",
            "http://${player.address}:${player.port}/player/playback/playMedia?$queryParams",
            "$baseUrl/player/playback/playMedia?$queryParams"
        ).distinct()

        for (endpoint in targetEndpoints) {
            try {
                val reqBuilder = Request.Builder()
                    .url(endpoint)
                    .get()
                    .header("X-Plex-Client-Identifier", prefs.clientIdentifier)
                    .header("X-Plex-Device-Name", "Plex Channel Connect")
                    .header("X-Plex-Target-Client-Identifier", player.machineIdentifier)

                if (prefs.plexToken.isNotBlank()) {
                    reqBuilder.header("X-Plex-Token", prefs.plexToken)
                }

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        return true
                    }
                }
            } catch (_: Exception) {
            }
        }
        return false
    }
}
