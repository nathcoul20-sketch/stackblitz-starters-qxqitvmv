package com.scorecast.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Button
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
    private lateinit var muteButton: Button

    private val sceneButtons = mutableMapOf<Scene, Button>()
    private var server: RemoteServer? = null
    private var facing = CameraHelper.Facing.BACK

    private var url = ""
    private var backupUrl = ""
    private var retryCount = 0
    private var videoWidth = 1280
    private var videoHeight = 720
    private var videoBitrate = 2_500_000

    private val pin by lazy { AppPrefs(this).pin }

    private val scoreListener: (ScoreState) -> Unit = { state ->
        scenes.onState(state)
        sceneButtons.forEach { (scene, button) -> button.alpha = if (scene == state.scene) 1f else 0.55f }
        clockButton?.text = if (state.clockRunning) "❚❚ Chrono" else "▶ Chrono"
        endPeriodButton.text = state.endPeriodLabel
        muteButton.text = if (state.muted) "🔇 Son coupé" else "🔊 Son"
        muteButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
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
        if (intent.getBooleanExtra(EXTRA_1080P, false)) {
            videoWidth = 1920
            videoHeight = 1080
            videoBitrate = 4_500_000
        }
        val repo = ClubRepository(this)
        val club = repo.get(intent.getStringExtra(com.scorecast.app.EXTRA_CLUB_ID))
        val media = club?.let { c -> MediaSlot.entries.mapNotNull { slot -> repo.mediaFile(c, slot)?.let { slot to it } }.toMap() }
            ?: emptyMap()
        audioMixer = AudioMixer(media[MediaSlot.MUSIC])
        val logo = club?.let { repo.logo(it) }
        scenes = SceneController(OverlayRenderer(this, videoWidth, videoHeight, logo), audioMixer, media)

        openGlView = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        liveButton = findViewById(R.id.live)
        scorePanel = findViewById(R.id.scorePanelBox)
        endPeriodButton = buildScorePanel(findViewById(R.id.scorePanel), ScoreManager.state.sport)

        camera = RtmpCamera2(openGlView, this)
        // Tout le son du live passe par le mixeur (musique / micro / bruitages)
        camera.setCustomAudioEffect(audioMixer)
        openGlView.holder.addCallback(this)

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
                    .setMessage(if (camera.isStreaming) "Le live sera arrêté et le score remis à zéro." else "Le score sera remis à zéro.")
                    .setPositiveButton("Quitter") { _, _ -> finish() }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        })

        startRemoteServer()
        ScoreManager.addListener(scoreListener)
    }

    override fun onDestroy() {
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
        return camera.prepareVideo(videoWidth, videoHeight, 30, videoBitrate, rotation) &&
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
        camera.getStreamClient().setReTries(MAX_RETRIES)
        camera.startStream(url)
        status.text = "Connexion…"
        updateLiveUi()
    }

    private fun updateLiveUi() {
        liveButton.text = if (camera.isStreaming) "■ STOP" else "● LIVE"
        if (!camera.isStreaming) status.text = "Hors ligne"
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
        if (camera.isStreaming) {
            status.text = String.format(Locale.FRANCE, "● EN DIRECT · %.1f Mb/s", bitrate / 1_000_000.0)
        }
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
        if (sport.hasClock) clockButton = add("▶ Chrono") { ScoreManager.apply("clock_toggle") }
        add("Annuler") { ScoreManager.apply("undo") }
        val end = add(state.endPeriodLabel) { confirmEndPeriod() }
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
