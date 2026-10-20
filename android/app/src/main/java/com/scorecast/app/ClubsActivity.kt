package com.scorecast.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

/** Étape 2 : choix du club (clubs enregistrés) ou création d'un nouveau club. */
class ClubsActivity : AppCompatActivity() {

    private lateinit var repo: ClubRepository
    private lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clubs)
        Ui.fitSystemBars(this)
        repo = ClubRepository(this)
        prefs = AppPrefs(this)
        val sport = prefs.sport
        Ui.header(this, 2, "Votre club", Ui.withSportIcon(this, sport, "${sport.label} · choisissez le club qui diffuse le match."))
        findViewById<View>(R.id.receive).setOnClickListener { scanClub() }
        findViewById<View>(R.id.add).setOnClickListener {
            startActivity(Intent(this, ClubEditActivity::class.java))
        }
        // Premier lancement : on ouvre directement la création du club
        if (savedInstanceState == null && repo.all().isEmpty()) {
            startActivity(Intent(this, ClubEditActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val clubs = repo.all()
        val list = findViewById<LinearLayout>(R.id.list)
        list.removeAllViews()
        findViewById<View>(R.id.empty).visibility = if (clubs.isEmpty()) View.VISIBLE else View.GONE
        val size = Ui.dp(this, 56f)
        for (club in clubs) {
            val item = layoutInflater.inflate(R.layout.item_club, list, false) as MaterialCardView
            item.findViewById<ImageView>(R.id.badge).setImageBitmap(Ui.clubBadge(this, club, repo.logo(club), size))
            item.findViewById<TextView>(R.id.name).text = club.name
            val radius = Ui.dp(this, 9f).toFloat()
            item.findViewById<View>(R.id.color1).background = Ui.rounded(club.primary, radius).apply {
                setStroke(Ui.dp(this@ClubsActivity, 1f), 0x33FFFFFF)
            }
            item.findViewById<View>(R.id.color2).background = Ui.rounded(club.secondary, radius).apply {
                setStroke(Ui.dp(this@ClubsActivity, 1f), 0x33FFFFFF)
            }
            val mediaCount = club.media.size
            item.findViewById<TextView>(R.id.info).text = listOfNotNull(
                Platform.fromId(club.platformId)?.label,
                if (mediaCount > 0) "$mediaCount média${if (mediaCount > 1) "s" else ""}" else null,
            ).joinToString(" · ")
            item.strokeWidth = if (club.id == prefs.clubId) Ui.dp(this, 2f) else 0
            item.setOnClickListener { choose(club) }
            item.findViewById<ImageButton>(R.id.edit).setOnClickListener {
                startActivity(Intent(this, ClubEditActivity::class.java).putExtra(EXTRA_CLUB_ID, club.id))
            }
            list.addView(item)
        }
    }

    /** Scan du QR code affiché par l'autre téléphone (Modifier le club → Envoyer), puis copie. */
    private fun scanClub() {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val url = ClubShare.parse(barcode.rawValue.orEmpty())
                if (url == null) {
                    android.widget.Toast.makeText(this, "Ce QR code n'est pas un club ScoreCast", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    receiveClub(url)
                }
            }
            .addOnFailureListener {
                android.widget.Toast.makeText(this, "Scanner indisponible : ${it.message}", android.widget.Toast.LENGTH_LONG).show()
            }
    }

    private fun receiveClub(url: String) {
        @Suppress("DEPRECATION")
        val progress = android.app.ProgressDialog(this).apply {
            setMessage("Copie du club en cours…")
            setCancelable(false)
            show()
        }
        kotlin.concurrent.thread {
            val result = runCatching { ClubShare.receive(this, repo, url) }
            runOnUiThread {
                progress.dismiss()
                result.onSuccess { club ->
                    android.widget.Toast.makeText(this, "${club.name} copié sur ce téléphone", android.widget.Toast.LENGTH_LONG).show()
                    render()
                }.onFailure {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Copie impossible")
                        .setMessage("${it.message}\n\nVérifiez que les deux téléphones sont sur le même Wi-Fi et que la fenêtre d'envoi est toujours ouverte sur l'autre téléphone.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun choose(club: Club) {
        prefs.clubId = club.id
        startActivity(Intent(this, PlatformActivity::class.java).putExtra(EXTRA_CLUB_ID, club.id))
    }
}
