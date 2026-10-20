package com.scorecast.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

/** Étape 3 : plateforme de diffusion du live. */
class PlatformActivity : AppCompatActivity() {

    private lateinit var repo: ClubRepository
    private lateinit var club: Club

    private val descriptions = mapOf(
        Platform.TWITCH to "Clé de stream + connexion du compte pour le titre",
        Platform.YOUTUBE to "Clé du flux (YouTube Studio)",
        Platform.FACEBOOK to "Page ou profil, clé de stream",
        Platform.KICK to "URL du serveur + clé",
        Platform.TIKTOK to "URL du serveur + clé (comptes éligibles)",
        Platform.INSTAGRAM to "URL du serveur + clé",
        Platform.CUSTOM to "Tout serveur RTMP / RTMPS",
        Platform.NONE to "Tester l'affichage sans diffuser",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_platform)
        repo = ClubRepository(this)
        club = repo.get(intent.getStringExtra(EXTRA_CLUB_ID)) ?: run { finish(); return }
        Ui.header(this, 3, "Où diffuser le live ?", "Plateforme de ${club.name}. Elle sera retenue pour les prochains matchs.")
    }

    override fun onResume() {
        super.onResume()
        if (!::club.isInitialized) return
        club = repo.get(club.id) ?: club
        render()
    }

    private fun render() {
        val container = findViewById<LinearLayout>(R.id.platforms)
        container.removeAllViews()
        Platform.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = Ui.dp(this@PlatformActivity, 12f) }
            }
            pair.forEachIndexed { i, platform -> row.addView(tile(platform, first = i == 0)) }
            container.addView(row)
        }
    }

    private fun tile(platform: Platform, first: Boolean): View {
        val selected = club.platformId == platform.id
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@PlatformActivity, 128f), 1f).apply {
                if (!first) marginStart = Ui.dp(this@PlatformActivity, 12f)
            }
            radius = Ui.dp(this@PlatformActivity, 18f).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.sc_surface))
            strokeColor = getColor(R.color.sc_brand)
            strokeWidth = if (selected) Ui.dp(this@PlatformActivity, 2f) else 0
            isClickable = true
            contentDescription = platform.label
            setOnClickListener { choose(platform) }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Ui.dp(this@PlatformActivity, 14f)
            setPadding(pad, pad, pad, pad)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Pastille de couleur avec l'initiale (pas de logo de marque)
        top.addView(TextView(this).apply {
            val size = Ui.dp(this@PlatformActivity, 34f)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = Ui.rounded(platform.color, size / 2f)
            gravity = Gravity.CENTER
            text = if (platform == Platform.NONE) "–" else platform.label.first().toString()
            setTextColor(Colors.onColor(platform.color))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (selected) top.addView(ImageView(this).apply { setImageResource(R.drawable.ic_check) })
        inner.addView(top)
        inner.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        inner.addView(TextView(this).apply {
            text = platform.label
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        inner.addView(TextView(this).apply {
            text = descriptions[platform]
            textSize = 12f
            setTextColor(getColor(R.color.sc_muted))
            maxLines = 2
        })
        card.addView(inner)
        return card
    }

    private fun choose(platform: Platform) {
        club.platformId = platform.id
        repo.save(club)
        startActivity(Intent(this, ConnectActivity::class.java).putExtra(EXTRA_CLUB_ID, club.id))
    }
}
