package com.scorecast.app

import android.app.ProgressDialog
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File
import kotlin.concurrent.thread

/**
 * Espace « Écrans & vidéos » d'un club : import facultatif de l'intro, de l'écran de pause,
 * de l'écran de fin, de la transition et de la musique d'attente.
 * Sans import, le live utilise des écrans générés aux couleurs du club et change d'écran directement.
 */
class MediaActivity : AppCompatActivity() {

    private lateinit var repo: ClubRepository
    private lateinit var club: Club
    private var pendingSlot: MediaSlot? = null

    private val partnerPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) addPartner(uri)
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val slot = pendingSlot ?: return@registerForActivityResult
        pendingSlot = null
        if (uri != null) import(slot, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media)
        Ui.fitSystemBars(this)
        repo = ClubRepository(this)
        club = repo.get(intent.getStringExtra(EXTRA_CLUB_ID)) ?: run { finish(); return }
        Ui.header(this, intent.getIntExtra(EXTRA_STEP, 2), "Écrans & vidéos", "Tout est facultatif : importez seulement ce que le club souhaite.")
        val logo = repo.logo(club)
        val sample = Previews.sampleState(AppPrefs(this).sport, club.name, club.primary, club.secondary)
        findViewById<ImageView>(R.id.generated).setImageBitmap(Previews.screen(this, sample, Scene.INTRO, logo))
        findViewById<View>(R.id.addPartner).setOnClickListener {
            if (club.partners.size >= OverlayRenderer.MAX_PARTNERS) {
                Toast.makeText(this, "8 logos maximum : retirez-en un d'abord", Toast.LENGTH_LONG).show()
            } else {
                partnerPicker.launch("image/*")
            }
        }
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.partnersInMatch).apply {
            isChecked = club.partnersInMatch
            setOnCheckedChangeListener { _, checked ->
                club.partnersInMatch = checked
                repo.save(club)
            }
        }
        render()
        renderPartners()
    }

    // ---- Partenaires ----

    private fun renderPartners() {
        val grid = findViewById<android.widget.GridLayout>(R.id.partners)
        grid.removeAllViews()
        val cell = (resources.displayMetrics.widthPixels - Ui.dp(this, 40f) - Ui.dp(this, 24f)) / 3
        for (name in club.partners.toList()) {
            val bmp = runCatching { android.graphics.BitmapFactory.decodeFile(File(repo.dir(club), name).path) }.getOrNull()
            val tile = ImageView(this).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = cell
                    height = (cell * 0.6f).toInt()
                    setMargins(0, 0, Ui.dp(this@MediaActivity, 8f), Ui.dp(this@MediaActivity, 8f))
                }
                background = Ui.rounded(android.graphics.Color.WHITE, Ui.dp(this@MediaActivity, 10f).toFloat())
                val pad = Ui.dp(this@MediaActivity, 8f)
                setPadding(pad, pad, pad, pad)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bmp)
                contentDescription = "Logo partenaire"
                setOnClickListener { removePartner(name) }
            }
            grid.addView(tile)
        }
        findViewById<View>(R.id.partnersInMatch).visibility = if (club.partners.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun addPartner(uri: Uri) {
        thread {
            val bmp = Ui.loadScaledImage(this, uri, 600)
            val name = "partenaire_${System.currentTimeMillis()}.png"
            val ok = bmp != null && runCatching {
                File(repo.dir(club), name).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }.isSuccess
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, "Image illisible", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                club.partners.add(name)
                repo.save(club)
                renderPartners()
            }
        }
    }

    private fun removePartner(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Retirer ce partenaire ?")
            .setPositiveButton("Retirer") { _, _ ->
                club.partners.remove(name)
                File(repo.dir(club), name).delete()
                repo.save(club)
                renderPartners()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun render() {
        val list = findViewById<LinearLayout>(R.id.slots)
        list.removeAllViews()
        for (slot in MediaSlot.entries) {
            val row = layoutInflater.inflate(R.layout.item_media, list, false)
            row.findViewById<TextView>(R.id.icon).text = when (slot) {
                MediaSlot.INTRO -> "▶️"
                MediaSlot.HALFTIME -> "⏸️"
                MediaSlot.OUTRO -> "🏁"
                MediaSlot.TRANSITION -> "✨"
                MediaSlot.MUSIC -> "🎵"
            }
            row.findViewById<TextView>(R.id.title).text = slot.label
            row.findViewById<TextView>(R.id.help).text = slot.help
            val file = repo.mediaFile(club, slot)
            val status = row.findViewById<TextView>(R.id.status)
            if (file != null) {
                status.text = "✔ ${club.media[slot]?.substringAfter("__") ?: file.name} · ${Ui.sizeText(file.length())}"
                status.setTextColor(getColor(R.color.sc_text))
            } else {
                status.text = when (slot) {
                    MediaSlot.TRANSITION -> "Aucune : l'écran change directement"
                    MediaSlot.MUSIC -> "Aucune : le son du micro est conservé"
                    else -> "Aucun : écran aux couleurs du club (aperçu ci-dessus)"
                }
                status.setTextColor(getColor(R.color.sc_muted))
            }
            row.findViewById<MaterialButton>(R.id.pick).apply {
                text = if (file != null) "Remplacer" else "Importer"
                setOnClickListener {
                    pendingSlot = slot
                    picker.launch(if (slot.isAudio) arrayOf("audio/*") else arrayOf("video/*"))
                }
            }
            row.findViewById<MaterialButton>(R.id.remove).apply {
                visibility = if (file != null) View.VISIBLE else View.GONE
                setOnClickListener { remove(slot) }
            }
            list.addView(row)
        }
    }

    private fun import(slot: MediaSlot, uri: Uri) {
        val original = Ui.displayName(this, uri)
        val ext = original.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 }
            ?: if (slot.isAudio) "m4a" else "mp4"
        // Nom du fichier : « intro__Mon intro.mp4 » (le nom d'origine sert à l'affichage)
        val stored = "${slot.id}__${original.substringBeforeLast('.').take(40)}.$ext"
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(this).apply {
            setMessage("Import de « $original »…")
            setCancelable(false)
            show()
        }
        thread {
            val dir = repo.dir(club)
            val result = runCatching {
                val dest = File(dir, stored)
                Ui.copyUri(this, uri, dest)
            }
            runOnUiThread {
                progress.dismiss()
                result.onSuccess {
                    val previous = club.media[slot]
                    if (previous != null && previous != stored) File(dir, previous).delete()
                    club.media[slot] = stored
                    repo.save(club)
                    render()
                }.onFailure {
                    Toast.makeText(this, "Import impossible : ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun remove(slot: MediaSlot) {
        AlertDialog.Builder(this)
            .setTitle("Retirer « ${slot.label} » ?")
            .setPositiveButton("Retirer") { _, _ ->
                club.media.remove(slot)?.let { File(repo.dir(club), it).delete() }
                repo.save(club)
                render()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
