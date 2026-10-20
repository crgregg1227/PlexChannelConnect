package com.projectivy.plexconnect.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.projectivy.plexconnect.data.AppPreferences
import com.projectivy.plexconnect.network.PlexApiClient
import com.projectivy.plexconnect.playback.PlexLauncher
import kotlinx.coroutines.launch

class MediaDetailsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ratingKey = intent.getStringExtra("ratingKey") ?: run {
            finish()
            return
        }
        val title = intent.getStringExtra("title") ?: "Plex Media"
        val subtitle = intent.getStringExtra("subtitle") ?: ""
        val summary = intent.getStringExtra("summary") ?: ""
        val duration = intent.getLongExtra("duration", 0L)
        val viewOffset = intent.getLongExtra("viewOffset", 0L)
        val posterUrl = intent.getStringExtra("posterUrl") ?: ""
        val backdropUrl = intent.getStringExtra("backdropUrl") ?: ""

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#D90B0F17"))
        }

        val backdropImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.28f
        }
        if (backdropUrl.isNotBlank()) {
            backdropImage.load(backdropUrl)
        }
        root.addView(
            backdropImage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(64, 56, 64, 56)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2161B22"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#30363D"))
            }
        }

        val posterView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#21262D"))
                cornerRadius = 16f
            }
        }
        val posterToLoad = posterUrl.ifBlank { backdropUrl }
        if (posterToLoad.isNotBlank()) {
            posterView.load(posterToLoad)
        }
        val posterParams = LinearLayout.LayoutParams(340, 500).apply {
            rightMargin = 56
        }
        card.addView(posterView, posterParams)

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val badgeText = TextView(this).apply {
            text = if (viewOffset > 0L) "CONTINUE WATCHING" else "PLEX MEDIA DETAILS"
            setTextColor(Color.parseColor("#E5A00D"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        rightCol.addView(badgeText)

        val titleText = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 8, 0, 6)
        }
        rightCol.addView(titleText)

        if (subtitle.isNotBlank()) {
            val subText = TextView(this).apply {
                text = subtitle
                setTextColor(Color.parseColor("#8B949E"))
                textSize = 16f
                setPadding(0, 0, 0, 16)
            }
            rightCol.addView(subText)
        }

        if (viewOffset > 0L && duration > 0L) {
            val progressPct = ((viewOffset.toDouble() / duration.toDouble()) * 100).toInt().coerceIn(1, 100)
            val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = progressPct
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E5A00D"))
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#30363D"))
            }
            val pbParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                14
            ).apply {
                bottomMargin = 20
            }
            rightCol.addView(progressBar, pbParams)
        }

        val summaryScroll = ScrollView(this).apply {
            isFocusable = false
        }
        val summaryText = TextView(this).apply {
            text = summary.ifBlank { "Loading details from Plex Media Server..." }
            setTextColor(Color.parseColor("#C9D1D9"))
            textSize = 16f
            setLineSpacing(0f, 1.25f)
        }
        summaryScroll.addView(summaryText)
        val summaryParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            180
        ).apply {
            bottomMargin = 28
        }
        rightCol.addView(summaryScroll, summaryParams)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

        val primaryLabel = if (viewOffset > 0L) {
            val mins = (viewOffset / 60000L).coerceAtLeast(1L)
            "▶  Resume from ${mins}m"
        } else {
            "▶  Play Now in Plex"
        }

        val playButton = createTvButton(primaryLabel, isPrimary = true) {
            PlexLauncher.startDirectPlayback(
                context = this,
                ratingKey = ratingKey,
                viewOffset = viewOffset,
                resume = true
            )
            finish()
        }
        buttonRow.addView(playButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 20 })

        if (viewOffset > 0L) {
            val startOverButton = createTvButton("↺  Play from Beginning", isPrimary = false) {
                PlexLauncher.startDirectPlayback(
                    context = this,
                    ratingKey = ratingKey,
                    viewOffset = 0L,
                    resume = false
                )
                finish()
            }
            buttonRow.addView(startOverButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 20 })
        }

        val openPlexPageButton = createTvButton("Open Plex Details Screen", isPrimary = false) {
            PlexLauncher.openInPlexPreplayDetails(this, ratingKey)
            finish()
        }
        buttonRow.addView(openPlexPageButton)

        rightCol.addView(buttonRow)
        card.addView(
            rightCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val cardParams = FrameLayout.LayoutParams(
            1360,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        root.addView(card, cardParams)
        setContentView(root)

        playButton.requestFocus()

        if (summary.isBlank()) {
            val prefs = AppPreferences(this)
            val api = PlexApiClient(prefs)
            lifecycleScope.launch {
                val fresh = api.fetchSingleItem(ratingKey)
                if (fresh != null && fresh.summary.isNotBlank()) {
                    summaryText.text = fresh.summary
                }
            }
        }
    }

    private fun createTvButton(
        label: String,
        isPrimary: Boolean,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(36, 20, 36, 20)
            isFocusable = true
            isFocusableInTouchMode = true

            fun updateStyle(hasFocus: Boolean) {
                val bgColor = when {
                    hasFocus -> Color.parseColor("#E5A00D")
                    isPrimary -> Color.parseColor("#382B0C")
                    else -> Color.parseColor("#21262D")
                }
                val textColor = if (hasFocus) Color.parseColor("#0D1117") else Color.WHITE
                val strokeColor = if (hasFocus) Color.WHITE else Color.parseColor("#E5A00D")
                setTextColor(textColor)
                background = GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = 14f
                    setStroke(if (hasFocus) 3 else 2, strokeColor)
                }
            }

            updateStyle(false)
            setOnFocusChangeListener { _, hasFocus -> updateStyle(hasFocus) }
            setOnClickListener { onClick() }
        }
    }
}
