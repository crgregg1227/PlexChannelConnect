package com.projectivy.plexconnect.channels

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.projectivy.plexconnect.data.AppPreferences
import com.projectivy.plexconnect.data.BuiltInChannelType
import com.projectivy.plexconnect.data.PlexMediaItem
import com.projectivy.plexconnect.network.PlexApiClient
import com.projectivy.plexconnect.ui.MainActivity
import com.projectivy.plexconnect.ui.SingleClickPlayActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ChannelSyncManager(private val context: Context) {

    private val prefs = AppPreferences(context)
    private val apiClient = PlexApiClient(prefs)

    companion object {
        private val syncMutex = Mutex()
    }

    suspend fun syncPlaybackChannelsNow() = withContext(Dispatchers.IO) {
        if (!prefs.isConfigured()) return@withContext
        syncMutex.withLock {
            val continueWatching = apiClient.fetchContinueWatching()
            if (prefs.isChannelEnabled(BuiltInChannelType.CONTINUE_WATCHING)) {
                publishPreviewChannel(
                    channelKey = BuiltInChannelType.CONTINUE_WATCHING.idKey,
                    displayName = BuiltInChannelType.CONTINUE_WATCHING.displayName,
                    description = BuiltInChannelType.CONTINUE_WATCHING.description,
                    items = continueWatching
                )
            }

            if (prefs.isChannelEnabled(BuiltInChannelType.ON_DECK)) {
                val onDeck = apiClient.fetchOnDeck()
                publishPreviewChannel(
                    channelKey = BuiltInChannelType.ON_DECK.idKey,
                    displayName = BuiltInChannelType.ON_DECK.displayName,
                    description = BuiltInChannelType.ON_DECK.description,
                    items = onDeck
                )
            }

            if (prefs.syncWatchNextSystemRow) {
                syncSystemWatchNextRow(continueWatching)
            }
            prefs.lastSyncTimestamp = System.currentTimeMillis()
        }
    }

    suspend fun syncAllChannels() = withContext(Dispatchers.IO) {
        if (!prefs.isConfigured()) return@withContext
        syncMutex.withLock {
            val continueWatching = apiClient.fetchContinueWatching()

            for (channelType in BuiltInChannelType.values()) {
                if (!prefs.isChannelEnabled(channelType)) {
                    removeChannelIfPresent(channelType.idKey)
                    continue
                }

                val items = when (channelType) {
                    BuiltInChannelType.CONTINUE_WATCHING -> continueWatching
                    BuiltInChannelType.ON_DECK -> apiClient.fetchOnDeck()
                    BuiltInChannelType.RECENTLY_ADDED_MOVIES -> apiClient.fetchRecentlyAddedMovies()
                    BuiltInChannelType.RECENTLY_ADDED_TV -> apiClient.fetchRecentlyAddedTv()
                    BuiltInChannelType.RECENTLY_RELEASED -> apiClient.fetchRecentlyReleased()
                    BuiltInChannelType.UNWATCHED -> apiClient.fetchUnwatched()
                }

                publishPreviewChannel(
                    channelKey = channelType.idKey,
                    displayName = channelType.displayName,
                    description = channelType.description,
                    items = items
                )
            }

            val enabledCollections = prefs.getEnabledCollectionKeys()
            if (enabledCollections.isNotEmpty()) {
                val allCollections = apiClient.fetchAllCollections().associateBy { it.ratingKey }
                for (collKey in enabledCollections) {
                    val coll = allCollections[collKey] ?: continue
                    val collItems = apiClient.fetchCollectionItems(collKey)
                    publishPreviewChannel(
                        channelKey = "collection_$collKey",
                        displayName = "Plex • ${coll.title}",
                        description = "${coll.sectionTitle} Collection",
                        items = collItems
                    )
                }
            }

            if (prefs.syncWatchNextSystemRow) {
                syncSystemWatchNextRow(continueWatching)
            }

            prefs.lastSyncTimestamp = System.currentTimeMillis()
        }
    }

    private fun publishPreviewChannel(
        channelKey: String,
        displayName: String,
        description: String,
        items: List<PlexMediaItem>
    ) {
        try {
            var channelId = prefs.getSystemChannelId(channelKey)
            val appIntentUri = Uri.parse(
                Intent(context, MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME)
            )

            val channel = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(displayName)
                .setDescription(description)
                .setAppLinkIntentUri(appIntentUri)
                .setInternalProviderId(channelKey)
                .build()

            if (channelId <= 0L || !channelExists(channelId)) {
                val insertedUri = context.contentResolver.insert(
                    TvContractCompat.Channels.CONTENT_URI,
                    channel.toContentValues()
                ) ?: return
                channelId = ContentUris.parseId(insertedUri)
                prefs.setSystemChannelId(channelKey, channelId)
                ChannelLogoUtils.storeChannelLogo(context, channelId, createPlexLogoBitmap())
                TvContractCompat.requestChannelBrowsable(context, channelId)
            } else {
                context.contentResolver.update(
                    TvContractCompat.buildChannelUri(channelId),
                    channel.toContentValues(),
                    null,
                    null
                )
                TvContractCompat.requestChannelBrowsable(context, channelId)
            }

            val programsUri = TvContractCompat.buildPreviewProgramsUriForChannel(channelId)
            context.contentResolver.delete(programsUri, null, null)

            items.forEachIndexed { index, item ->
                val weight = (items.size - index).coerceAtLeast(1)
                val program = buildPreviewProgram(channelId, item, weight)
                context.contentResolver.insert(
                    TvContractCompat.PreviewPrograms.CONTENT_URI,
                    program.toContentValues()
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun syncSystemWatchNextRow(items: List<PlexMediaItem>) {
        try {
            val watchNextUri = TvContractCompat.WatchNextPrograms.CONTENT_URI
            context.contentResolver.delete(watchNextUri, null, null)

            val now = System.currentTimeMillis()
            items.forEachIndexed { index, item ->
                val builder = WatchNextProgram.Builder()
                    .setType(
                        if (item.type == "episode")
                            TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
                        else
                            TvContractCompat.WatchNextPrograms.TYPE_MOVIE
                    )
                    .setWatchNextType(
                        if (item.isInProgress)
                            TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                        else
                            TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
                    )
                    .setLastEngagementTimeUtcMillis(now - (index * 1000L))
                    .setTitle(item.displayTitle)
                    .setDescription(item.displaySubtitle.ifBlank { item.summary })
                    .setPosterArtUri(Uri.parse(item.getCardImageUrl()))
                    .setPosterArtAspectRatio(
                        if (item.isInProgress)
                            TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                        else
                            TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
                    )
                    .setIntentUri(buildSingleClickPlayIntentUri(item))
                    .setInternalProviderId("watchnext_${item.ratingKey}")

                if (item.duration > 0L) {
                    builder.setDurationMillis(item.duration.toInt())
                }
                if (item.viewOffset > 0L) {
                    builder.setLastPlaybackPositionMillis(item.viewOffset.toInt())
                }

                context.contentResolver.insert(
                    watchNextUri,
                    builder.build().toContentValues()
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun buildPreviewProgram(channelId: Long, item: PlexMediaItem, weight: Int): PreviewProgram {
        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(
                if (item.type == "episode")
                    TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
                else
                    TvContractCompat.PreviewPrograms.TYPE_MOVIE
            )
            .setTitle(item.displayTitle)
            .setDescription(item.displaySubtitle.ifBlank { item.summary })
            .setPosterArtUri(Uri.parse(item.getCardImageUrl()))
            .setPosterArtAspectRatio(
                if (item.isInProgress)
                    TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                else
                    TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
            )
            .setIntentUri(buildSingleClickPlayIntentUri(item))
            .setInternalProviderId(item.ratingKey)
            .setWeight(weight)

        if (item.duration > 0L) {
            builder.setDurationMillis(item.duration.toInt())
        }
        if (item.viewOffset > 0L) {
            builder.setLastPlaybackPositionMillis(item.viewOffset.toInt())
        }
        if (item.year > 0) {
            builder.setReleaseDate(item.year.toString())
        }

        return builder.build()
    }

    private fun buildSingleClickPlayIntentUri(item: PlexMediaItem): Uri {
        val intent = Intent(context, SingleClickPlayActivity::class.java).apply {
            action = "com.projectivy.plexconnect.PLAY_MEDIA"
            data = Uri.parse("plexconnect://play/${item.ratingKey}")
            putExtra("ratingKey", item.ratingKey)
            putExtra("key", item.key)
            putExtra("type", item.type)
            putExtra("title", item.displayTitle)
            putExtra("subtitle", item.displaySubtitle)
            putExtra("duration", item.duration)
            putExtra("viewOffset", item.viewOffset)
            putExtra("partKeyPath", item.partKeyPath ?: "")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME))
    }

    private fun channelExists(channelId: Long): Boolean {
        val uri = TvContractCompat.buildChannelUri(channelId)
        return try {
            context.contentResolver.query(uri, arrayOf(TvContractCompat.Channels._ID), null, null, null)?.use {
                it.moveToFirst()
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun removeChannelIfPresent(channelKey: String) {
        val id = prefs.getSystemChannelId(channelKey)
        if (id > 0L) {
            try {
                val uri = ContentUris.withAppendedId(TvContractCompat.Channels.CONTENT_URI, id)
                context.contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            prefs.setSystemChannelId(channelKey, -1L)
        }
    }

    private fun createPlexLogoBitmap(): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#161B22") }
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 20f, 20f, bgPaint)

        val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E5A00D")
            style = Paint.Style.FILL
        }
        val path = Path().apply {
            moveTo(32f, 22f)
            lineTo(72f, 48f)
            lineTo(32f, 74f)
            close()
        }
        canvas.drawPath(path, playPaint)
        return bmp
    }
}
