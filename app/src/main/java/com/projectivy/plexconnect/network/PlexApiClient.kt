package com.projectivy.plexconnect.network

import android.net.Uri
import com.projectivy.plexconnect.data.AppPreferences
import com.projectivy.plexconnect.data.PlexClientPlayer
import com.projectivy.plexconnect.data.PlexCollection
import com.projectivy.plexconnect.data.PlexLibrarySection
import com.projectivy.plexconnect.data.PlexMediaItem
import com.projectivy.plexconnect.data.PlexPlaybackUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PlexApiClient(private val prefs: AppPreferences) {

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private fun buildRequest(path: String): Request? {
        val base = prefs.serverUrl.trimEnd('/')
        if (base.isBlank()) return null
        val fullUrl = if (path.startsWith("http")) path else "$base/${path.trimStart('/')}"
        val builder = Request.Builder()
            .url(fullUrl)
            .header("Accept", "application/json")
            .header("X-Plex-Platform", "Android TV")
            .header("X-Plex-Device", "Android TV")
            .header("X-Plex-Device-Name", "Plex Channel Connect")
            .header("X-Plex-Product", "Plex Channel Connect")
            .header("X-Plex-Version", "1.1.0")
            .header("X-Plex-Client-Identifier", prefs.clientIdentifier)

        if (prefs.plexToken.isNotBlank()) {
            builder.header("X-Plex-Token", prefs.plexToken)
        }
        return builder.build()
    }

    suspend fun resolvePlaybackUrls(ratingKey: String, fallbackOffsetMs: Long): PlexPlaybackUrls? =
        withContext(Dispatchers.IO) {
            val req = buildRequest("/library/metadata/$ratingKey") ?: return@withContext null
            try {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val root = JSONObject(resp.body?.string() ?: return@withContext null)
                    val metaArr = root.optJSONObject("MediaContainer")?.optJSONArray("Metadata")
                        ?: return@withContext null
                    if (metaArr.length() == 0) return@withContext null

                    val obj = metaArr.getJSONObject(0)
                    val item = parseMediaItem(obj) ?: return@withContext null
                    val mediaArr = obj.optJSONArray("Media")
                    var partKey = ""
                    if (mediaArr != null && mediaArr.length() > 0) {
                        val parts = mediaArr.getJSONObject(0).optJSONArray("Part")
                        if (parts != null && parts.length() > 0) {
                            partKey = parts.getJSONObject(0).optString("key", "")
                        }
                    }

                    val cleanBase = prefs.serverUrl.trimEnd('/')
                    val tokParam = if (prefs.plexToken.isNotBlank()) "X-Plex-Token=${prefs.plexToken}" else ""

                    val directPlayUrl = if (partKey.isNotBlank()) {
                        val sep = if (partKey.contains("?")) "&" else "?"
                        if (tokParam.isNotBlank()) "$cleanBase$partKey$sep$tokParam" else "$cleanBase$partKey"
                    } else ""

                    val encodedMetaPath = Uri.encode("/library/metadata/$ratingKey")
                    val hlsBase = "$cleanBase/video/:/transcode/universal/start.m3u8" +
                        "?hasMDE=1" +
                        "&path=$encodedMetaPath" +
                        "&mediaIndex=0" +
                        "&partIndex=0" +
                        "&protocol=hls" +
                        "&fastSeek=1" +
                        "&directPlay=0" +
                        "&directStream=1" +
                        "&videoQuality=100" +
                        "&maxVideoBitrate=40000" +
                        "&X-Plex-Platform=Android" +
                        "&X-Plex-Client-Identifier=${prefs.clientIdentifier}" +
                        "&X-Plex-Product=Plex+Channel+Connect" +
                        "&X-Plex-Device=Android+TV"
                    val hlsUrl = if (tokParam.isNotBlank()) "$hlsBase&$tokParam" else hlsBase

                    PlexPlaybackUrls(
                        ratingKey = ratingKey,
                        title = item.displayTitle,
                        subtitle = item.displaySubtitle,
                        durationMs = item.duration,
                        viewOffsetMs = if (item.viewOffset > 0L) item.viewOffset else fallbackOffsetMs,
                        directPlayUrl = directPlayUrl.ifBlank { hlsUrl },
                        hlsTranscodeUrl = hlsUrl,
                        mediaPartKey = partKey
                    )
                }
            } catch (_: Exception) {
                null
            }
        }

    suspend fun reportTimelineToPlex(
        ratingKey: String,
        state: String,
        currentPositionMs: Long,
        durationMs: Long
    ) = withContext(Dispatchers.IO) {
        try {
            val safeDuration = durationMs.coerceAtLeast(1L)
            val safeTime = currentPositionMs.coerceIn(0L, safeDuration)
            val encodedKey = Uri.encode("/library/metadata/$ratingKey")
            val timelinePath = "/:/timeline" +
                "?ratingKey=$ratingKey" +
                "&key=$encodedKey" +
                "&state=$state" +
                "&hasMDE=1" +
                "&time=$safeTime" +
                "&duration=$safeDuration" +
                "&identifier=com.plexapp.plugins.library"

            val req = buildRequest(timelinePath) ?: return@withContext
            httpClient.newCall(req).execute().close()

            if (state == "stopped" && durationMs > 60_000L && safeTime >= (durationMs * 92L) / 100L) {
                val scrobblePath = "/:/scrobble?identifier=com.plexapp.plugins.library&key=$ratingKey"
                buildRequest(scrobblePath)?.let { httpClient.newCall(it).execute().close() }
            }
        } catch (_: Exception) {
        }
    }

    suspend fun fetchAvailablePlexClients(): List<PlexClientPlayer> = withContext(Dispatchers.IO) {
        val players = mutableListOf<PlexClientPlayer>()

        buildRequest("/clients")?.let { req ->
            try {
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val root = JSONObject(resp.body?.string() ?: "{}")
                        val servers = root.optJSONObject("MediaContainer")?.optJSONArray("Server")
                        if (servers != null) {
                            for (i in 0 until servers.length()) {
                                val s = servers.getJSONObject(i)
                                val id = s.optString("machineIdentifier", "")
                                val host = s.optString("address", s.optString("host", ""))
                                val port = s.optInt("port", 32500)
                                val name = s.optString("name", "Plex Player")
                                if (id.isNotBlank()) {
                                    players.add(PlexClientPlayer(name, id, host, port))
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (prefs.plexToken.isNotBlank()) {
            try {
                val resReq = Request.Builder()
                    .url("https://plex.tv/api/v2/resources?includeHttps=1")
                    .header("Accept", "application/json")
                    .header("X-Plex-Token", prefs.plexToken)
                    .header("X-Plex-Client-Identifier", prefs.clientIdentifier)
                    .build()

                httpClient.newCall(resReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body?.string() ?: "[]")
                        for (i in 0 until arr.length()) {
                            val dev = arr.getJSONObject(i)
                            val provides = dev.optString("provides", "")
                            val product = dev.optString("product", "")
                            if (provides.contains("player") || provides.contains("client") || product.contains("Android")) {
                                val clientId = dev.optString("clientIdentifier", "")
                                val name = dev.optString("name", "Android TV")
                                var localAddr = "127.0.0.1"
                                var localPort = 32500
                                val conns = dev.optJSONArray("connections")
                                if (conns != null && conns.length() > 0) {
                                    localAddr = conns.getJSONObject(0).optString("address", "127.0.0.1")
                                    localPort = conns.getJSONObject(0).optInt("port", 32500)
                                }
                                if (clientId.isNotBlank() && clientId != prefs.machineIdentifier) {
                                    players.add(PlexClientPlayer(name, clientId, localAddr, localPort))
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        players.distinctBy { it.machineIdentifier }
    }

    suspend fun fetchLibrarySections(): List<PlexLibrarySection> = withContext(Dispatchers.IO) {
        val req = buildRequest("/library/sections") ?: return@withContext emptyList()
        val sections = mutableListOf<PlexLibrarySection>()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val root = JSONObject(resp.body?.string() ?: return@withContext emptyList())
                val dirs = root.optJSONObject("MediaContainer")?.optJSONArray("Directory") ?: return@withContext emptyList()
                for (i in 0 until dirs.length()) {
                    val d = dirs.getJSONObject(i)
                    val type = d.optString("type", "")
                    if (type == "movie" || type == "show") {
                        sections.add(
                            PlexLibrarySection(
                                key = d.optString("key"),
                                title = d.optString("title"),
                                type = type
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
        sections
    }

    suspend fun fetchContinueWatching(limit: Int = 30): List<PlexMediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<PlexMediaItem>()
        items.addAll(fetchMetadataList("/hubs/continueWatching?X-Plex-Container-Start=0&X-Plex-Container-Size=$limit"))
        if (items.isEmpty()) {
            items.addAll(fetchMetadataList("/library/onDeck?X-Plex-Container-Start=0&X-Plex-Container-Size=$limit"))
        }
        items
            .filter { it.viewOffset > 0L }
            .distinctBy { it.ratingKey }
            .sortedByDescending { it.lastViewedAt }
            .take(limit)
    }

    suspend fun fetchOnDeck(limit: Int = 30): List<PlexMediaItem> = withContext(Dispatchers.IO) {
        val raw = fetchMetadataList("/library/onDeck?X-Plex-Container-Start=0&X-Plex-Container-Size=$limit")
        val unwatchedNext = raw.filter { it.viewOffset == 0L }
        (if (unwatchedNext.isNotEmpty()) unwatchedNext else raw)
            .distinctBy { it.ratingKey }
            .take(limit)
    }

    suspend fun fetchRecentlyAddedMovies(limit: Int = 30): List<PlexMediaItem> = withContext(Dispatchers.IO) {
        val sections = fetchLibrarySections().filter { it.type == "movie" }
        val all = mutableListOf<PlexMediaItem>()
        for (sec in sections) {
            all.addAll(
                fetchMetadataList(
                    "/library/sections/${sec.key}/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=$limit"
                )
            )
        }
        if (all.isEmpty()) {
            all.addAll(
                fetchMetadataList("/library/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=$limit")
                    .filter { it.type == "movie" }
            )
        }
        all.distinctBy { it.ratingKey }
            .sortedByDescending { it.addedAt }
            .take(limit)
    }

    suspend fun fetchRecentlyAddedTv(limit: Int = 30): List<PlexMediaItem> = withContext(Dispatchers.IO) {
        val sections = fetchLibrarySections().filter { it.type == "show" }
        val all = mutableListOf<PlexMediaItem>()
        for (sec in sections) {
            all.addAll(
                fetchMetadataList(
                    "/library/sections/${sec.key}/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=$limit"
                )
            )
        }
        if (all.isEmpty()) {
            all.addAll(
                fetchMetadataList("/library/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=$limit")
                    .filter { it.type != "movie" }
            )
        }
        all.distinctBy { it.ratingKey }
            .sortedByDescending { it.addedAt }
            .take(limit)
    }

    suspend fun fetchRecentlyReleased(limit: Int = 30): List<PlexMediaItem> = withContext(Dispatchers.IO) {
        val sections = fetchLibrarySections()
        val all = mutableListOf<PlexMediaItem>()
        for (sec in sections) {
            all.addAll(
                fetchMetadataList(
                    "/library/sections/${sec.key}/all?sort=originallyAvailableAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=20"
                )
            )
        }
        all.distinctBy { it.ratingKey }.take(limit)
    }

    suspend fun fetchUnwatched(limit: Int = 30): List<PlexMediaItem> = withContext(Dispatchers.IO) {
        val sections = fetchLibrarySections()
        val all = mutableListOf<PlexMediaItem>()
        for (sec in sections) {
            all.addAll(
                fetchMetadataList(
                    "/library/sections/${sec.key}/unwatched?sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=20"
                )
            )
        }
        all.distinctBy { it.ratingKey }
            .sortedByDescending { it.addedAt }
            .take(limit)
    }

    suspend fun fetchAllCollections(): List<PlexCollection> = withContext(Dispatchers.IO) {
        val sections = fetchLibrarySections()
        val collections = mutableListOf<PlexCollection>()
        for (sec in sections) {
            val req = buildRequest("/library/sections/${sec.key}/collections") ?: continue
            try {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val root = JSONObject(resp.body?.string() ?: return@use)
                    val meta = root.optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: return@use
                    for (i in 0 until meta.length()) {
                        val m = meta.getJSONObject(i)
                        val childCount = m.optInt("childCount", 0)
                        if (childCount > 0) {
                            collections.add(
                                PlexCollection(
                                    ratingKey = m.optString("ratingKey"),
                                    title = m.optString("title"),
                                    sectionKey = sec.key,
                                    sectionTitle = sec.title,
                                    childCount = childCount
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        collections.distinctBy { it.ratingKey }
    }

    suspend fun fetchCollectionItems(collectionRatingKey: String, limit: Int = 40): List<PlexMediaItem> =
        withContext(Dispatchers.IO) {
            fetchMetadataList(
                "/library/collections/$collectionRatingKey/children?X-Plex-Container-Start=0&X-Plex-Container-Size=$limit"
            )
        }

    private fun fetchMetadataList(path: String): List<PlexMediaItem> {
        val req = buildRequest(path) ?: return emptyList()
        val results = mutableListOf<PlexMediaItem>()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val bodyStr = resp.body?.string() ?: return emptyList()
                val root = JSONObject(bodyStr)
                val container = root.optJSONObject("MediaContainer") ?: return emptyList()

                val hubs = container.optJSONArray("Hub")
                if (hubs != null) {
                    for (h in 0 until hubs.length()) {
                        val hubObj = hubs.getJSONObject(h)
                        val metaArr = hubObj.optJSONArray("Metadata") ?: continue
                        for (i in 0 until metaArr.length()) {
                            parseMediaItem(metaArr.getJSONObject(i))?.let { results.add(it) }
                        }
                    }
                }

                val metaArr = container.optJSONArray("Metadata")
                if (metaArr != null) {
                    for (i in 0 until metaArr.length()) {
                        parseMediaItem(metaArr.getJSONObject(i))?.let { results.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return results
    }

    private fun parseMediaItem(obj: JSONObject): PlexMediaItem? {
        val ratingKey = obj.optString("ratingKey", "")
        if (ratingKey.isBlank()) return null

        var partKey: String? = null
        val mediaArr = obj.optJSONArray("Media")
        if (mediaArr != null && mediaArr.length() > 0) {
            val parts = mediaArr.getJSONObject(0).optJSONArray("Part")
            if (parts != null && parts.length() > 0) {
                partKey = parts.getJSONObject(0).optString("key", "").takeIf { it.isNotBlank() }
            }
        }

        return PlexMediaItem(
            ratingKey = ratingKey,
            key = obj.optString("key", "/library/metadata/$ratingKey"),
            guid = obj.optString("guid", ""),
            slug = obj.optString("slug", ""),
            type = obj.optString("type", "movie"),
            title = obj.optString("title", "Untitled"),
            grandparentTitle = obj.optString("grandparentTitle", "").takeIf { it.isNotBlank() },
            parentTitle = obj.optString("parentTitle", "").takeIf { it.isNotBlank() },
            summary = obj.optString("summary", ""),
            year = obj.optInt("year", 0),
            duration = obj.optLong("duration", 0L),
            viewOffset = obj.optLong("viewOffset", 0L),
            viewCount = obj.optInt("viewCount", 0),
            seasonNumber = obj.optInt("parentIndex", 0),
            episodeNumber = obj.optInt("index", 0),
            thumbPath = obj.optString("thumb", "").takeIf { it.isNotBlank() },
            artPath = obj.optString("art", "").takeIf { it.isNotBlank() },
            grandparentThumbPath = obj.optString("grandparentThumb", "").takeIf { it.isNotBlank() },
            grandparentArtPath = obj.optString("grandparentArt", "").takeIf { it.isNotBlank() },
            parentThumbPath = obj.optString("parentThumb", "").takeIf { it.isNotBlank() },
            partKeyPath = partKey,
            contentRating = obj.optString("contentRating", "").takeIf { it.isNotBlank() },
            rating = obj.optDouble("audienceRating", obj.optDouble("rating", 0.0)).takeIf { it > 0.0 },
            addedAt = obj.optLong("addedAt", 0L),
            lastViewedAt = obj.optLong("lastViewedAt", 0L),
            serverBaseUrl = prefs.serverUrl,
            serverMachineId = prefs.machineIdentifier,
            serverToken = prefs.plexToken
        )
    }
}
