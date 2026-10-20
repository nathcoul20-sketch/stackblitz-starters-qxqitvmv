package com.scorecast.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.concurrent.thread

/** Création / modification d'un club : nom, logo, couleurs (avec aperçu), accès aux médias. */
class ClubEditActivity : AppCompatActivity() {

    private lateinit var repo: ClubRepository
    private lateinit var club: Club
    private var isNew = true

    /** Logo choisi mais pas encore enregistré (null + [logoRemoved] : retiré). */
    private var pendingLogo: Bitmap? = null
    private var logoRemoved = false

    private lateinit var name: TextInputEditText
    private lateinit var primary: ColorPaletteView
    private lateinit var secondary: ColorPaletteView

    private val pickLogo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        thread {
            val bmp = Ui.loadScaledImage(this, uri)
            runOnUiThread {
                if (bmp == null) {
                    Toast.makeText(this, "Image illisible", Toast.LENGTH_LONG).show()
                } else {
                    pendingLogo = bmp
                    logoRemoved = false
                    refresh()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_club_edit)
        Ui.fitSystemBars(this)
        repo = ClubRepository(this)
        val existing = repo.get(intent.getStringExtra(EXTRA_CLUB_ID))
        isNew = existing == null
        club = existing ?: Club(primary = 0xFF1E4FD8.toInt(), secondary = 0xFFFFFFFF.toInt())

        Ui.header(
            this, 2,
            if (isNew) "Nouveau club" else "Modifier le club",
            "Nom, couleurs et logo du club, utilisés pour l'incrustation et les écrans du live.",
        )

        name = findViewById(R.id.name)
        primary = findViewById(R.id.primary)
        secondary = findViewById(R.id.secondary)
        name.setText(club.name)
        primary.selected = club.primary
        secondary.selected = club.secondary
        primary.onColorSelected = { refresh() }
        secondary.onColorSelected = { refresh() }
        name.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                findViewById<TextInputLayout>(R.id.nameLayout).error = null
                refresh()
            }
        })

        findViewById<View>(R.id.pickLogo).setOnClickListener { pickLogo.launch("image/*") }
        findViewById<View>(R.id.removeLogo).setOnClickListener {
            pendingLogo = null
            logoRemoved = true
            refresh()
        }
        findViewById<View>(R.id.media).setOnClickListener {
            if (saveClub()) startActivity(Intent(this, MediaActivity::class.java).putExtra(EXTRA_CLUB_ID, club.id))
        }
        findViewById<View>(R.id.share).apply {
            visibility = if (isNew) View.GONE else View.VISIBLE
            setOnClickListener { if (saveClub()) shareClub() }
        }
        findViewById<View>(R.id.delete).apply {
            visibility = if (isNew) View.GONE else View.VISIBLE
            setOnClickListener { confirmDelete() }
        }
        findViewById<TextView>(R.id.save).text = if (isNew) "Enregistrer et continuer" else "Enregistrer"
        findViewById<View>(R.id.save).setOnClickListener { onSave() }
        findViewById<View>(R.id.previewBox).background =
            Ui.gradient(0xFF2E5B3A.toInt(), 0xFF101A13.toInt(), Ui.dp(this, 16f).toFloat())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Retour de l'écran des médias : nombre de médias à jour
        repo.get(club.id)?.let { saved -> club.media.clear(); club.media.putAll(saved.media) }
        updateMediaInfo()
    }

    private fun currentLogo(): Bitmap? = when {
        pendingLogo != null -> pendingLogo
        logoRemoved -> null
        else -> repo.logo(club)
    }

    private fun refresh() {
        val logo = currentLogo()
        val sport = AppPrefs(this).sport
        val state = Previews.sampleState(sport, name.text.toString().uppercase(), primary.selected, secondary.selected)
        findViewById<ImageView>(R.id.preview).setImageBitmap(Previews.scoreboard(this, state, logo))
        val badgeClub = Club(name = name.text.toString(), primary = primary.selected, secondary = secondary.selected)
        findViewById<ImageView>(R.id.logo).setImageBitmap(Ui.clubBadge(this, badgeClub, logo, Ui.dp(this, 76f)))
        findViewById<View>(R.id.removeLogo).visibility = if (logo != null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.colorWarning).visibility =
            if (Colors.tooClose(primary.selected, secondary.selected)) View.VISIBLE else View.GONE
        updateMediaInfo()
    }

    private fun updateMediaInfo() {
        val n = club.media.size
        findViewById<TextView>(R.id.mediaInfo).text =
            if (n == 0) "Intro, pause, fin, transition, musique : aucun importé"
            else "$n média${if (n > 1) "s" else ""} importé${if (n > 1) "s" else ""}"
    }

    /** Enregistre le club ; false si le nom manque. */
    private fun saveClub(): Boolean {
        val n = name.text.toString().trim()
        if (n.isEmpty()) {
            findViewById<TextInputLayout>(R.id.nameLayout).error = "Indiquez le nom du club"
            findViewById<View>(R.id.scroll).scrollTo(0, 0)
            return false
        }
        club.name = n.uppercase()
        club.primary = primary.selected
        club.secondary = secondary.selected
        pendingLogo?.let { bmp ->
            runCatching {
                repo.logoFile(club).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                club.hasLogo = true
            }
            pendingLogo = null
        }
        if (logoRemoved) {
            repo.logoFile(club).delete()
            club.hasLogo = false
            logoRemoved = false
        }
        repo.save(club)
        return true
    }

    private fun onSave() {
        if (!saveClub()) return
        if (isNew) {
            // Nouveau club : il devient le club choisi, on passe à la plateforme
            AppPrefs(this).clubId = club.id
            startActivity(Intent(this, PlatformActivity::class.java).putExtra(EXTRA_CLUB_ID, club.id))
        }
        finish()
    }

    /** Affiche le QR code à scanner depuis l'autre téléphone (ScoreCast → Votre club → Recevoir). */
    private fun shareClub() {
        @Suppress("DEPRECATION")
        val progress = android.app.ProgressDialog(this).apply {
            setMessage("Préparation…")
            setCancelable(false)
            show()
        }
        thread {
            val result = runCatching { ClubShare.startSending(this, repo, club) }
            runOnUiThread {
                progress.dismiss()
                val pair = result.getOrNull()
                if (pair == null) {
                    AlertDialog.Builder(this)
                        .setTitle("Pas de réseau local")
                        .setMessage(result.exceptionOrNull()?.message?.let { "Erreur : $it" }
                            ?: "Connectez les deux téléphones au même Wi-Fi (ou l'un au point d'accès de l'autre), puis réessayez.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@runOnUiThread
                }
                val (sender, qrText) = pair
                val view = layoutInflater.inflate(R.layout.dialog_remote, null)
                view.findViewById<ImageView>(R.id.qr).setImageBitmap(NetworkUtils.qrCode(qrText))
                view.findViewById<TextView>(R.id.info).text =
                    "Sur l'autre téléphone : ScoreCast → choisir un sport → « Recevoir un club », puis scannez ce QR code.\n\n" +
                        "Les deux téléphones doivent être sur le même Wi-Fi (ou l'un connecté au point d'accès de l'autre).\n\n" +
                        "Tout est copié : nom, couleurs, logo, partenaires, vidéos, plateforme et clé de diffusion."
                AlertDialog.Builder(this)
                    .setTitle("Envoyer ${club.name}")
                    .setView(view)
                    .setPositiveButton("Terminé", null)
                    .setOnDismissListener { sender.stop() }
                    .show()
            }
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Supprimer ${club.name} ?")
            .setMessage("Le club, son logo et ses médias importés seront supprimés de ce téléphone.")
            .setPositiveButton("Supprimer") { _, _ ->
                repo.delete(club)
                finish()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
