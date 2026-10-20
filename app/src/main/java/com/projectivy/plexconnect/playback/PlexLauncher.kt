package com.projectivy.plexconnect.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.projectivy.plexconnect.data.AppPreferences
import com.projectivy.plexconnect.service.PlexRealtimeSyncService
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

    fun startDirectPlayback(
        context: Context,
        ratingKey: String,
        viewOffset: Long,
        resume: Boolean = true
    ) {
        val prefs = AppPreferences(context)
        val machineId = prefs.machineIdentifier
        val offsetToUse = if (resume) viewOffset else 0L

        PlexRealtimeSyncService.notifyPlexSessionStarted(context)

        val launched = tryLaunchPlexDeepLink(
            context = context,
            machineId = machineId,
            ratingKey = ratingKey,
            viewOffset = offsetToUse,
            directPlay = true
        )

        if (!launched) {
            val fallbackIntent = context.packageManager.getLaunchIntentForPackage(PLEX_PACKAGE)
            if (fallbackIntent != null) {
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallbackIntent)
            } else {
                Toast.makeText(context, "Plex for Android TV is not installed", Toast.LENGTH_LONG).show()
                return
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            delay(900)
            triggerCompanionPlayMedia(prefs, ratingKey, offsetToUse)
        }
    }

    fun openInPlexPreplayDetails(context: Context, ratingKey: String) {
        val prefs = AppPreferences(context)
        PlexRealtimeSyncService.notifyPlexSessionStarted(context)

        val launched = tryLaunchPlexDeepLink(
            context = context,
            machineId = prefs.machineIdentifier,
            ratingKey = ratingKey,
            viewOffset = 0L,
            directPlay = false
        )

        if (!launched) {
            val fallbackIntent = context.packageManager.getLaunchIntentForPackage(PLEX_PACKAGE)
            if (fallbackIntent != null) {
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallbackIntent)
            }
        }
    }

    private fun tryLaunchPlexDeepLink(
        context: Context,
        machineId: String,
        ratingKey: String,
        viewOffset: Long,
        directPlay: Boolean
    ): Boolean {
        val encodedMetaKey = Uri.encode("/library/metadata/$ratingKey")
        val candidateUris = if (directPlay) {
            listOf(
                "plex://play/?metadataKey=$encodedMetaKey&server=$machineId&offset=$viewOffset",
                "plex://server://$machineId/com.plexapp.plugins.library/library/metadata/$ratingKey?offset=$viewOffset",
                "plex://play?metadataKey=$encodedMetaKey&server=$machineId"
            )
        } else {
            listOf(
                "plex://server://$machineId/com.plexapp.plugins.library/library/metadata/$ratingKey",
                "plex://preplay/?metadataKey=$encodedMetaKey&server=$machineId"
            )
        }

        for (uriStr in candidateUris) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).apply {
                    setPackage(PLEX_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun triggerCompanionPlayMedia(
        prefs: AppPreferences,
        ratingKey: String,
        viewOffset: Long
    ) {
        try {
            val baseUrl = prefs.serverUrl.trimEnd('/')
            val machineId = prefs.machineIdentifier
            if (baseUrl.isBlank() || machineId.isBlank()) return

            val parsedUri = URI(baseUrl)
            val protocol = parsedUri.scheme ?: "http"
            val host = parsedUri.host ?: return
            val port = if (parsedUri.port > 0) parsedUri.port else 32400

            val itemUri = Uri.encode("server://$machineId/com.plexapp.plugins.library/library/metadata/$ratingKey")
            val pqUrl = "$baseUrl/playQueues?type=video&uri=$itemUri&continuous=1&own=1"
            val pqReqBuilder = Request.Builder()
                .url(pqUrl)
                .post("".toRequestBody())
                .header("Accept", "application/json")
                .header("X-Plex-Client-Identifier", prefs.clientIdentifier)

            if (prefs.plexToken.isNotBlank()) {
                pqReqBuilder.header("X-Plex-Token", prefs.plexToken)
            }

            var playQueueId = 0L
            client.newCall(pqReqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    playQueueId = json.optJSONObject("MediaContainer")?.optLong("playQueueID", 0L) ?: 0L
                }
            }

            val containerKey = if (playQueueId > 0L) {
                Uri.encode("/playQueues/$playQueueId?own=1&window=200")
            } else {
                Uri.encode("/library/metadata/$ratingKey")
            }

            val companionUrl = "http://127.0.0.1:32500/player/playback/playMedia" +
                "?key=${Uri.encode("/library/metadata/$ratingKey")}" +
                "&offset=$viewOffset" +
                "&machineIdentifier=$machineId" +
                "&protocol=$protocol" +
                "&address=$host" +
                "&port=$port" +
                "&containerKey=$containerKey" +
                (if (prefs.plexToken.isNotBlank()) "&token=${prefs.plexToken}" else "") +
                "&commandID=1"

            val compReq = Request.Builder()
                .url(companionUrl)
                .get()
                .header("X-Plex-Client-Identifier", prefs.clientIdentifier)
                .header("X-Plex-Device-Name", "Plex Channel Connect")
                .build()

            client.newCall(compReq).execute().close()
        } catch (_: Exception) {
        }
    }
}
