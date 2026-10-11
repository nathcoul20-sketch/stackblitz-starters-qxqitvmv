package com.volleylive.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    /** [backupUrl] : 2e serveur essayé en alternance si le premier refuse la connexion. */
    private data class Platform(val label: String, val url: String?, val backupUrl: String? = null)

    private val platforms = listOf(
        Platform("YouTube", "rtmp://a.rtmp.youtube.com/live2", "rtmps://a.rtmps.youtube.com:443/live2"),
        Platform("Facebook", "rtmps://live-api-s.facebook.com:443/rtmp"),
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

        platform.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, platforms.map { it.label }
        )
        platform.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                serverUrlLayout.visibility = if (platforms[position].url == null) View.VISIBLE else View.GONE
                streamKey.isEnabled = platforms[position].url != ""
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        platform.setSelection(prefs.platform.coerceIn(0, platforms.lastIndex))
        serverUrl.setText(prefs.serverUrl)
        streamKey.setText(prefs.streamKey)
        homeName.setText(prefs.homeName)
        awayName.setText(prefs.awayName)
        competition.setText(prefs.competition)
        quality1080.isChecked = prefs.quality1080

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

        prefs.platform = platform.selectedItemPosition
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
