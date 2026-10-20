package com.projectivy.plexconnect.network

import com.projectivy.plexconnect.data.PlexClientPlayer
import com.projectivy.plexconnect.data.PlexServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class PlexPinResponse(
    val id: Long,
    val code: String,
    val authToken: String? = null
)

class PlexDiscoveryAndAuth(private val clientId: String) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun discoverLocalServerViaGdm(): List<PlexServerInfo> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<PlexServerInfo>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 2200

            val msg = "M-SEARCH * HTTP/1.1\r\n\r\n".toByteArray(Charsets.UTF_8)
            val group = InetAddress.getByName("239.0.0.250")
            val packet = DatagramPacket(msg, msg.size, group, 32414)
            socket.send(packet)

            val buf = ByteArray(2048)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 2200) {
                val recv = DatagramPacket(buf, buf.size)
                socket.receive(recv)
                val body = String(recv.data, 0, recv.length, Charsets.UTF_8)
                if (body.contains("200 OK")) {
                    val hostIp = recv.address.hostAddress ?: continue
                    var port = "32400"
                    var name = "Local Plex Server ($hostIp)"
                    var machineId = ""

                    for (line in body.lines()) {
                        val parts = line.split(":", limit = 2)
                        if (parts.size == 2) {
                            val k = parts[0].trim().lowercase()
                            val v = parts[1].trim()
                            when (k) {
                                "port" -> if (v.isNotBlank()) port = v
                                "name" -> if (v.isNotBlank()) name = v
                                "resource-identifier" -> machineId = v
                            }
                        }
                    }

                    val baseUrl = "http://$hostIp:$port"
                    val noAuthWorks = probeUnauthenticatedAccess(baseUrl)
                    if (machineId.isBlank()) {
                        machineId = fetchMachineIdentifier(baseUrl) ?: hostIp
                    }
                    discovered.add(
                        PlexServerInfo(
                            name = name,
                            baseUrl = baseUrl,
                            machineIdentifier = machineId,
                            token = "",
                            isLocalAuthFree = noAuthWorks
                        )
                    )
                }
            }
        } catch (_: Exception) {
        } finally {
            socket?.close()
        }
        discovered.distinctBy { it.baseUrl }
    }

    suspend fun discoverLocalPlayersViaGdm(): List<PlexClientPlayer> = withContext(Dispatchers.IO) {
        val players = mutableListOf<PlexClientPlayer>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 1200

            val msg = "M-SEARCH * HTTP/1.1\r\n\r\n".toByteArray(Charsets.UTF_8)
            val group = InetAddress.getByName("239.0.0.250")
            val packet = DatagramPacket(msg, msg.size, group, 32412)
            socket.send(packet)

            val buf = ByteArray(2048)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 1200) {
                val recv = DatagramPacket(buf, buf.size)
                socket.receive(recv)
                val body = String(recv.data, 0, recv.length, Charsets.UTF_8)
                if (body.contains("200 OK")) {
                    val hostIp = recv.address.hostAddress ?: continue
                    var port = 32500
                    var name = "Plex Player ($hostIp)"
                    var machineId = ""

                    for (line in body.lines()) {
                        val parts = line.split(":", limit = 2)
                        if (parts.size == 2) {
                            val k = parts[0].trim().lowercase()
                            val v = parts[1].trim()
                            when (k) {
                                "port" -> port = v.toIntOrNull() ?: 32500
                                "name" -> if (v.isNotBlank()) name = v
                                "resource-identifier" -> machineId = v
                            }
                        }
                    }
                    if (machineId.isNotBlank()) {
                        players.add(
                            PlexClientPlayer(
                                name = name,
                                machineIdentifier = machineId,
                                address = hostIp,
                                port = port
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            socket?.close()
        }
        players.distinctBy { it.machineIdentifier }
    }

    fun probeUnauthenticatedAccess(baseUrl: String): Boolean {
        return try {
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/library/sections")
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    fun fetchMachineIdentifier(baseUrl: String): String? {
        return try {
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/identity")
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: return null)
                json.optJSONObject("MediaContainer")?.optString("machineIdentifier")
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun requestPlexPin(): PlexPinResponse? = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder()
                .add("strong", "false")
                .add("X-Plex-Product", "Plex Channel Connect")
                .add("X-Plex-Client-Identifier", clientId)
                .build()

            val request = Request.Builder()
                .url("https://plex.tv/api/v2/pins")
                .post(body)
                .header("Accept", "application/json")
                .header("X-Plex-Product", "Plex Channel Connect")
                .header("X-Plex-Client-Identifier", clientId)
                .header("X-Plex-Platform", "Android TV")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body?.string() ?: return@withContext null)
                PlexPinResponse(
                    id = json.getLong("id"),
                    code = json.getString("code"),
                    authToken = json.optString("authToken", "").takeIf { it.isNotBlank() && it != "null" }
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun pollPlexPin(pinId: Long): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://plex.tv/api/v2/pins/$pinId")
                .get()
                .header("Accept", "application/json")
                .header("X-Plex-Client-Identifier", clientId)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body?.string() ?: return@withContext null)
                val token = json.optString("authToken", "")
                if (token.isNotBlank() && token != "null") token else null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchServersForToken(authToken: String): List<PlexServerInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<PlexServerInfo>()
        try {
            val request = Request.Builder()
                .url("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1")
                .get()
                .header("Accept", "application/json")
                .header("X-Plex-Token", authToken)
                .header("X-Plex-Client-Identifier", clientId)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val arr = JSONArray(response.body?.string() ?: return@withContext emptyList())
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val provides = item.optString("provides", "")
                    if (!provides.contains("server")) continue

                    val name = item.optString("name", "Plex Server")
                    val machineId = item.optString("clientIdentifier", "")
                    val serverAccessToken = item.optString("accessToken", authToken).ifBlank { authToken }
                    val connections = item.optJSONArray("connections") ?: continue

                    val candidateUris = mutableListOf<String>()
                    for (c in 0 until connections.length()) {
                        val conn = connections.getJSONObject(c)
                        val isLocal = conn.optBoolean("local", false)
                        val address = conn.optString("address", "")
                        val port = conn.optInt("port", 32400)
                        val uri = conn.optString("uri", "")
                        if (isLocal && address.isNotBlank()) {
                            candidateUris.add(0, "http://$address:$port")
                        }
                        if (uri.isNotBlank()) {
                            candidateUris.add(uri)
                        }
                    }

                    var chosenUri = candidateUris.firstOrNull() ?: continue
                    for (uri in candidateUris.distinct()) {
                        if (verifyServerConnection(uri, serverAccessToken)) {
                            chosenUri = uri
                            break
                        }
                    }

                    results.add(
                        PlexServerInfo(
                            name = name,
                            baseUrl = chosenUri,
                            machineIdentifier = machineId,
                            token = serverAccessToken,
                            isLocalAuthFree = false
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        results
    }

    private fun verifyServerConnection(baseUrl: String, token: String): Boolean {
        return try {
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/identity")
                .header("Accept", "application/json")
                .header("X-Plex-Token", token)
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}
