package com.volleylive.app

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.library.view.GlInterface

/**
 * Gère ce qui est affiché par-dessus la caméra, sous forme de 3 calques OpenGL (du bas vers le haut) :
 *  1. vidéo plein écran (intro / écran de fin), invisible pendant le match. Chaque vidéo joue son
 *     animation d'entrée une fois, puis boucle sur sa seconde moitié (voir [LOOP_FROM_MS]) ;
 *  2. overlay : tableau de score en match, résultat sur l'écran de fin ;
 *  3. transition ECVB (vidéo avec transparence), jouée à chaque changement de scène.
 *
 * Le changement de scène a lieu au milieu de la transition, quand elle couvre tout l'écran.
 * Gère aussi le son : musique (micro coupé) en intro / fin, micro en match, avec des fondus
 * pendant les transitions (pas de bruitage).
 * Toutes les méthodes publiques s'appellent depuis le thread principal.
 */
class SceneController(
    private val context: Context,
    renderer: OverlayRenderer,
    private val audio: AudioMixer,
) {

    /** Remplacé quand la résolution du live change (bouton Qualité). */
    var renderer: OverlayRenderer = renderer
        set(value) {
            field = value
            refreshOverlay()
        }

    private val main = Handler(Looper.getMainLooper())

    /**
     * Appelé une fois, quand l'image de la scène est vraiment prête (1re image de la vidéo d'intro
     * affichée, ou tableau de score posé en match) : l'écran de chargement peut disparaître.
     */
    var onReady: (() -> Unit)? = null
    private fun ready() {
        val callback = onReady ?: return
        onReady = null
        main.post(callback)
    }

    /** Pendant le match : redessine le tableau quand le logo partenaire affiché doit changer. */
    private val ticker = Handler(Looper.getMainLooper())
    private var shownPartner = -1
    private val tick = object : Runnable {
        override fun run() {
            if (shownScene == Scene.MATCH &&
                renderer.partnerIndex(state, SystemClock.elapsedRealtime()) != shownPartner
            ) refreshOverlay()
            ticker.postDelayed(this, 500L)
        }
    }

    private var videoFilter: SurfaceFilterRender? = null
    private var overlayFilter: ImageObjectFilterRender? = null
    private var transitionFilter: AlphaVideoFilterRender? = null
    private var videoPlayer: MediaPlayer? = null
    private var transitionPlayer: MediaPlayer? = null
    private var transitionReady = false
    private var transitionDurationMs = DEFAULT_TRANSITION_MS

    private var state: ScoreState = ScoreManager.state
    private var shownScene: Scene? = null
    private var transitioning = false

    /** Installe les calques sur l'aperçu/flux. À rappeler à chaque redémarrage de l'aperçu. */
    fun attach(gl: GlInterface) {
        detach()
        val video = SurfaceFilterRender { onVideoSurfaceReady() }.apply { alpha = 0f }
        val overlay = ImageObjectFilterRender()
        val transition = AlphaVideoFilterRender { onTransitionSurfaceReady(it) }.apply { alpha = 0f }
        gl.clearFilters()
        listOf(video, overlay, transition).forEach {
            gl.addFilter(it)
            it.setScale(100f, 100f)
            it.setPosition(0f, 0f)
        }
        videoFilter = video
        overlayFilter = overlay
        transitionFilter = transition
        showScene(state.scene)
        ticker.removeCallbacksAndMessages(null)
        ticker.post(tick)
    }

    fun detach() {
        main.removeCallbacksAndMessages(null)
        ticker.removeCallbacksAndMessages(null)
        transitioning = false
        videoPlayer?.release()
        videoPlayer = null
        transitionPlayer?.release()
        transitionPlayer = null
        transitionReady = false
        videoFilter = null
        overlayFilter = null
        transitionFilter = null
        shownScene = null
    }

    fun onState(s: ScoreState) {
        state = s
        audio.muted = s.audioMuted
        if (overlayFilter == null) return
        if (!transitioning && s.scene != shownScene) startTransition() else refreshOverlay()
    }

    // ---- Transition ----

    private fun startTransition() {
        transitioning = true
        val player = transitionPlayer
        val filter = transitionFilter
        if (player == null || filter == null || !transitionReady) {
            // Transition indisponible : on change de scène directement
            finishTransition(cut = true)
            return
        }
        player.seekTo(0)
        player.start()
        // Le calque n'est rendu visible qu'après quelques images : les premières sont transparentes
        // et cela évite d'afficher une image vide le temps que la vidéo démarre.
        main.postDelayed({ filter.alpha = 1f }, SHOW_DELAY_MS)
        // Son : celui en cours s'éteint doucement jusqu'au changement d'écran, puis le nouveau remonte
        // progressivement pendant que les volets s'ouvrent
        audio.fadeOutFor(CUT_MS)
        val fadeIn = (transitionDurationMs - CUT_MS).coerceAtLeast(500L)
        main.postDelayed({ showScene(state.scene, fadeIn) }, CUT_MS)
        // Sécurité si la fin de la vidéo n'est jamais signalée
        main.postDelayed({ finishTransition(cut = false) }, TIMEOUT_MS)
    }

    private fun finishTransition(cut: Boolean) {
        if (!transitioning) return
        main.removeCallbacksAndMessages(null)
        if (cut || shownScene != state.scene) showScene(state.scene)
        transitionFilter?.alpha = 0f
        transitioning = false
        // La scène a encore changé pendant la transition : on enchaîne
        if (state.scene != shownScene) startTransition()
    }

    private fun onTransitionSurfaceReady(surface: Surface) {
        transitionPlayer?.release()
        transitionPlayer = null
        transitionReady = false
        // Surface d'un ancien calque (aperçu redémarré entre-temps) : ignorée
        if (!surface.isValid || transitionFilter == null) return
        transitionPlayer = MediaPlayer().apply {
            context.resources.openRawResourceFd(R.raw.transition).use { afd ->
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            setSurface(surface)
            setVolume(0f, 0f)
            setOnPreparedListener {
                transitionReady = true
                if (it.duration > 0) transitionDurationMs = it.duration.toLong()
            }
            setOnCompletionListener { finishTransition(cut = false) }
            setOnErrorListener { _, _, _ ->
                transitionReady = false
                finishTransition(cut = true)
                true
            }
            prepareAsync()
        }
    }

    // ---- Scènes ----

    /** Affiche [scene] ; avec [fadeInMs], son son remonte progressivement (fin de transition). */
    private fun showScene(scene: Scene, fadeInMs: Long? = null) {
        shownScene = scene
        val music = scene != Scene.MATCH
        if (fadeInMs != null) audio.fadeInFor(fadeInMs, music) else audio.crossfade(music)
        when (scene) {
            Scene.INTRO -> playVideo(R.raw.intro)
            Scene.OUTRO -> playVideo(R.raw.outro)
            Scene.MATCH -> {
                videoFilter?.alpha = 0f
                videoPlayer?.release()
                videoPlayer = null
                ready()
            }
        }
        refreshOverlay()
    }

    private fun refreshOverlay() {
        val now = SystemClock.elapsedRealtime()
        shownPartner = renderer.partnerIndex(state, now)
        overlayFilter?.setImage(
            when (shownScene) {
                Scene.MATCH -> renderer.renderScoreboard(state, now)
                Scene.OUTRO -> renderer.renderOutro(state)
                else -> renderer.empty()
            }
        )
    }

    /** La surface de la vidéo est prête (créée par le thread OpenGL) : on lance la vidéo de la scène. */
    private fun onVideoSurfaceReady() {
        when (shownScene) {
            Scene.INTRO -> playVideo(R.raw.intro)
            Scene.OUTRO -> playVideo(R.raw.outro)
            else -> {}
        }
    }

    private fun playVideo(resId: Int) {
        videoPlayer?.release()
        videoPlayer = null
        val filter = videoFilter ?: return
        val surface = filter.surface ?: return // sera relancé par onVideoSurfaceReady()
        videoPlayer = MediaPlayer().apply {
            context.resources.openRawResourceFd(resId).use { afd ->
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            setSurface(surface)
            setVolume(0f, 0f)
            setOnPreparedListener {
                it.start()
                filter.alpha = 1f
                // Au cas où la 1re image ne serait pas signalée
                ticker.postDelayed({ ready() }, 600L)
            }
            setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) ticker.postDelayed({ ready() }, 120L)
                false
            }
            // Fin de la vidéo : on repart du milieu (image clé), sans rejouer l'animation d'entrée.
            // Les chevrons animés ont un cycle de 4,5 s : la reprise ne se voit pas.
            setOnCompletionListener {
                runCatching {
                    it.seekTo(LOOP_FROM_MS, MediaPlayer.SEEK_PREVIOUS_SYNC)
                    it.start()
                }
            }
            prepareAsync()
        }
    }

    companion object {
        /** Durée avant d'afficher le calque de transition (images transparentes au début). */
        private const val SHOW_DELAY_MS = 80L
        /** Changement de scène : les volets couvrent tout l'écran entre 0,3 s et 1,1 s. */
        private const val CUT_MS = 730L
        private const val TIMEOUT_MS = 3000L
        /** Durée de la transition ECVB si elle n'est pas encore connue. */
        private const val DEFAULT_TRANSITION_MS = 1_700L
        /** Reprise des vidéos d'intro et de fin (image clé placée à 4,5 s à l'encodage). */
        private const val LOOP_FROM_MS = 4_500L
    }
}
