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

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val slot = pendingSlot ?: return@registerForActivityResult
        pendingSlot = null
        if (uri != null) import(slot, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media)
        repo = ClubRepository(this)
        club = repo.get(intent.getStringExtra(EXTRA_CLUB_ID)) ?: run { finish(); return }
        Ui.header(this, intent.getIntExtra(EXTRA_STEP, 2), "Écrans & vidéos", "Tout est facultatif : importez seulement ce que le club souhaite.")
        val logo = repo.logo(club)
        val sample = Previews.sampleState(AppPrefs(this).sport, club.name, club.primary, club.secondary)
        findViewById<ImageView>(R.id.generated).setImageBitmap(Previews.screen(this, sample, Scene.INTRO, logo))
        render()
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
