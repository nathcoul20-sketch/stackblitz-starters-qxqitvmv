package com.scorecast.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.concurrent.thread

/** Étape 5 : le match (adversaire, compétition, durée), puis ouverture de la caméra. */
class MatchActivity : AppCompatActivity() {

    private lateinit var repo: ClubRepository
    private lateinit var club: Club
    private lateinit var sport: Sport
    private lateinit var platform: Platform

    private lateinit var awayName: TextInputEditText
    private lateinit var awayColor: ColorPaletteView
    private lateinit var competition: TextInputEditText
    private lateinit var periodMinutes: TextInputEditText

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) openCamera()
            else Toast.makeText(this, "La caméra et le micro sont nécessaires pour filmer", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_match)
        repo = ClubRepository(this)
        club = repo.get(intent.getStringExtra(EXTRA_CLUB_ID)) ?: run { finish(); return }
        sport = AppPrefs(this).sport
        platform = Platform.fromId(club.platformId) ?: Platform.NONE

        Ui.header(this, 5, "Le match", "${sport.emoji} ${sport.label} · ${club.name} reçoit.")

        awayName = findViewById(R.id.awayName)
        awayColor = findViewById(R.id.awayColor)
        competition = findViewById(R.id.competition)
        periodMinutes = findViewById(R.id.periodMinutes)

        findViewById<TextView>(R.id.homeName).text = club.name
        findViewById<ImageView>(R.id.badge).setImageBitmap(Ui.clubBadge(this, club, repo.logo(club), Ui.dp(this, 44f)))
        findViewById<View>(R.id.swap).setOnClickListener {
            startActivity(Intent(this, ClubsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }

        awayName.setText(club.awayName)
        awayColor.selected = club.awayColor
        competition.setText(club.competition)
        val periodLayout = findViewById<TextInputLayout>(R.id.periodLayout)
        if (sport.hasClock) {
            periodLayout.hint = "Durée d'un${if (sport.periodNoun == "mi-temps") "e" else ""} ${sport.periodNoun} (minutes)"
            periodLayout.helperText = "Entre ${sport.periodRange.first} et ${sport.periodRange.last} minutes"
            periodMinutes.setText((club.periodMinutes[sport.id] ?: sport.periodMinutes).toString())
        } else {
            periodLayout.visibility = View.GONE
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        }
        awayName.addTextChangedListener(watcher)
        competition.addTextChangedListener(watcher)
        awayColor.onColorSelected = { refresh() }

        findViewById<View>(R.id.previewBox).background =
            Ui.gradient(0xFF2E5B3A.toInt(), 0xFF101A13.toInt(), Ui.dp(this, 16f).toFloat())
        findViewById<View>(R.id.platformCard).setOnClickListener {
            startActivity(Intent(this, PlatformActivity::class.java).putExtra(EXTRA_CLUB_ID, club.id))
        }
        findViewById<View>(R.id.mediaCard).setOnClickListener {
            startActivity(Intent(this, MediaActivity::class.java).putExtra(EXTRA_CLUB_ID, club.id).putExtra(EXTRA_STEP, 5))
        }
        findViewById<View>(R.id.start).setOnClickListener { onStartClicked() }
    }

    override fun onResume() {
        super.onResume()
        if (!::club.isInitialized) return
        // Retour des écrans plateforme / médias : on relit la fiche du club
        repo.get(club.id)?.let { saved ->
            club = saved
            platform = Platform.fromId(club.platformId) ?: Platform.NONE
        }
        findViewById<TextView>(R.id.platformName).text = platform.label
        val n = club.media.size
        findViewById<TextView>(R.id.mediaInfo).text =
            if (n == 0) "Écrans aux couleurs du club" else "$n média${if (n > 1) "s" else ""} importé${if (n > 1) "s" else ""}"
        refresh()
    }

    private fun currentState(): ScoreState = Previews.sampleState(
        sport, club.name, club.primary, club.secondary,
        awayName.text.toString().trim().uppercase(), awayColor.selected, competition.text.toString().trim(),
    )

    private fun refresh() {
        findViewById<ImageView>(R.id.preview).setImageBitmap(Previews.scoreboard(this, currentState(), repo.logo(club)))
        findViewById<View>(R.id.colorWarning).visibility =
            if (Colors.tooClose(club.primary, awayColor.selected)) View.VISIBLE else View.GONE
        val title = findViewById<TextView>(R.id.titlePreview)
        if (platform == Platform.TWITCH && club.twitchAutoTitle && club.twitchRefreshToken.isNotEmpty()) {
            title.visibility = View.VISIBLE
            title.text = "Titre Twitch : " + TwitchApi.buildTitle(
                competition.text.toString(), club.name, awayName.text.toString().ifBlank { "ADVERSAIRE" }.uppercase(),
            )
        } else {
            title.visibility = View.GONE
        }
    }

    private fun onStartClicked() {
        val minutes = if (sport.hasClock) periodMinutes.text.toString().trim().toIntOrNull() else sport.periodMinutes
        if (sport.hasClock && (minutes == null || minutes !in sport.periodRange)) {
            findViewById<TextInputLayout>(R.id.periodLayout).error =
                "Entre ${sport.periodRange.first} et ${sport.periodRange.last} minutes"
            return
        }
        if (platform.needsKey && streamUrl().isEmpty()) {
            Toast.makeText(this, "Clé de diffusion manquante : touchez « Diffusion » pour la saisir", Toast.LENGTH_LONG).show()
            return
        }
        club.awayName = awayName.text.toString().trim().uppercase()
        club.awayColor = awayColor.selected
        club.competition = competition.text.toString().trim()
        club.lastSport = sport.id
        if (sport.hasClock && minutes != null) club.periodMinutes[sport.id] = minutes
        repo.save(club)
        AppPrefs(this).clubId = club.id

        val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) openCamera() else permissionLauncher.launch(missing.toTypedArray())
    }

    /** Adresse complète de diffusion (serveur + clé), vide en mode test ou si la clé manque. */
    private fun streamUrl(): String {
        val key = club.streamKeys[platform.id].orEmpty()
        return when {
            platform == Platform.NONE -> ""
            platform.needsServerUrl -> {
                val server = club.serverUrls[platform.id].orEmpty()
                when {
                    server.isEmpty() -> ""
                    key.isEmpty() -> if (platform == Platform.CUSTOM) server else ""
                    else -> joinUrl(server, key)
                }
            }
            key.isEmpty() -> ""
            else -> joinUrl(platform.url!!, key)
        }
    }

    private fun openCamera() {
        val key = club.streamKeys[platform.id].orEmpty()
        val url = streamUrl()
        val backupUrl = platform.backupUrl?.takeIf { key.isNotEmpty() }?.let { joinUrl(it, key) } ?: ""
        val away = club.awayName.ifBlank { "ADVERSAIRE" }

        if (platform == Platform.TWITCH && club.twitchAutoTitle) {
            val twitch = TwitchApi(club, repo)
            if (twitch.isConnected) updateTwitchTitle(twitch, TwitchApi.buildTitle(club.competition, club.name, away))
        }

        ScoreManager.init(
            ScoreState(
                sport = sport,
                homeName = club.name,
                awayName = away,
                competition = club.competition,
                homeColor = club.primary,
                homeAccent = club.secondary,
                awayColor = club.awayColor,
                periodMinutes = club.periodMinutes[sport.id] ?: sport.periodMinutes,
            )
        )
        startActivity(
            Intent(this, StreamActivity::class.java)
                .putExtra(StreamActivity.EXTRA_URL, url)
                .putExtra(StreamActivity.EXTRA_BACKUP_URL, backupUrl)
                .putExtra(StreamActivity.EXTRA_1080P, club.quality1080)
                .putExtra(EXTRA_CLUB_ID, club.id)
        )
    }

    private fun updateTwitchTitle(twitch: TwitchApi, title: String) {
        val appContext = applicationContext
        thread {
            val result = runCatching { twitch.updateTitle(title) }
            runOnUiThread {
                val message = result.fold(
                    onSuccess = { "Titre Twitch mis à jour" },
                    onFailure = { "Titre Twitch non modifié : ${it.message}" },
                )
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun joinUrl(base: String, key: String) = base.trimEnd('/') + "/" + key
}
