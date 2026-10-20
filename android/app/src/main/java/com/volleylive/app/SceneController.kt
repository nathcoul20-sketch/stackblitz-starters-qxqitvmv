package com.volleylive.app

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.library.view.GlInterface

/**
 * Gère ce qui est affiché par-dessus la caméra, sous forme de 3 calques OpenGL (du bas vers le haut) :
 *  1. vidéo plein écran (intro en boucle / écran de fin), invisible pendant le match ;
 *  2. overlay : tableau de score en match, résultat sur l'écran de fin ;
 *  3. transition ECVB (vidéo avec transparence), jouée à chaque changement de scène.
 *
 * Le changement de scène a lieu au milieu de la transition, quand elle couvre tout l'écran.
 * Gère aussi le son : musique (micro coupé) en intro / fin, micro en match, bruitage des transitions.
 * Toutes les méthodes publiques s'appellent depuis le thread principal.
 */
class SceneController(
    private val context: Context,
    private val renderer: OverlayRenderer,
    private val audio: AudioMixer,
) {

    private val main = Handler(Looper.getMainLooper())

    private var videoFilter: SurfaceFilterRender? = null
    private var overlayFilter: ImageObjectFilterRender? = null
    private var transitionFilter: AlphaVideoFilterRender? = null
    private var videoPlayer: MediaPlayer? = null
    private var transitionPlayer: MediaPlayer? = null
    private var transitionReady = false

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
    }

    fun detach() {
        main.removeCallbacksAndMessages(null)
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
        audio.playTransitionSound()
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
        main.postDelayed({ showScene(state.scene) }, CUT_MS)
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
            setVolume(0f, 0f) // le bruitage est envoyé dans le live par AudioMixer
            setOnPreparedListener { transitionReady = true }
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

    private fun showScene(scene: Scene) {
        shownScene = scene
        audio.musicOn = scene != Scene.MATCH
        when (scene) {
            Scene.INTRO -> playVideo(R.raw.intro, loop = true)
            Scene.OUTRO -> playVideo(R.raw.outro, loop = false) // s'arrête sur la dernière image
            Scene.MATCH -> {
                videoFilter?.alpha = 0f
                videoPlayer?.release()
                videoPlayer = null
            }
        }
        refreshOverlay()
    }

    private fun refreshOverlay() {
        overlayFilter?.setImage(
            when (shownScene) {
                Scene.MATCH -> renderer.renderScoreboard(state)
                Scene.OUTRO -> renderer.renderOutro(state)
                else -> renderer.empty()
            }
        )
    }

    /** La surface de la vidéo est prête (créée par le thread OpenGL) : on lance la vidéo de la scène. */
    private fun onVideoSurfaceReady() {
        when (shownScene) {
            Scene.INTRO -> playVideo(R.raw.intro, loop = true)
            Scene.OUTRO -> playVideo(R.raw.outro, loop = false)
            else -> {}
        }
    }

    private fun playVideo(resId: Int, loop: Boolean) {
        videoPlayer?.release()
        videoPlayer = null
        val filter = videoFilter ?: return
        val surface = filter.surface ?: return // sera relancé par onVideoSurfaceReady()
        videoPlayer = MediaPlayer().apply {
            context.resources.openRawResourceFd(resId).use { afd ->
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            setSurface(surface)
            isLooping = loop
            setVolume(0f, 0f)
            setOnPreparedListener {
                it.start()
                filter.alpha = 1f
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
    }
}
