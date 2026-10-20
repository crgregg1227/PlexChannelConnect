package com.projectivy.plexconnect.data

data class PlexServerInfo(
    val name: String,
    val baseUrl: String,
    val machineIdentifier: String,
    val token: String = "",
    val isLocalAuthFree: Boolean = false
)

data class PlexLibrarySection(
    val key: String,
    val title: String,
    val type: String // "movie" or "show"
)

data class PlexCollection(
    val ratingKey: String,
    val title: String,
    val sectionKey: String,
    val sectionTitle: String,
    val childCount: Int
)

data class PlexMediaItem(
    val ratingKey: String,
    val key: String,
    val type: String, // "movie", "episode", "show", "season"
    val title: String,
    val grandparentTitle: String? = null,
    val parentTitle: String? = null,
    val summary: String = "",
    val year: Int = 0,
    val duration: Long = 0L,
    val viewOffset: Long = 0L,
    val viewCount: Int = 0,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val thumbPath: String? = null,
    val artPath: String? = null,
    val grandparentThumbPath: String? = null,
    val grandparentArtPath: String? = null,
    val parentThumbPath: String? = null,
    val contentRating: String? = null,
    val rating: Double? = null,
    val addedAt: Long = 0L,
    val lastViewedAt: Long = 0L,
    val serverBaseUrl: String = "",
    val serverMachineId: String = "",
    val serverToken: String = ""
) {
    val isInProgress: Boolean
        get() = viewOffset > 0L

    val displayTitle: String
        get() = when (type) {
            "episode" -> if (!grandparentTitle.isNullOrBlank()) {
                "$grandparentTitle • S${seasonNumber}E${episodeNumber} - $title"
            } else {
                title
            }
            else -> title
        }

    val displaySubtitle: String
        get() = when (type) {
            "episode" -> {
                val pct = if (duration > 0 && viewOffset > 0) {
                    val minsLeft = ((duration - viewOffset) / 60000L).coerceAtLeast(1L)
                    "${minsLeft}m left • "
                } else ""
                "$pct$title"
            }
            else -> {
                val pct = if (duration > 0 && viewOffset > 0) {
                    val minsLeft = ((duration - viewOffset) / 60000L).coerceAtLeast(1L)
                    "${minsLeft}m left • "
                } else ""
                val yr = if (year > 0) "$year" else ""
                "$pct$yr"
            }
        }

    /**
     * Requirement 4:
     * - 16:9 landscape with a video still/backdrop if the video is in progress (viewOffset > 0)
     * - 2:3 vertical poster if the video is unplayed (viewOffset == 0)
     */
    fun getCardImageUrl(): String {
        val rawPath = if (isInProgress) {
            when (type) {
                "episode" -> thumbPath ?: artPath ?: grandparentArtPath ?: grandparentThumbPath
                else -> artPath ?: thumbPath
            }
        } else {
            when (type) {
                "episode" -> grandparentThumbPath ?: parentThumbPath ?: thumbPath
                else -> thumbPath ?: artPath
            }
        } ?: return ""

        return buildAuthenticatedImageUrl(rawPath, isInProgress)
    }

    fun getBackdropUrl(): String {
        val rawPath = artPath ?: grandparentArtPath ?: thumbPath ?: return ""
        return buildAuthenticatedImageUrl(rawPath, true)
    }

    fun getPosterUrl(): String {
        val rawPath = when (type) {
            "episode" -> grandparentThumbPath ?: parentThumbPath ?: thumbPath
            else -> thumbPath
        } ?: return ""
        return buildAuthenticatedImageUrl(rawPath, false)
    }

    private fun buildAuthenticatedImageUrl(path: String, landscape16x9: Boolean): String {
        if (path.isBlank()) return ""
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val cleanBase = serverBaseUrl.trimEnd('/')
        val tokenParam = if (serverToken.isNotBlank()) "X-Plex-Token=$serverToken" else ""
        return if (landscape16x9) {
            val encodedUrl = java.net.URLEncoder.encode(path, "UTF-8")
            val baseTranscode = "$cleanBase/photo/:/transcode?width=640&height=360&minSize=1&upscale=1&url=$encodedUrl"
            if (tokenParam.isNotBlank()) "$baseTranscode&$tokenParam" else baseTranscode
        } else {
            val sep = if (path.contains("?")) "&" else "?"
            if (tokenParam.isNotBlank()) "$cleanBase$path$sep$tokenParam" else "$cleanBase$path"
        }
    }
}

enum class BuiltInChannelType(
    val idKey: String,
    val displayName: String,
    val description: String,
    val defaultEnabled: Boolean
) {
    CONTINUE_WATCHING(
        "continue_watching",
        "Plex • Continue Watching",
        "In-progress movies and TV episodes (16:9 stills with progress)",
        true
    ),
    ON_DECK(
        "on_deck",
        "Plex • On Deck",
        "Next unwatched episodes in active TV series",
        true
    ),
    RECENTLY_ADDED_MOVIES(
        "recently_added_movies",
        "Plex • Recently Added Movies",
        "Newest movies added across your libraries",
        true
    ),
    RECENTLY_ADDED_TV(
        "recently_added_tv",
        "Plex • Recently Added TV",
        "Newest episodes and shows added across your libraries",
        true
    ),
    RECENTLY_RELEASED(
        "recently_released",
        "Plex • Recently Released",
        "Movies and shows sorted by release date",
        false
    ),
    UNWATCHED(
        "unwatched",
        "Plex • Unwatched",
        "Unwatched movies and TV shows from your libraries",
        false
    )
}
