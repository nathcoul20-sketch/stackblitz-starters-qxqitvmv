package com.volleylive.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    /**
     * [url] vide : test hors ligne (pas de diffusion).
     * [backupUrl] : 2e serveur essayé en alternance si le premier refuse la connexion.
     */
    private data class Platform(val label: String, val url: String, val backupUrl: String? = null)

    private val platforms = listOf(
        // Serveur d'ingestion global recommandé par Twitch. RTMPS sur le port 443 en premier :
        // il passe aussi sur les Wi-Fi qui bloquent le port RTMP classique (1935).
        Platform(
            "Twitch",
            "rtmps://ingest.global-contribute.live-video.net:443/app",
            "rtmp://ingest.global-contribute.live-video.net/app",
        ),
        Platform("Test hors ligne", ""),
    )

    /** Choix en cours : 0 = Twitch, 1 = test hors ligne (deux grands boutons). */
    private var platformIndex = 0
    private lateinit var platformCards: List<MaterialCardView>
    private lateinit var streamKeyLayout: View

    private lateinit var prefs: Prefs
    private lateinit var streamKey: EditText
    private lateinit var homeName: EditText
    private lateinit var awayName: EditText
    private lateinit var competition: EditText
    private lateinit var partnersGrid: android.widget.GridLayout
    private lateinit var partnersInMatch: MaterialSwitch
    private lateinit var twitch: TwitchApi
    private lateinit var twitchSection: View
    private lateinit var twitchAutoTitle: MaterialSwitch
    private lateinit var twitchTitlePreview: TextView
    private lateinit var twitchStatus: TextView
    private lateinit var twitchClientIdLayout: View
    private lateinit var twitchClientId: EditText
    private lateinit var twitchConnect: Button
    @Volatile private var twitchLoginCancelled = false

    private val partnerPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) addPartner(uri)
    }

    /** Après l'autorisation caméra / micro : reprendre le match sauvegardé ou en commencer un nouveau. */
    private var resumeAfterPermission = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                if (resumeAfterPermission) resumeMatch() else openCamera()
            } else Toast.makeText(this, "La caméra et le micro sont nécessaires pour filmer", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fitAboveSystemBarsAndKeyboard()
        prefs = Prefs(this)

        streamKey = findViewById(R.id.streamKey)
        streamKeyLayout = findViewById(R.id.streamKeyLayout)
        homeName = findViewById(R.id.homeName)
        awayName = findViewById(R.id.awayName)
        competition = findViewById(R.id.competition)
        partnersGrid = findViewById(R.id.partners)
        partnersInMatch = findViewById(R.id.partnersInMatch)
        twitch = TwitchApi(prefs)
        twitchSection = findViewById(R.id.twitchSection)
        twitchAutoTitle = findViewById(R.id.twitchAutoTitle)
        twitchTitlePreview = findViewById(R.id.twitchTitlePreview)
        twitchStatus = findViewById(R.id.twitchStatus)
        twitchClientIdLayout = findViewById(R.id.twitchClientIdLayout)
        twitchClientId = findViewById(R.id.twitchClientId)
        twitchConnect = findViewById(R.id.twitchConnect)

        platformCards = listOf(findViewById(R.id.platformTwitch), findViewById(R.id.platformTest))
        platformCards.forEachIndexed { i, card -> card.setOnClickListener { selectPlatform(i) } }
        selectPlatform(if (prefs.platform == "Twitch" || prefs.platform == "Serveur personnalisé") 0 else 1)
        streamKey.setText(prefs.streamKey)
        homeName.setText(prefs.homeName)
        awayName.setText(prefs.awayName)
        competition.setText(prefs.competition)
        partnersInMatch.isChecked = prefs.partnersInMatch
        partnersInMatch.setOnCheckedChangeListener { _, checked -> prefs.partnersInMatch = checked }
        findViewById<Button>(R.id.addPartner).setOnClickListener {
            if (Partners.files(this).size >= Partners.MAX) toast("${Partners.MAX} logos maximum")
            else partnerPicker.launch("image/*")
        }
        renderPartners()
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
        findViewById<Button>(R.id.resume).setOnClickListener {
            resumeAfterPermission = true
            withPermissions { resumeMatch() }
        }
    }

    override fun onResume() {
        super.onResume()
        updateResumeButton()
    }

    // ---- Match en cours ----

    private fun updateResumeButton() {
        val button = findViewById<Button>(R.id.resume)
        val saved = MatchStore.load(this)
        if (saved == null) {
            button.visibility = View.GONE
            return
        }
        val st = saved.state
        button.text = "▶ Reprendre le match en cours\n${st.homeName} ${st.homeSets}-${st.awaySets} ${st.awayName} · set ${st.setNumber} (${st.homeScore}-${st.awayScore})"
        button.visibility = View.VISIBLE
    }

    /** Rouvre la caméra sur le match sauvegardé, avec le même live (plateforme, clé). */
    private fun resumeMatch() {
        val saved = MatchStore.load(this) ?: run {
            toast("Aucun match en cours")
            updateResumeButton()
            return
        }
        ScoreManager.init(saved.state)
        startActivity(
            Intent(this, StreamActivity::class.java)
                .putExtra(StreamActivity.EXTRA_URL, saved.session.url)
                .putExtra(StreamActivity.EXTRA_BACKUP_URL, saved.session.backupUrl)
        )
    }

    private fun withPermissions(action: () -> Unit) {
        val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else permissionLauncher.launch(missing.toTypedArray())
    }

    /** Sélectionne Twitch ou le test hors ligne : la clé et la section Twitch n'apparaissent que pour Twitch. */
    private fun selectPlatform(index: Int) {
        platformIndex = index
        val selectedStroke = ContextCompat.getColor(this, R.color.platform_selected)
        val idleStroke = ContextCompat.getColor(this, R.color.platform_idle)
        platformCards.forEachIndexed { i, card ->
            card.isChecked = i == index
            card.strokeColor = if (i == index) selectedStroke else idleStroke
        }
        val twitchSelected = isTwitch(index)
        streamKeyLayout.visibility = if (twitchSelected) View.VISIBLE else View.GONE
        twitchSection.visibility = if (twitchSelected) View.VISIBLE else View.GONE
    }

    private fun onStartClicked() {
        val selected = platforms[platformIndex]
        val key = cleanKey(streamKey.text.toString())
        if (selected.url.isNotEmpty() && key.isEmpty()) {
            streamKey.error = "Clé de diffusion requise"
            return
        }

        prefs.platform = selected.label
        if (key.isNotEmpty()) prefs.streamKey = key
        prefs.homeName = homeName.text.toString().trim().ifEmpty { "DOMICILE" }
        prefs.awayName = awayName.text.toString().trim().ifEmpty { "VISITEUR" }
        prefs.competition = competition.text.toString().trim()

        val start = {
            resumeAfterPermission = false
            withPermissions { openCamera() }
        }
        val saved = MatchStore.load(this)
        if (saved == null) {
            start()
        } else {
            val st = saved.state
            AlertDialog.Builder(this)
                .setTitle("Commencer un nouveau match ?")
                .setMessage("Le match en cours (${st.homeName} ${st.homeSets}-${st.awaySets} ${st.awayName}) sera remplacé et ne pourra plus être repris.")
                .setPositiveButton("Nouveau match") { _, _ -> start() }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    private fun openCamera() {
        val selected = platforms[platformIndex]
        val key = prefs.streamKey
        val url = if (selected.url.isEmpty()) "" else joinUrl(selected.url, key)
        val backupUrl = if (selected.url.isEmpty()) "" else selected.backupUrl?.let { joinUrl(it, key) } ?: ""

        if (isTwitch(platformIndex) && prefs.twitchAutoTitle && twitch.isConnected) {
            updateTwitchTitle(TwitchApi.buildTitle(prefs.competition, prefs.homeName, prefs.awayName))
        }

        ScoreManager.init(
            ScoreState(
                homeName = prefs.homeName,
                awayName = prefs.awayName,
                competition = prefs.competition,
                partnersVisible = prefs.partnersInMatch,
            )
        )
        startActivity(
            Intent(this, StreamActivity::class.java)
                .putExtra(StreamActivity.EXTRA_URL, url)
                .putExtra(StreamActivity.EXTRA_BACKUP_URL, backupUrl)
        )
    }

    // ---- Partenaires ----

    private fun renderPartners() {
        partnersGrid.removeAllViews()
        val gap = dp(8f)
        val cell = (resources.displayMetrics.widthPixels - dp(40f) - 3 * gap) / 3
        for (file in Partners.files(this)) {
            val tile = ImageView(this).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = cell
                    height = (cell * 0.6f).toInt()
                    setMargins(0, 0, gap, gap)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.WHITE)
                    cornerRadius = dp(10f).toFloat()
                }
                setPadding(gap, gap, gap, gap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull())
                contentDescription = "Logo partenaire (toucher pour retirer)"
                setOnClickListener { removePartner(file) }
            }
            partnersGrid.addView(tile)
        }
        partnersInMatch.visibility = if (Partners.files(this).isEmpty()) View.GONE else View.VISIBLE
    }

    private fun addPartner(uri: Uri) {
        thread {
            val ok = Partners.add(this, uri)
            runOnUiThread {
                if (!ok) toast("Image illisible")
                renderPartners()
            }
        }
    }

    private fun removePartner(file: java.io.File) {
        AlertDialog.Builder(this)
            .setTitle("Retirer ce partenaire ?")
            .setPositiveButton("Retirer") { _, _ ->
                Partners.remove(file)
                renderPartners()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

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

    /**
     * Android 15 affiche l'appli sous la barre d'état, la barre de navigation et le clavier.
     * On laisse donc la place nécessaire : les boutons du bas restent au-dessus des boutons
     * du téléphone, et la case en cours de saisie reste visible au-dessus du clavier.
     * (Même comportement sur toutes les versions d'Android.)
     */
    private fun fitAboveSystemBarsAndKeyboard() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            // Quand le clavier s'ouvre, on refait défiler jusqu'à la case en cours de saisie
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                currentFocus?.let { focused ->
                    view.post { focused.requestRectangleOnScreen(Rect(0, 0, focused.width, focused.height + 40)) }
                }
            }
            WindowInsetsCompat.CONSUMED
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
