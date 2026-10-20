package com.projectivy.plexconnect.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("plex_connect_prefs", Context.MODE_PRIVATE)

    val clientIdentifier: String
        get() {
            var id = prefs.getString("client_id", null)
            if (id.isNullOrBlank()) {
                id = "plex-channel-connect-" + UUID.randomUUID().toString().take(12)
                prefs.edit().putString("client_id", id).apply()
            }
            return id
        }

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(value) = prefs.edit().putString("server_url", value.trimEnd('/')).apply()

    var serverName: String
        get() = prefs.getString("server_name", "") ?: ""
        set(value) = prefs.edit().putString("server_name", value).apply()

    var machineIdentifier: String
        get() = prefs.getString("machine_id", "") ?: ""
        set(value) = prefs.edit().putString("machine_id", value.trim()).apply()

    var plexToken: String
        get() = prefs.getString("plex_token", "") ?: ""
        set(value) = prefs.edit().putString("plex_token", value.trim()).apply()

    var isLocalNoAuth: Boolean
        get() = prefs.getBoolean("is_local_no_auth", false)
        set(value) = prefs.edit().putBoolean("is_local_no_auth", value).apply()

    var playbackEngineMode: PlaybackEngineMode
        get() {
            val saved = prefs.getString("playback_engine_mode", PlaybackEngineMode.BUILT_IN_EXOPLAYER.id)
            return PlaybackEngineMode.values().firstOrNull { it.id == saved }
                ?: PlaybackEngineMode.BUILT_IN_EXOPLAYER
        }
        set(value) = prefs.edit().putString("playback_engine_mode", value.id).apply()

    var targetPlexClientId: String
        get() = prefs.getString("target_plex_client_id", "") ?: ""
        set(value) = prefs.edit().putString("target_plex_client_id", value.trim()).apply()

    var syncWatchNextSystemRow: Boolean
        get() = prefs.getBoolean("sync_watch_next_row", true)
        set(value) = prefs.edit().putBoolean("sync_watch_next_row", value).apply()

    var lastSyncTimestamp: Long
        get() = prefs.getLong("last_sync_ts", 0L)
        set(value) = prefs.edit().putLong("last_sync_ts", value).apply()

    fun isChannelEnabled(channelType: BuiltInChannelType): Boolean {
        return prefs.getBoolean("chan_enabled_${channelType.idKey}", channelType.defaultEnabled)
    }

    fun setChannelEnabled(channelType: BuiltInChannelType, enabled: Boolean) {
        prefs.edit().putBoolean("chan_enabled_${channelType.idKey}", enabled).apply()
    }

    fun getEnabledCollectionKeys(): Set<String> {
        return prefs.getStringSet("enabled_collections", emptySet()) ?: emptySet()
    }

    fun toggleCollectionEnabled(ratingKey: String): Boolean {
        val current = getEnabledCollectionKeys().toMutableSet()
        val nowEnabled = if (current.contains(ratingKey)) {
            current.remove(ratingKey)
            false
        } else {
            current.add(ratingKey)
            true
        }
        prefs.edit().putStringSet("enabled_collections", current).apply()
        return nowEnabled
    }

    fun getSystemChannelId(channelKey: String): Long {
        return prefs.getLong("tv_channel_id_$channelKey", -1L)
    }

    fun setSystemChannelId(channelKey: String, id: Long) {
        prefs.edit().putLong("tv_channel_id_$channelKey", id).apply()
    }

    fun isConfigured(): Boolean {
        return serverUrl.isNotBlank() && (plexToken.isNotBlank() || isLocalNoAuth)
    }
}
