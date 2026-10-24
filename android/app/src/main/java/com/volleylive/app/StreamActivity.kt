package com.volleylive.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
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
import com.pedro.library.util.BitrateAdapter
import com.pedro.library.view.OpenGlView
import java.util.Locale

class StreamActivity : AppCompatActivity(), ConnectChecker, SurfaceHolder.Callback {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_BACKUP_URL = "backupUrl"
        private const val MAX_RETRIES = 10
        /** Au-delà, le téléphone chauffe (température de la batterie, °C). */
        private const val HOT_TEMP = 43f
        private val COLOR_OK = 0xFF20C07C.toInt()
        private val COLOR_WARN = 0xFFFF9800.toInt()
        private val COLOR_BAD = 0xFFE5484D.toInt()
    }

    /**
     * Qualité du live, choisie sur cet écran (bouton « Qualité »).
     * « Auto » : 720p, le débit baisse quand le réseau sature puis remonte. Les autres sont fixes.
     */
    private enum class Quality(val label: String, val width: Int, val height: Int, val bitrate: Int, val detail: String) {
        AUTO("Auto", 1280, 720, 2_500_000, "720p, s'adapte au réseau (conseillé)"),
        HIGH("Haute", 1920, 1080, 4_500_000, "1080p · 4,5 Mb/s, bon Wi-Fi ou très bonne 4G"),
        MEDIUM("Moyenne", 1280, 720, 2_500_000, "720p · 2,5 Mb/s"),
        LOW("Basse", 1280, 720, 1_200_000, "720p · 1,2 Mb/s, réseau faible"),
    }

    private lateinit var openGlView: OpenGlView
    private lateinit var camera: RtmpCamera2
    private lateinit var scenes: SceneController
    private lateinit var audioMixer: AudioMixer
    private lateinit var status: TextView
    private lateinit var liveButton: Button
    private lateinit var scorePanel: View

    private val sceneButtons = mutableMapOf<Scene, Button>()
    private lateinit var muteButton: ImageButton
    private lateinit var qualityButton: Button
    private lateinit var bitrateAdapter: BitrateAdapter
    private lateinit var prefs: Prefs
    private var quality = Quality.AUTO
    private var weakCount = 0
    private var partners: List<android.graphics.Bitmap> = emptyList()

    // Batterie et température (petit indicateur en haut à droite, alertes une fois par seuil)
    private lateinit var deviceInfo: View
    private lateinit var batteryIcon: ImageView
    private lateinit var batteryBolt: ImageView
    private lateinit var batteryText: TextView
    private lateinit var tempIcon: ImageView
    private lateinit var tempText: TextView
    private var warned15 = false
    private var warned5 = false
    private var warnedHot = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onBattery(intent)
    }
    private var server: RemoteServer? = null
    private var facing = CameraHelper.Facing.BACK

    private var url = ""
    private var backupUrl = ""
    private var retryCount = 0
    /** Résolution de l'aperçu et du live en cours (celle de la qualité choisie, appliquée hors live). */
    private var videoWidth = 1280
    private var videoHeight = 720

    private val pin by lazy { prefs.pin }

    private val scoreListener: (ScoreState) -> Unit = { state ->
        scenes.onState(state)
        sceneButtons.forEach { (scene, button) -> button.alpha = if (scene == state.scene) 1f else 0.55f }
        muteButton.setImageResource(if (state.audioMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
        muteButton.contentDescription = if (state.audioMuted) "Rétablir le son du live" else "Couper le son du live"
        muteButton.backgroundTintList = ColorStateList.valueOf(
            if (state.audioMuted) Color.parseColor("#D00A0A") else Color.parseColor("#CC333333")
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
        // L'appli a été fermée puis relancée par Android directement sur cet écran : on reprend
        // le match sauvegardé (sinon le score repartirait de zéro)
        if (ScoreManager.version == 0L) {
            MatchStore.load(this)?.let { saved ->
                ScoreManager.init(saved.state)
                url = saved.session.url
                backupUrl = saved.session.backupUrl
                toast("Match en cours repris")
            }
        }
        MatchStore.start(this, MatchStore.Session(url, backupUrl))
        prefs = Prefs(this)
        quality = runCatching { Quality.valueOf(prefs.quality) }.getOrDefault(Quality.AUTO)
        videoWidth = quality.width
        videoHeight = quality.height
        partners = Partners.logos(this)
        audioMixer = AudioMixer(this)
        scenes = SceneController(this, OverlayRenderer(this, videoWidth, videoHeight, partners), audioMixer)
        bitrateAdapter = BitrateAdapter { bitrate -> runCatching { camera.setVideoBitrateOnFly(bitrate) } }
        bitrateAdapter.setMaxBitrate(quality.bitrate)

        openGlView = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        liveButton = findViewById(R.id.live)
        scorePanel = findViewById(R.id.scorePanel)

        camera = RtmpCamera2(openGlView, this)
        // Tout le son du live passe par le mixeur (musique / micro / bruitages)
        camera.setCustomAudioEffect(audioMixer)
        openGlView.holder.addCallback(this)

        liveButton.isEnabled = url.isNotEmpty()
        liveButton.setOnClickListener { toggleLive() }
        findViewById<Button>(R.id.remote).setOnClickListener { showRemoteDialog() }
        findViewById<Button>(R.id.scorePanelToggle).setOnClickListener {
            scorePanel.visibility = if (scorePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        muteButton = findViewById(R.id.mute)
        muteButton.setOnClickListener { ScoreManager.apply("toggle_mute") }
        qualityButton = findViewById(R.id.quality)
        qualityButton.setOnClickListener { chooseQuality() }
        updateQualityUi()
        findViewById<Button>(R.id.switchCamera).setOnClickListener {
            try {
                camera.switchCamera()
                facing = if (facing == CameraHelper.Facing.BACK) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
            } catch (e: Exception) {
                toast("Impossible de changer de caméra")
            }
        }
        mapOf(
            R.id.homePlus to "home_plus", R.id.homeMinus to "home_minus",
            R.id.awayPlus to "away_plus", R.id.awayMinus to "away_minus",
        ).forEach { (id, action) -> findViewById<Button>(id).setOnClickListener { ScoreManager.apply(action) } }
        findViewById<Button>(R.id.endSet).setOnClickListener { confirmEndSet() }
        mapOf(
            Scene.INTRO to R.id.sceneIntro, Scene.MATCH to R.id.sceneMatch, Scene.OUTRO to R.id.sceneOutro,
        ).forEach { (scene, id) ->
            val button = findViewById<Button>(id)
            sceneButtons[scene] = button
            button.setOnClickListener { ScoreManager.apply("scene_" + scene.name.lowercase()) }
        }

        deviceInfo = findViewById(R.id.deviceInfo)
        batteryIcon = findViewById(R.id.batteryIcon)
        batteryBolt = findViewById(R.id.batteryBolt)
        batteryText = findViewById(R.id.batteryText)
        tempIcon = findViewById(R.id.tempIcon)
        tempText = findViewById(R.id.tempText)

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
        DeviceStatus.weakNetwork = false
        ScoreManager.removeListener(scoreListener)
        scenes.detach()
        audioMixer.release()
        server?.stop()
        if (camera.isStreaming) camera.stopStream()
        if (camera.isOnPreview) camera.stopPreview()
        super.onDestroy()
    }

    // ---- Caméra / aperçu ----

    private fun prepareEncoders(): Boolean {
        val rotation = CameraHelper.getCameraOrientation(this)
        return camera.prepareVideo(videoWidth, videoHeight, 30, quality.bitrate, rotation) &&
            camera.prepareAudio(128_000, 44_100, true)
    }

    /**
     * Applique la résolution de la qualité choisie (720p ou 1080p) : l'aperçu redémarre avec la
     * nouvelle taille et l'incrustation est redessinée à cette taille. Uniquement hors live.
     */
    private fun applyResolution() {
        if (camera.isStreaming) return
        if (videoWidth == quality.width && videoHeight == quality.height) return
        videoWidth = quality.width
        videoHeight = quality.height
        scenes.renderer = OverlayRenderer(this, videoWidth, videoHeight, partners)
        if (!camera.isOnPreview) return // l'aperçu démarrera directement à la bonne taille
        scenes.detach()
        camera.stopPreview()
        if (!prepareEncoders()) {
            toast("Ce téléphone ne supporte pas cette résolution")
            return
        }
        camera.startPreview(facing)
        scenes.attach(camera.getGlInterface())
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
        applyResolution() // qualité changée pendant le live précédent : appliquée maintenant
        if (!prepareEncoders()) {
            toast("Erreur de préparation de l'encodeur")
            return
        }
        retryCount = 0
        weakCount = 0
        bitrateAdapter.reset()
        bitrateAdapter.setMaxBitrate(quality.bitrate)
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
        val weak = congestion || bitrate < quality.bitrate * (if (quality == Quality.AUTO) 0.4 else 0.6)
        weakCount = if (weak) weakCount + 1 else 0
        DeviceStatus.bitrate = bitrate
        DeviceStatus.weakNetwork = weakCount >= 3
        val mbps = String.format(Locale.FRANCE, "%.1f Mb/s", bitrate / 1_000_000.0)
        if (weakCount >= 3) {
            status.text = "⚠ RÉSEAU FAIBLE · $mbps" + if (quality == Quality.AUTO || quality == Quality.LOW) "" else "\nPassez en qualité Auto ou Basse"
            status.backgroundTintList = ColorStateList.valueOf(0xE6C62828.toInt())
        } else {
            status.text = "● EN DIRECT · $mbps"
            status.backgroundTintList = null
        }
    }

    // ---- Qualité du live ----

    private fun chooseQuality() {
        val labels = Quality.entries.map { "${it.label} — ${it.detail}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Qualité du live")
            .setSingleChoiceItems(labels, quality.ordinal) { dialog, which ->
                dialog.dismiss()
                setQuality(Quality.entries[which])
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun setQuality(q: Quality) {
        val resolutionChanges = q.width != videoWidth || q.height != videoHeight
        quality = q
        prefs.quality = q.name
        weakCount = 0
        bitrateAdapter.reset()
        bitrateAdapter.setMaxBitrate(q.bitrate)
        updateQualityUi()
        if (camera.isStreaming) {
            // Pendant le live, seul le débit change (sans coupure) ; la résolution suivra au prochain live
            runCatching { camera.setVideoBitrateOnFly(q.bitrate) }
            toast(
                if (resolutionChanges) "Qualité ${q.label} : débit modifié. Image en ${q.height}p au prochain lancement du live."
                else "Qualité : ${q.label}"
            )
        } else {
            applyResolution()
            toast("Qualité : ${q.label} (${q.height}p)")
        }
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

    private fun confirmEndSet() {
        val s = ScoreManager.state
        if (s.homeScore == s.awayScore) {
            toast("Égalité : impossible d'attribuer le set")
            return
        }
        val winner = if (s.homeScore > s.awayScore) s.homeName else s.awayName
        AlertDialog.Builder(this)
            .setTitle("Fin du set ${s.setNumber}")
            .setMessage("Set gagné par $winner (${s.homeScore}-${s.awayScore}) ?")
            .setPositiveButton("Valider") { _, _ -> ScoreManager.apply("end_set") }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---- Télécommande ----

    private fun startRemoteServer() {
        server = try {
            RemoteServer(this, RemoteServer.DEFAULT_PORT, pin, partners.size).also { it.start() }
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
