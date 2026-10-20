package com.scorecast.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

/** Écran d'accueil (étape 1) : choix du sport. */
class SportActivity : AppCompatActivity() {

    private data class Look(val top: Int, val bottom: Int, val details: String)

    private val looks = mapOf(
        Sport.FOOT to Look(0xFF1FA35B.toInt(), 0xFF0B3B22.toInt(), "Chrono, temps additionnel, buteurs, cartons, tirs au but"),
        Sport.VOLLEY to Look(0xFFF2B705.toInt(), 0xFF7A4B00.toInt(), "Points, sets, service, tie-break"),
        Sport.BASKET to Look(0xFFFF7A1A.toInt(), 0xFF6B2306.toInt(), "Quarts-temps, +1 / +2 / +3, fautes d'équipe, temps morts"),
        Sport.HAND to Look(0xFF2F7BFF.toInt(), 0xFF0B2A6B.toInt(), "Mi-temps de 30 min, exclusions 2', temps morts"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sport)
        Ui.fitSystemBars(this)
        val container = findViewById<LinearLayout>(R.id.sports)
        // 2 cartes par ligne
        Sport.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = Ui.dp(this@SportActivity, 12f) }
            }
            pair.forEachIndexed { i, sport -> row.addView(card(sport, first = i == 0)) }
            container.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        showResume()
    }

    private fun card(sport: Sport, first: Boolean): View {
        val look = looks.getValue(sport)
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@SportActivity, 200f), 1f).apply {
                if (!first) marginStart = Ui.dp(this@SportActivity, 12f)
            }
            radius = Ui.dp(this@SportActivity, 22f).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            contentDescription = sport.label
            setOnClickListener { choose(sport) }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.gradient(look.top, look.bottom, Ui.dp(this@SportActivity, 22f).toFloat())
            val pad = Ui.dp(this@SportActivity, 16f)
            setPadding(pad, pad, pad, pad)
        }
        if (sport.emoji.isNotEmpty()) {
            inner.addView(TextView(this).apply {
                text = sport.emoji
                textSize = 52f
            })
        } else {
            // Hand : ballon dessiné (pas d'émoji ballon de hand)
            val size = Ui.dp(this, 60f)
            inner.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_handball)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(size, size).apply { topMargin = Ui.dp(this@SportActivity, 6f) })
        }
        inner.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        inner.addView(TextView(this).apply {
            text = sport.label
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        inner.addView(TextView(this).apply {
            text = look.details
            textSize = 12f
            setTextColor(0xD9FFFFFF.toInt())
            maxLines = 3
        })
        card.addView(inner)
        return card
    }

    private fun choose(sport: Sport) {
        AppPrefs(this).sport = sport
        startActivity(Intent(this, ClubsActivity::class.java))
    }

    /** Raccourci « Reprendre » : même sport, même club, même plateforme → directement la page du match. */
    private fun showResume() {
        val prefs = AppPrefs(this)
        val repo = ClubRepository(this)
        val club = repo.get(prefs.clubId)
        val card = findViewById<MaterialCardView>(R.id.resume)
        if (club == null || Platform.fromId(club.platformId) == null || club.lastSport.isEmpty()) {
            card.visibility = View.GONE
            return
        }
        val sport = Sport.fromId(club.lastSport)
        val platform = Platform.fromId(club.platformId)!!
        card.visibility = View.VISIBLE
        findViewById<TextView>(R.id.resumeText).text = Ui.withSportIcon(this, sport, "${club.name} · ${platform.label}", 16f)
        val size = Ui.dp(this, 48f)
        findViewById<ImageView>(R.id.resumeBadge).setImageBitmap(Ui.clubBadge(this, club, repo.logo(club), size))
        card.setOnClickListener {
            prefs.sport = sport
            startActivity(Intent(this, MatchActivity::class.java).putExtra(EXTRA_CLUB_ID, club.id))
        }
    }
}
