package com.scorecast.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.BatteryManager
import android.os.Bundle
import com.pedro.library.util.BitrateAdapter
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import java.util.Locale

class StreamActivity : AppCompatActivity(), ConnectChecker, SurfaceHolder.Callback {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_BACKUP_URL = "backupUrl"
        private const val MAX_RETRIES = 10
        /** Au-delà (°C de la batterie), on prévient que le téléphone chauffe. */
        private const val HOT_TEMP = 43f
        private val COLOR_OK = 0xFF20C07C.toInt()
        private val COLOR_WARN = 0xFFFF9800.toInt()
        private val COLOR_BAD = 0xFFE5484D.toInt()
        const val EXTRA_1080P = "1080p"
    }

    private lateinit var openGlView: OpenGlView
    private lateinit var camera: RtmpCamera2
    private lateinit var scenes: SceneController
    private lateinit var audioMixer: AudioMixer
    private lateinit var status: TextView
    private lateinit var liveButton: Button
    private lateinit var scorePanel: View
    private var clockButton: Button? = null
    private lateinit var endPeriodButton: Button
    private lateinit var muteButton: ImageButton
    private lateinit var qualityButton: Button
    private lateinit var deviceInfo: View
    private lateinit var batteryIcon: ImageView
    private lateinit var batteryBolt: ImageView
    private lateinit var batteryText: TextView
    private lateinit var tempIcon: ImageView
    private lateinit var tempText: TextView

    /** Qualité d'image : « Auto » s'adapte au réseau, les autres sont des débits fixes. */
    private enum class Quality(val label: String, val factor: Float) {
        AUTO("Auto", 1f), HIGH("Haute", 1f), MEDIUM("Moyenne", 0.6f), LOW("Basse", 0.35f)
    }
    private var quality = Quality.AUTO
    private var weakCount = 0
    private lateinit var bitrateAdapter: BitrateAdapter

    // Alertes batterie / chauffe : une seule fois par seuil
    private var warned15 = false
    private var warned5 = false
    private var warnedHot = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onBattery(intent)
    }

    private val sceneButtons = mutableMapOf<Scene, Button>()
    private var server: RemoteServer? = null
    private var facing = CameraHelper.Facing.BACK

    private var url = ""
    private var backupUrl = ""
    private var retryCount = 0
    private var videoWidth = 1280
    private var videoHeight = 720
    /** Débit maximal (qualité « Haute ») : 2,5 Mb/s en 720p, 4,5 Mb/s en 1080p. */
    private var videoBitrate = 2_500_000

    private val pin by lazy { AppPrefs(this).pin }

    private val scoreListener: (ScoreState) -> Unit = { state ->
        scenes.onState(state)
        sceneButtons.forEach { (scene, button) -> button.alpha = if (scene == state.scene) 1f else 0.55f }
        clockButton?.text = if (state.clockRunning) "❚❚" else "▶"
        endPeriodButton.text = shortEndLabel(state)
        muteButton.setImageResource(if (state.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
        muteButton.contentDescription = if (state.muted) "Rétablir le son du live" else "Couper le son du live"
        muteButton.backgroundTintList = ColorStateList.valueOf(
            if (state.muted) 0xFFE5484D.toInt() else 0xCC2A2C35.toInt()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_stream)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        url = intent.getStringExtra(EXTRA_URL) ?: ""
        backupUrl = intent.getStringExtra(EXTRA_BACKUP_URL) ?: ""
        var clubId = intent.getStringExtra(com.scorecast.app.EXTRA_CLUB_ID)
        var q1080 = intent.getBooleanExtra(EXTRA_1080P, false)
        // L'appli a été fermée puis relancée par Android directement sur cet écran : on reprend
        // le match sauvegardé (sinon le score repartirait de zéro)
        if (ScoreManager.version == 0L) {
            MatchStore.load(this)?.let { saved ->
                ScoreManager.init(saved.state)
                url = saved.session.url
                backupUrl = saved.session.backupUrl
                clubId = saved.session.clubId
                q1080 = saved.session.q1080
                toast("Match en cours repris")
            }
        }
        MatchStore.start(this, MatchStore.Session(clubId ?: "", url, backupUrl, q1080))
        if (q1080) {
            videoWidth = 1920
            videoHeight = 1080
            videoBitrate = 4_500_000
        }
        val repo = ClubRepository(this)
        val club = repo.get(clubId)
        val media = club?.let { c -> MediaSlot.entries.mapNotNull { slot -> repo.mediaFile(c, slot)?.let { slot to it } }.toMap() }
            ?: emptyMap()
        audioMixer = AudioMixer(media[MediaSlot.MUSIC])
        val logo = club?.let { repo.logo(it) }
        val partners = club?.let { repo.partnerLogos(it) } ?: emptyList()
        DeviceStatus.partnerCount = partners.size
        scenes = SceneController(
            OverlayRenderer(this, videoWidth, videoHeight, logo, partners), audioMixer, media,
            // Transition dessinée en demi-résolution (plus légère), étirée sur tout l'écran
            TransitionRenderer(this, videoWidth / 2, videoHeight / 2, logo),
        )
        bitrateAdapter = BitrateAdapter { bitrate -> camera.setVideoBitrateOnFly(bitrate) }
        bitrateAdapter.setMaxBitrate(videoBitrate)

        openGlView = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        liveButton = findViewById(R.id.live)
        scorePanel = findViewById(R.id.scorePanelBox)
        endPeriodButton = buildScorePanel(findViewById(R.id.scorePanel), ScoreManager.state.sport)

        camera = RtmpCamera2(openGlView, this)
        // Tout le son du live passe par le mixeur (musique / micro / bruitages)
        camera.setCustomAudioEffect(audioMixer)
        openGlView.holder.addCallback(this)

        deviceInfo = findViewById(R.id.deviceInfo)
        batteryIcon = findViewById(R.id.batteryIcon)
        batteryBolt = findViewById(R.id.batteryBolt)
        batteryText = findViewById(R.id.batteryText)
        tempIcon = findViewById(R.id.tempIcon)
        tempText = findViewById(R.id.tempText)
        qualityButton = findViewById(R.id.quality)
        qualityButton.setOnClickListener { chooseQuality() }
        updateQualityUi()
        muteButton = findViewById(R.id.mute)
        muteButton.setOnClickListener { ScoreManager.apply("toggle_mute") }
        liveButton.isEnabled = url.isNotEmpty()
        liveButton.setOnClickListener { toggleLive() }
        findViewById<Button>(R.id.remote).setOnClickListener { showRemoteDialog() }
        findViewById<Button>(R.id.scorePanelToggle).setOnClickListener {
            scorePanel.visibility = if (scorePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.switchCamera).setOnClickListener {
            try {
                camera.switchCamera()
                facing = if (facing == CameraHelper.Facing.BACK) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
            } catch (e: Exception) {
                toast("Impossible de changer de caméra")
            }
        }
        findViewById<Button>(R.id.sceneHalftime).text = ScoreManager.state.sport.breakLabel
        mapOf(
            Scene.INTRO to R.id.sceneIntro, Scene.MATCH to R.id.sceneMatch,
            Scene.HALFTIME to R.id.sceneHalftime, Scene.OUTRO to R.id.sceneOutro,
        ).forEach { (scene, id) ->
            val button = findViewById<Button>(id)
            sceneButtons[scene] = button
            button.setOnClickListener { ScoreManager.apply("scene_" + scene.name.lowercase()) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AlertDialog.Builder(this@StreamActivity)
                    .setTitle("Quitter ?")
                    .setMessage(
                        (if (camera.isStreaming) "Le live sera arrêté. " else "") +
                            "Le match sera terminé : il ne pourra plus être repris."
                    )
                    .setPositiveButton("Quitter") { _, _ ->
                        MatchStore.clear(this@StreamActivity)
                        finish()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        })

        startRemoteServer()
        ScoreManager.addListener(scoreListener)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { onBattery(it) }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(batteryReceiver) }
        DeviceStatus.live = false
        DeviceStatus.bitrate = 0
        ScoreManager.removeListener(scoreListener)
        scenes.release()
        audioMixer.release()
        server?.stop()
        if (camera.isStreaming) camera.stopStream()
        if (camera.isOnPreview) camera.stopPreview()
        super.onDestroy()
    }

    // ---- Caméra / aperçu ----

    private fun prepareEncoders(): Boolean {
        val rotation = CameraHelper.getCameraOrientation(this)
        return camera.prepareVideo(videoWidth, videoHeight, 30, currentBitrate(), rotation) &&
            camera.prepareAudio(128_000, 44_100, true)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (camera.isOnPreview) return
        if (!prepareEncoders()) {
            toast("Ce téléphone ne supporte pas la résolution demandée")
            return
        }
        camera.startPreview(facing)
        // Les calques (vidéos, overlay, transition) sont recréés à chaque démarrage de l'aperçu
        scenes.attach(camera.getGlInterface())
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (camera.isStreaming) {
            camera.stopStream()
            updateLiveUi()
        }
        scenes.detach()
        if (camera.isOnPreview) camera.stopPreview()
    }

    // ---- Live ----

    private fun toggleLive() {
        if (camera.isStreaming) {
            AlertDialog.Builder(this)
                .setTitle("Arrêter le live ?")
                .setPositiveButton("Arrêter") { _, _ ->
                    camera.stopStream()
                    updateLiveUi()
                }
                .setNegativeButton("Annuler", null)
                .show()
            return
        }
        if (!prepareEncoders()) {
            toast("Erreur de préparation de l'encodeur")
            return
        }
        retryCount = 0
        weakCount = 0
        bitrateAdapter.reset()
        camera.getStreamClient().setReTries(MAX_RETRIES)
        camera.startStream(url)
        status.text = "Connexion…"
        updateLiveUi()
    }

    private fun updateLiveUi() {
        liveButton.text = if (camera.isStreaming) "■ STOP" else "● LIVE"
        DeviceStatus.live = camera.isStreaming
        if (!camera.isStreaming) {
            status.text = "Hors ligne"
            status.backgroundTintList = null
            DeviceStatus.bitrate = 0
            DeviceStatus.weakNetwork = false
        }
    }

    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() = runOnUiThread {
        retryCount = 0
        camera.getStreamClient().setReTries(MAX_RETRIES)
        status.text = "● EN DIRECT"
        toast("Live démarré")
    }

    override fun onConnectionFailed(reason: String) = runOnUiThread {
        retryCount++
        // En alternance : serveur principal, puis serveur de secours (s'il existe)
        val next = if (backupUrl.isNotEmpty() && retryCount % 2 == 1) backupUrl else url
        if (camera.getStreamClient().reTry(5000, reason, next)) {
            status.text = "Reconnexion $retryCount/$MAX_RETRIES…\n$reason"
        } else {
            camera.stopStream()
            updateLiveUi()
            showConnectionError(reason)
        }
    }

    private fun showConnectionError(reason: String) {
        AlertDialog.Builder(this)
            .setTitle("Impossible de lancer le live")
            .setMessage(
                "Erreur : $reason\n\n" +
                    "À vérifier :\n" +
                    "• la clé de diffusion (copiez-la à nouveau depuis la plateforme, en entier) ;\n" +
                    "• la connexion internet du téléphone (essayez en 4G plutôt qu'en Wi-Fi) ;\n" +
                    "• que le compte a bien le droit de diffuser en direct."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onNewBitrate(bitrate: Long) = runOnUiThread {
        if (!camera.isStreaming) return@runOnUiThread
        val congestion = runCatching { camera.getStreamClient().hasCongestion() }.getOrDefault(false)
        // Qualité « Auto » : le débit baisse quand le réseau sature, puis remonte
        if (quality == Quality.AUTO) bitrateAdapter.adaptBitrate(bitrate, congestion)
        // Réseau faible : saturation, ou débit très en dessous de ce qui est demandé, plusieurs secondes de suite
        val target = currentBitrate()
        val weak = congestion || bitrate < target * (if (quality == Quality.AUTO) 0.4 else 0.6)
        weakCount = if (weak) weakCount + 1 else 0
        val isWeak = weakCount >= 3
        DeviceStatus.bitrate = bitrate
        DeviceStatus.weakNetwork = isWeak
        val mbps = String.format(Locale.FRANCE, "%.1f Mb/s", bitrate / 1_000_000.0)
        if (isWeak) {
            status.text = "⚠ RÉSEAU FAIBLE · $mbps" + if (quality == Quality.HIGH) "\nPassez en qualité Auto ou Basse" else ""
            status.backgroundTintList = ColorStateList.valueOf(0xE6C62828.toInt())
        } else {
            status.text = "● EN DIRECT · $mbps"
            status.backgroundTintList = null
        }
    }

    // ---- Qualité d'image ----

    private fun currentBitrate(): Int = (videoBitrate * quality.factor).toInt()

    private fun chooseQuality() {
        val labels = Quality.entries.map {
            when (it) {
                Quality.AUTO -> "Auto (s'adapte au réseau, conseillé)"
                else -> String.format(Locale.FRANCE, "%s (%.1f Mb/s)", it.label, videoBitrate * it.factor / 1_000_000.0)
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Qualité du live")
            .setSingleChoiceItems(labels, quality.ordinal) { dialog, which ->
                quality = Quality.entries[which]
                applyQuality()
                dialog.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Change le débit sans couper le live. */
    private fun applyQuality() {
        weakCount = 0
        bitrateAdapter.reset()
        bitrateAdapter.setMaxBitrate(videoBitrate)
        if (camera.isStreaming) runCatching { camera.setVideoBitrateOnFly(currentBitrate()) }
        updateQualityUi()
        toast("Qualité : ${quality.label}")
    }

    private fun updateQualityUi() {
        qualityButton.text = "Qualité : ${quality.label}"
        DeviceStatus.quality = quality.label
    }

    // ---- Batterie et chauffe ----

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level >= 0) level * 100 / scale else -1
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        DeviceStatus.battery = pct
        DeviceStatus.charging = plugged
        DeviceStatus.temperature = temp
        if (pct < 0) return
        val hot = temp >= HOT_TEMP
        // Petit indicateur en haut à droite : vert = OK, orange = à surveiller, rouge = alerte
        deviceInfo.visibility = View.VISIBLE
        batteryText.text = "$pct %"
        batteryBolt.visibility = if (plugged) View.VISIBLE else View.GONE
        batteryIcon.imageTintList = ColorStateList.valueOf(
            when {
                plugged || pct > 30 -> COLOR_OK
                pct > 15 -> COLOR_WARN
                else -> COLOR_BAD
            }
        )
        tempText.text = String.format(Locale.FRANCE, "%.0f °C", temp)
        tempIcon.imageTintList = ColorStateList.valueOf(
            when {
                hot -> COLOR_BAD
                temp >= HOT_TEMP - 5 -> COLOR_WARN
                else -> COLOR_OK
            }
        )
        if (plugged) {
            // En charge : les alertes pourront se redéclencher si on débranche plus tard
            if (pct > 15) warned15 = false
            if (pct > 5) warned5 = false
        } else {
            if (pct <= 5 && !warned5) {
                warned5 = true; warned15 = true
                alert("Batterie presque vide ($pct %)", "Le téléphone va bientôt s'éteindre. Branchez-le maintenant sur un chargeur ou une batterie externe.")
            } else if (pct <= 15 && !warned15) {
                warned15 = true
                alert("Batterie faible ($pct %)", "Branchez une batterie externe pour ne pas couper le live. Le match est sauvegardé en continu.")
            }
        }
        if (hot && !warnedHot) {
            warnedHot = true
            alert(
                "Le téléphone chauffe (${temp.toInt()} °C)",
                "Mettez-le à l'ombre, retirez sa coque si possible, et passez en qualité « Basse » ou « Moyenne ». " +
                    "Un téléphone trop chaud peut couper la caméra.",
            )
        } else if (temp < HOT_TEMP - 3) {
            warnedHot = false
        }
    }

    private fun alert(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    override fun onDisconnect() = runOnUiThread { updateLiveUi() }

    override fun onAuthError() = runOnUiThread {
        camera.stopStream()
        updateLiveUi()
        toast("Clé de diffusion refusée")
    }

    override fun onAuthSuccess() {}

    // ---- Score ----

    private fun confirmEndPeriod() {
        val s = ScoreManager.state
        if (s.sport == Sport.VOLLEY && s.homeScore == s.awayScore) {
            toast("Égalité : le set ne peut pas être attribué")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(s.endPeriodLabel + " ?")
            .setMessage("Score : ${s.homeName} ${s.homeScore} - ${s.awayScore} ${s.awayName}." +
                if (s.sport.hasClock) " Le chrono s'arrête." else "")
            .setPositiveButton("Valider") { _, _ -> ScoreManager.apply("end_period") }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /**
     * Panneau de score local (secours si pas de télécommande), adapté au sport.
     * Renvoie le bouton « fin de période ».
     */
    /** Libellé court du bouton de fin de période (barre de score compacte). */
    private fun shortEndLabel(s: ScoreState): String = when (s.sport) {
        Sport.VOLLEY -> "Fin set"
        Sport.BASKET -> if (s.period < 4) "Fin QT${s.period}" else "Fin match"
        Sport.FOOT -> when (s.period) {
            1 -> "Fin 1re MT"
            3 -> "Fin 1re prol."
            else -> "Fin match"
        }
        Sport.HAND -> if (s.period == 1) "Fin 1re MT" else "Fin match"
    }

    private fun buildScorePanel(panel: android.widget.LinearLayout, sport: Sport): Button {
        val state = ScoreManager.state
        fun add(label: String, color: Int? = null, onClick: () -> Unit): Button {
            val b = layoutInflater.inflate(R.layout.score_button, panel, false) as Button
            b.text = label
            if (color != null) {
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                b.setTextColor(Colors.onColor(color))
            }
            b.setOnClickListener { onClick() }
            panel.addView(b)
            return b
        }
        val home = state.homeColor
        val away = state.awayColor
        add("−") { ScoreManager.apply("home_minus") }
        if (sport == Sport.BASKET) {
            for (p in 1..3) add("+$p", home) { ScoreManager.apply("home_plus", p.toString()) }
        } else {
            add(if (sport == Sport.FOOT || sport == Sport.HAND) "But" else "Point", home) { ScoreManager.apply("home_plus") }
        }
        if (sport.hasClock) clockButton = add("▶") { ScoreManager.apply("clock_toggle") }.apply {
            contentDescription = "Démarrer ou arrêter le chrono"
        }
        val end = add(shortEndLabel(state)) { confirmEndPeriod() }
        if (sport == Sport.BASKET) {
            for (p in 1..3) add("+$p", away) { ScoreManager.apply("away_plus", p.toString()) }
        } else {
            add(if (sport == Sport.FOOT || sport == Sport.HAND) "But" else "Point", away) { ScoreManager.apply("away_plus") }
        }
        add("−") { ScoreManager.apply("away_minus") }
        return end
    }

    // ---- Télécommande ----

    private fun startRemoteServer() {
        server = try {
            RemoteServer(this, RemoteServer.DEFAULT_PORT, pin).also { it.start() }
        } catch (e: Exception) {
            toast("Serveur télécommande indisponible : ${e.message}")
            null
        }
    }

    private fun showRemoteDialog() {
        val ips = NetworkUtils.localIpAddresses()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_remote, null)
        val info = view.findViewById<TextView>(R.id.info)
        val qr = view.findViewById<ImageView>(R.id.qr)
        if (ips.isEmpty()) {
            qr.visibility = View.GONE
            info.text = "Aucun réseau local détecté.\n\nConnectez les deux téléphones au même Wi-Fi, " +
                "ou activez le partage de connexion (point d'accès) sur ce téléphone et connectez-y la télécommande."
        } else {
            val url = "http://${ips.first()}:${RemoteServer.DEFAULT_PORT}/?pin=$pin"
            qr.setImageBitmap(NetworkUtils.qrCode(url))
            info.text = buildString {
                append("Scannez le QR code avec le 2e téléphone, ou ouvrez dans son navigateur :\n\n")
                ips.forEach { append("http://$it:${RemoteServer.DEFAULT_PORT}\n") }
                append("\nCode PIN : $pin\n\n")
                append("Les deux téléphones doivent être sur le même Wi-Fi (ou la télécommande connectée au point d'accès de ce téléphone).")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Télécommande")
            .setView(view)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
