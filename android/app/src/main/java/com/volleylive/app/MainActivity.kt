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

    private data class Platform(val label: String, val url: String?)

    private val platforms = listOf(
        Platform("YouTube", "rtmp://a.rtmp.youtube.com/live2"),
        Platform("Facebook", "rtmps://live-api-s.facebook.com:443/rtmp"),
        Platform("Twitch", "rtmp://live.twitch.tv/app"),
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
        val key = streamKey.text.toString().trim()
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

        ScoreManager.init(
            ScoreState(homeName = prefs.homeName, awayName = prefs.awayName, competition = prefs.competition)
        )
        startActivity(
            Intent(this, StreamActivity::class.java)
                .putExtra(StreamActivity.EXTRA_URL, url)
                .putExtra(StreamActivity.EXTRA_1080P, prefs.quality1080)
        )
    }

    private fun joinUrl(base: String, key: String) = base.trimEnd('/') + "/" + key
}
