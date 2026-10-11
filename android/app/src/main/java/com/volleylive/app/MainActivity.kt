package com.volleylive.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    /** [backupUrl] : 2e serveur essayé en alternance si le premier refuse la connexion. */
    private data class Platform(val label: String, val url: String?, val backupUrl: String? = null)

    private val platforms = listOf(
        // Serveur d'ingestion global recommandé par Twitch. RTMPS sur le port 443 en premier :
        // il passe aussi sur les Wi-Fi qui bloquent le port RTMP classique (1935).
        Platform(
            "Twitch",
            "rtmps://ingest.global-contribute.live-video.net:443/app",
            "rtmp://ingest.global-contribute.live-video.net/app",
        ),
        Platform("Serveur personnalisé", null),
        Platform("Pas de live (test overlay / télécommande)", ""),
    )

    private lateinit var prefs: Prefs
    private lateinit var platform: Spinner
    private lateinit var serverUrlLayout: TextInputLayout
    private lateinit var serverUrl: EditText
    private lateinit var streamKey: EditText
    private lateinit var homeName: EditText
    private lateinit var awayName: EditText
    private lateinit var competition: EditText
    private lateinit var quality1080: MaterialSwitch
    private lateinit var twitch: TwitchApi
    private lateinit var twitchSection: View
    private lateinit var twitchAutoTitle: MaterialSwitch
    private lateinit var twitchTitlePreview: TextView
    private lateinit var twitchStatus: TextView
    private lateinit var twitchClientIdLayout: View
    private lateinit var twitchClientId: EditText
    private lateinit var twitchConnect: Button
    @Volatile private var twitchLoginCancelled = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) openCamera()
            else Toast.makeText(this, "La caméra et le micro sont nécessaires pour filmer", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        platform = findViewById(R.id.platform)
        serverUrlLayout = findViewById(R.id.serverUrlLayout)
        serverUrl = findViewById(R.id.serverUrl)
        streamKey = findViewById(R.id.streamKey)
        homeName = findViewById(R.id.homeName)
        awayName = findViewById(R.id.awayName)
        competition = findViewById(R.id.competition)
        quality1080 = findViewById(R.id.quality1080)
        twitch = TwitchApi(prefs)
        twitchSection = findViewById(R.id.twitchSection)
        twitchAutoTitle = findViewById(R.id.twitchAutoTitle)
        twitchTitlePreview = findViewById(R.id.twitchTitlePreview)
        twitchStatus = findViewById(R.id.twitchStatus)
        twitchClientIdLayout = findViewById(R.id.twitchClientIdLayout)
        twitchClientId = findViewById(R.id.twitchClientId)
        twitchConnect = findViewById(R.id.twitchConnect)

        platform.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, platforms.map { it.label }
        )
        platform.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                serverUrlLayout.visibility = if (platforms[position].url == null) View.VISIBLE else View.GONE
                streamKey.isEnabled = platforms[position].url != ""
                twitchSection.visibility = if (isTwitch(position)) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        platform.setSelection(platforms.indexOfFirst { it.label == prefs.platform }.coerceAtLeast(0))
        serverUrl.setText(prefs.serverUrl)
        streamKey.setText(prefs.streamKey)
        homeName.setText(prefs.homeName)
        awayName.setText(prefs.awayName)
        competition.setText(prefs.competition)
        quality1080.isChecked = prefs.quality1080
        twitchAutoTitle.isChecked = prefs.twitchAutoTitle
        twitchAutoTitle.setOnCheckedChangeListener { _, checked -> prefs.twitchAutoTitle = checked }
        twitchClientId.setText(prefs.twitchClientId)
        twitchConnect.setOnClickListener { onTwitchConnectClicked() }
        val previewUpdater = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateTwitchTitlePreview()
        }
        listOf(homeName, awayName, competition).forEach { it.addTextChangedListener(previewUpdater) }
        updateTwitchTitlePreview()
        updateTwitchUi()

        findViewById<Button>(R.id.start).setOnClickListener { onStartClicked() }
    }

    private fun onStartClicked() {
        val selected = platforms[platform.selectedItemPosition]
        val key = cleanKey(streamKey.text.toString())
        val custom = serverUrl.text.toString().trim()

        if (selected.url == null && !custom.startsWith("rtmp")) {
            serverUrl.error = "L'URL doit commencer par rtmp:// ou rtmps://"
            return
        }
        if (!selected.url.isNullOrEmpty() && key.isEmpty()) {
            streamKey.error = "Clé de diffusion requise"
            return
        }

        prefs.platform = platforms[platform.selectedItemPosition].label
        prefs.serverUrl = custom
        prefs.streamKey = key
        prefs.homeName = homeName.text.toString().trim().ifEmpty { "DOMICILE" }
        prefs.awayName = awayName.text.toString().trim().ifEmpty { "VISITEUR" }
        prefs.competition = competition.text.toString().trim()
        prefs.quality1080 = quality1080.isChecked

        val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) openCamera() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun openCamera() {
        val selected = platforms[platform.selectedItemPosition]
        val key = prefs.streamKey
        val url = when {
            selected.url == "" -> ""
            selected.url == null -> if (key.isEmpty()) prefs.serverUrl else joinUrl(prefs.serverUrl, key)
            else -> joinUrl(selected.url, key)
        }
        val backupUrl = selected.backupUrl?.let { joinUrl(it, key) } ?: ""

        if (isTwitch(platform.selectedItemPosition) && prefs.twitchAutoTitle && twitch.isConnected) {
            updateTwitchTitle(TwitchApi.buildTitle(prefs.competition, prefs.homeName, prefs.awayName))
        }

        ScoreManager.init(
            ScoreState(homeName = prefs.homeName, awayName = prefs.awayName, competition = prefs.competition)
        )
        startActivity(
            Intent(this, StreamActivity::class.java)
                .putExtra(StreamActivity.EXTRA_URL, url)
                .putExtra(StreamActivity.EXTRA_BACKUP_URL, backupUrl)
                .putExtra(StreamActivity.EXTRA_1080P, prefs.quality1080)
        )
    }

    // ---- Twitch : titre du live ----

    private fun isTwitch(position: Int) = platforms[position].label == "Twitch"

    private fun updateTwitchTitlePreview() {
        val title = TwitchApi.buildTitle(
            competition.text.toString(),
            homeName.text.toString().ifBlank { "DOMICILE" },
            awayName.text.toString().ifBlank { "VISITEUR" },
        )
        twitchTitlePreview.text = "Titre : $title"
    }

    private fun updateTwitchUi() {
        if (twitch.isConnected) {
            twitchStatus.text = "✔ Compte Twitch connecté : ${prefs.twitchLogin}"
            twitchClientIdLayout.visibility = View.GONE
            twitchConnect.text = "Déconnecter le compte Twitch"
        } else {
            twitchStatus.text = "Compte Twitch non connecté : le titre ne sera pas modifié."
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
        prefs.twitchClientId = clientId
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
                    outcome.getOrNull() == true -> toast("Compte Twitch connecté : ${prefs.twitchLogin}")
                    outcome.isFailure -> toast("Twitch : ${outcome.exceptionOrNull()?.message}")
                    else -> toast("Délai dépassé, recommencez la connexion Twitch")
                }
            }
        }
    }

    private fun updateTwitchTitle(title: String) {
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

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun joinUrl(base: String, key: String) = base.trimEnd('/') + "/" + key

    /**
     * Nettoie une clé collée : supprime espaces et retours à la ligne, et si une adresse complète
     * a été collée (rtmp://.../app/live_xxx), ne garde que la clé à la fin.
     */
    private fun cleanKey(raw: String): String {
        val key = raw.filterNot { it.isWhitespace() }
        return if (key.contains("://")) key.substringAfterLast('/') else key
    }
}
