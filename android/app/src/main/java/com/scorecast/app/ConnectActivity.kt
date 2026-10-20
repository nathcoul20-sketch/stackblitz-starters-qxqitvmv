package com.scorecast.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.concurrent.thread

/** Étape 4 : connexion à la plateforme (clé de diffusion, serveur, compte Twitch). */
class ConnectActivity : AppCompatActivity() {

    private lateinit var repo: ClubRepository
    private lateinit var club: Club
    private lateinit var platform: Platform
    private lateinit var twitch: TwitchApi

    private lateinit var serverUrl: TextInputEditText
    private lateinit var streamKey: TextInputEditText
    private lateinit var twitchStatus: TextView
    private lateinit var twitchClientIdLayout: View
    private lateinit var twitchClientId: TextInputEditText
    private lateinit var twitchConnect: MaterialButton
    @Volatile private var twitchLoginCancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)
        repo = ClubRepository(this)
        club = repo.get(intent.getStringExtra(EXTRA_CLUB_ID)) ?: run { finish(); return }
        platform = Platform.fromId(club.platformId) ?: Platform.TWITCH
        twitch = TwitchApi(club, repo)

        Ui.header(
            this, 4,
            if (platform == Platform.NONE) "Mode test" else "Connexion à ${platform.label}",
            if (platform == Platform.NONE) "Rien ne sera diffusé : idéal pour essayer l'incrustation et la télécommande."
            else "Collez la clé de diffusion de la chaîne de ${club.name}. Elle reste enregistrée sur ce téléphone.",
        )

        serverUrl = findViewById(R.id.serverUrl)
        streamKey = findViewById(R.id.streamKey)
        twitchStatus = findViewById(R.id.twitchStatus)
        twitchClientIdLayout = findViewById(R.id.twitchClientIdLayout)
        twitchClientId = findViewById(R.id.twitchClientId)
        twitchConnect = findViewById(R.id.twitchConnect)

        findViewById<TextView>(R.id.keyHelp).text = platform.keyHelp
        findViewById<TextView>(R.id.note).apply {
            text = platform.note
            visibility = if (platform.note.isBlank()) View.GONE else View.VISIBLE
        }
        if (platform == Platform.NONE) {
            findViewById<View>(R.id.helpBox).visibility = if (platform.note.isBlank()) View.GONE else View.VISIBLE
            findViewById<TextView>(R.id.keyHelp).visibility = View.GONE
            findViewById<View>(R.id.quality1080).visibility = View.GONE
        }
        findViewById<View>(R.id.serverUrlLayout).visibility = if (platform.needsServerUrl) View.VISIBLE else View.GONE
        findViewById<View>(R.id.streamKeyLayout).visibility = if (platform.needsKey) View.VISIBLE else View.GONE
        serverUrl.setText(club.serverUrls[platform.id] ?: "")
        streamKey.setText(club.streamKeys[platform.id] ?: "")

        val quality = findViewById<MaterialSwitch>(R.id.quality1080)
        quality.isChecked = club.quality1080

        val isTwitch = platform == Platform.TWITCH
        findViewById<View>(R.id.twitchSection).visibility = if (isTwitch) View.VISIBLE else View.GONE
        if (isTwitch) {
            twitchClientId.setText(club.twitchClientId)
            findViewById<MaterialSwitch>(R.id.twitchAutoTitle).apply {
                isChecked = club.twitchAutoTitle
                setOnCheckedChangeListener { _, checked -> club.twitchAutoTitle = checked; repo.save(club) }
            }
            twitchConnect.setOnClickListener { onTwitchConnectClicked() }
            updateTwitchUi()
        }

        findViewById<View>(R.id.next).setOnClickListener {
            if (!validateAndSave(quality.isChecked)) return@setOnClickListener
            startActivity(Intent(this, MatchActivity::class.java).putExtra(EXTRA_CLUB_ID, club.id))
        }
    }

    private fun validateAndSave(q1080: Boolean): Boolean {
        val url = serverUrl.text.toString().trim()
        val key = cleanKey(streamKey.text.toString())
        if (platform.needsServerUrl && !url.startsWith("rtmp://") && !url.startsWith("rtmps://")) {
            findViewById<TextInputLayout>(R.id.serverUrlLayout).error = "L'adresse doit commencer par rtmp:// ou rtmps://"
            return false
        }
        // Serveur personnalisé : la clé peut déjà être incluse dans l'adresse
        if (platform.needsKey && key.isEmpty() && !(platform.needsServerUrl && platform == Platform.CUSTOM)) {
            findViewById<TextInputLayout>(R.id.streamKeyLayout).error = "Clé de diffusion requise"
            return false
        }
        findViewById<TextInputLayout>(R.id.serverUrlLayout).error = null
        findViewById<TextInputLayout>(R.id.streamKeyLayout).error = null
        if (platform.needsServerUrl) club.serverUrls[platform.id] = url
        if (platform.needsKey) club.streamKeys[platform.id] = key
        club.quality1080 = q1080
        repo.save(club)
        return true
    }

    /**
     * Nettoie une clé collée : supprime espaces et retours à la ligne, et si une adresse complète
     * a été collée (rtmp://.../app/live_xxx), ne garde que la clé à la fin.
     */
    private fun cleanKey(raw: String): String {
        val key = raw.filterNot { it.isWhitespace() }
        return if (key.contains("://")) key.substringAfterLast('/') else key
    }

    // ---- Twitch : titre du live ----

    private fun updateTwitchUi() {
        if (twitch.isConnected) {
            twitchStatus.text = "✔ Compte connecté : ${club.twitchLogin}"
            twitchClientIdLayout.visibility = View.GONE
            twitchConnect.text = "Déconnecter le compte Twitch"
        } else {
            twitchStatus.text = "Non connecté : le live fonctionne, mais le titre de la chaîne ne sera pas modifié."
            twitchClientIdLayout.visibility = View.VISIBLE
            twitchConnect.text = "Connecter mon compte Twitch"
        }
    }

    private fun onTwitchConnectClicked() {
        if (twitch.isConnected) {
            AlertDialog.Builder(this)
                .setTitle("Déconnecter Twitch ?")
                .setPositiveButton("Déconnecter") { _, _ ->
                    twitch.disconnect()
                    updateTwitchUi()
                }
                .setNegativeButton("Annuler", null)
                .show()
            return
        }
        val clientId = twitchClientId.text.toString().trim()
        if (clientId.isEmpty()) {
            twitchClientId.error = "Collez ici le Client ID de votre application Twitch"
            return
        }
        club.twitchClientId = clientId
        repo.save(club)
        twitchConnect.isEnabled = false
        thread {
            val result = runCatching { twitch.startDeviceLogin() }
            runOnUiThread {
                twitchConnect.isEnabled = true
                result.onSuccess { showTwitchLoginDialog(it) }
                    .onFailure { toast("Twitch : ${it.message}") }
            }
        }
    }

    /** Affiche le code à valider sur twitch.tv/activate et attend que l'utilisateur l'ait accepté. */
    private fun showTwitchLoginDialog(code: TwitchApi.DeviceCode) {
        twitchLoginCancelled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Connexion à Twitch")
            .setMessage(
                "1. Touchez « Ouvrir Twitch » et connectez-vous avec le compte de la chaîne.\n" +
                    "2. Vérifiez que le code affiché est : ${code.userCode}\n" +
                    "3. Touchez « Autoriser », puis revenez dans l'appli.\n\n" +
                    "En attente de l'autorisation…"
            )
            .setPositiveButton("Ouvrir Twitch", null)
            .setNegativeButton("Annuler") { _, _ -> twitchLoginCancelled = true }
            .setCancelable(false)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri)))
        }
        thread {
            val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
            var outcome: Result<Boolean> = Result.success(false)
            while (!twitchLoginCancelled && System.currentTimeMillis() < deadline) {
                Thread.sleep(code.intervalSeconds * 1000L)
                if (twitchLoginCancelled) break
                outcome = runCatching { twitch.pollDeviceLogin(code) }
                if (outcome.isFailure || outcome.getOrNull() == true) break
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (dialog.isShowing) dialog.dismiss()
                updateTwitchUi()
                when {
                    twitchLoginCancelled -> {}
                    outcome.getOrNull() == true -> toast("Compte Twitch connecté : ${club.twitchLogin}")
                    outcome.isFailure -> toast("Twitch : ${outcome.exceptionOrNull()?.message}")
                    else -> toast("Délai dépassé, recommencez la connexion Twitch")
                }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
