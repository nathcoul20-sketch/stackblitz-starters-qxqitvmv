package com.scorecast.app

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.library.view.GlInterface
import java.io.File

/**
 * Gère ce qui est affiché par-dessus la caméra, sous forme de 3 calques OpenGL (du bas vers le haut) :
 *  1. vidéo plein écran importée par le club (intro, pause, fin : en boucle), invisible pendant le match ;
 *  2. overlay : tableau de score en match ; sur les autres écrans, écran généré aux couleurs du club
 *     (si aucune vidéo n'a été importée) ou cartouche de résultat posé sur la vidéo ;
 *  3. transition importée (vidéo plein écran), jouée à chaque changement d'écran ;
 *  4. sinon, transition générée aux couleurs du club ([TransitionRenderer]) : volets + logo.
 *
 * Le changement de scène a lieu quand la transition couvre tout l'écran. Le son suit : le son en cours
 * s'atténue jusqu'à ce moment-là, puis celui de la nouvelle scène remonte pendant la fin de la transition.
 * Toutes les méthodes publiques s'appellent depuis le thread principal.
 */
class SceneController(
    private val renderer: OverlayRenderer,
    private val audio: AudioMixer,
    /** Vidéos importées par le club (absentes : écran généré / transition générée). */
    private val media: Map<MediaSlot, File>,
    /** Transition aux couleurs du club, utilisée si le club n'a pas importé de transition. */
    private val generated: TransitionRenderer?,
) {

    private val main = Handler(Looper.getMainLooper())
    /** Horloge du chrono, séparée de [main] (dont les messages sont vidés à chaque transition). */
    private val ticker = Handler(Looper.getMainLooper())
    private var lastSignature = ""
    private val tick = object : Runnable {
        override fun run() {
            if (shownScene == Scene.MATCH && state.overlayVisible &&
                renderer.scoreboardSignature(state, ScoreManager.now()) != lastSignature
            ) refreshOverlay()
            ticker.postDelayed(this, TICK_MS)
        }
    }

    private var videoFilter: SurfaceFilterRender? = null
    private var overlayFilter: ImageObjectFilterRender? = null
    private var transitionFilter: SurfaceFilterRender? = null
    private var generatedFilter: ImageObjectFilterRender? = null
    /** Thread de dessin de la transition générée, et numéro de l'animation en cours (0 = aucune). */
    private var animThread: HandlerThread? = null
    private var animHandler: Handler? = null
    @Volatile private var animId = 0
    private var videoPlayer: MediaPlayer? = null
    private var transitionPlayer: MediaPlayer? = null
    private var transitionReady = false
    private var transitionDurationMs = 0

    private var state: ScoreState = ScoreManager.state
    private var shownScene: Scene? = null
    private var transitioning = false

    /** Vidéos que le téléphone n'a pas su lire : remplacées par l'écran généré. */
    private val broken = mutableSetOf<File>()

    private fun videoFor(scene: Scene): File? = when (scene) {
        Scene.INTRO -> media[MediaSlot.INTRO]
        Scene.HALFTIME -> media[MediaSlot.HALFTIME]
        Scene.OUTRO -> media[MediaSlot.OUTRO]
        Scene.MATCH -> null
    }?.takeIf { it !in broken }

    /** Installe les calques sur l'aperçu/flux. À rappeler à chaque redémarrage de l'aperçu. */
    fun attach(gl: GlInterface) {
        detach()
        val video = SurfaceFilterRender { onVideoSurfaceReady() }.apply { alpha = 0f }
        val overlay = ImageObjectFilterRender()
        val transition = SurfaceFilterRender { onTransitionSurfaceReady() }.apply { alpha = 0f }
        val gen = ImageObjectFilterRender().apply { alpha = 0f }
        gl.clearFilters()
        listOf(video, overlay, transition, gen).forEach {
            gl.addFilter(it)
            it.setScale(100f, 100f)
            it.setPosition(0f, 0f)
        }
        videoFilter = video
        overlayFilter = overlay
        transitionFilter = transition
        generatedFilter = gen
        if (generated != null && animThread == null) {
            animThread = HandlerThread("transition").also { it.start() }
            animHandler = Handler(animThread!!.looper)
        }
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
        animId = 0
        animHandler?.removeCallbacksAndMessages(null)
        generatedFilter = null
        shownScene = null
    }

    /** Arrêt définitif (fin de l'activité). */
    fun release() {
        detach()
        animThread?.quitSafely()
        animThread = null
        animHandler = null
    }

    fun onState(s: ScoreState) {
        state = s
        audio.muted = s.muted
        if (overlayFilter == null) return
        if (!transitioning && s.scene != shownScene) startTransition() else refreshOverlay()
    }

    // ---- Transition ----

    private fun startTransition() {
        val player = transitionPlayer
        val filter = transitionFilter
        if (player == null || filter == null || !transitionReady) {
            // Pas de transition importée (ou illisible) : transition aux couleurs du club
            startGeneratedTransition()
            return
        }
        transitioning = true
        player.seekTo(0)
        player.start()
        // Calque rendu visible après quelques images, le temps que la vidéo démarre réellement
        main.postDelayed({ filter.alpha = 1f }, SHOW_DELAY_MS)
        val cut = (transitionDurationMs / 2L).coerceIn(150L, 3_000L)
        audio.fadeOutFor(cut)
        val fadeIn = (transitionDurationMs - cut).coerceAtLeast(400L)
        main.postDelayed({ showScene(state.scene, fadeIn) }, cut)
        // Sécurité si la fin de la vidéo n'est jamais signalée
        main.postDelayed({ finishTransition() }, maxOf(transitionDurationMs.toLong(), cut) + 1_500L)
    }

    /** Volets + logo aux couleurs du club, dessinés image par image sur un thread dédié. */
    private fun startGeneratedTransition() {
        val renderer = generated
        val handler = animHandler
        val filter = generatedFilter
        if (renderer == null || handler == null || filter == null) {
            showScene(state.scene)
            return
        }
        transitioning = true
        val id = ++animId
        val colors = state
        val name = colors.homeName
        // 3 bitmaps utilisés à tour de rôle : le calque envoie l'image au GPU pendant qu'on dessine la suivante
        val pool = List(3) { Bitmap.createBitmap(renderer.width, renderer.height, Bitmap.Config.ARGB_8888) }
        var n = 0
        val start = SystemClock.uptimeMillis()
        audio.fadeOutFor(TransitionRenderer.CUT_MS)
        val cutAt = TransitionRenderer.CUT_MS
        main.postDelayed({ if (animId == id) showScene(state.scene, TransitionRenderer.DURATION_MS - cutAt) }, cutAt)
        handler.post(object : Runnable {
            override fun run() {
                if (animId != id) return
                val elapsed = SystemClock.uptimeMillis() - start
                if (elapsed >= TransitionRenderer.DURATION_MS) {
                    main.post { if (animId == id) finishTransition() }
                    return
                }
                val bmp = pool[n++ % pool.size]
                renderer.draw(bmp, elapsed * TransitionRenderer.FPS / 1000f, colors.homeColor, colors.homeAccent, name)
                filter.setImage(bmp)
                filter.alpha = 1f
                if (animId != id) { filter.alpha = 0f; return } // transition annulée entre-temps
                val next = (1000f / TransitionRenderer.FPS).toLong() - (SystemClock.uptimeMillis() - start - elapsed)
                handler.postDelayed(this, next.coerceAtLeast(1L))
            }
        })
        // Sécurité si l'animation ne se termine pas
        main.postDelayed({ if (animId == id) finishTransition() }, TransitionRenderer.DURATION_MS + 1_500L)
    }

    private fun finishTransition() {
        if (!transitioning) return
        main.removeCallbacksAndMessages(null)
        if (shownScene != state.scene) showScene(state.scene)
        transitionFilter?.alpha = 0f
        transitionPlayer?.pause()
        animId = 0
        generatedFilter?.alpha = 0f
        transitioning = false
        // La scène a encore changé pendant la transition : on enchaîne
        if (state.scene != shownScene) startTransition()
    }

    private fun onTransitionSurfaceReady() {
        transitionPlayer?.release()
        transitionPlayer = null
        transitionReady = false
        val file = media[MediaSlot.TRANSITION] ?: return
        val surface = transitionFilter?.surface ?: return
        if (!surface.isValid) return
        transitionPlayer = createPlayer(file, surface, loop = false) { mp ->
            transitionDurationMs = mp.duration.coerceAtLeast(0)
            transitionReady = true
        }?.apply {
            setOnCompletionListener { finishTransition() }
            setOnErrorListener { _, _, _ ->
                transitionReady = false
                if (transitioning) {
                    transitioning = false
                    transitionFilter?.alpha = 0f
                    main.removeCallbacksAndMessages(null)
                    showScene(state.scene)
                }
                true
            }
        }
    }

    // ---- Scènes ----

    /** Affiche [scene] ; avec [fadeInMs], son son remonte progressivement (fin de transition). */
    private fun showScene(scene: Scene, fadeInMs: Long? = null) {
        shownScene = scene
        val music = scene != Scene.MATCH
        if (fadeInMs != null) audio.fadeInFor(fadeInMs, music) else audio.crossfade(music)
        val file = videoFor(scene)
        if (file != null) {
            playVideo(file)
        } else {
            videoFilter?.alpha = 0f
            videoPlayer?.release()
            videoPlayer = null
        }
        refreshOverlay()
    }

    private fun refreshOverlay() {
        val now = ScoreManager.now()
        val scene = shownScene
        lastSignature = renderer.scoreboardSignature(state, now)
        overlayFilter?.setImage(
            when {
                scene == null -> renderer.empty()
                scene == Scene.MATCH -> renderer.renderScoreboard(state, now)
                videoFor(scene) != null -> renderer.renderResultCard(state, scene)
                else -> renderer.renderGeneratedScreen(state, scene)
            }
        )
    }

    /** La surface de la vidéo est prête (créée par le thread OpenGL) : on lance la vidéo de la scène. */
    private fun onVideoSurfaceReady() {
        shownScene?.let { scene -> videoFor(scene)?.let { playVideo(it) } }
    }

    private fun playVideo(file: File) {
        videoPlayer?.release()
        videoPlayer = null
        val filter = videoFilter ?: return
        val surface = filter.surface ?: return // sera relancé par onVideoSurfaceReady()
        videoPlayer = createPlayer(file, surface, loop = true) {
            it.start()
            filter.alpha = 1f
        }?.apply {
            setOnErrorListener { mp, _, _ ->
                // Vidéo illisible : on retombe sur l'écran généré
                broken += file
                filter.alpha = 0f
                mp.release()
                if (videoPlayer === mp) videoPlayer = null
                refreshOverlay()
                true
            }
        } ?: run {
            broken += file
            filter.alpha = 0f
            refreshOverlay()
            null
        }
    }

    private fun createPlayer(file: File, surface: Surface, loop: Boolean, onPrepared: (MediaPlayer) -> Unit): MediaPlayer? =
        try {
            MediaPlayer().apply {
                setDataSource(file.path)
                setSurface(surface)
                isLooping = loop
                setVolume(0f, 0f) // le son du live passe par AudioMixer
                setOnPreparedListener { onPrepared(it) }
                prepareAsync()
            }
        } catch (e: Exception) {
            null
        }

    companion object {
        /** Durée avant d'afficher le calque de transition (premières images parfois noires). */
        private const val SHOW_DELAY_MS = 60L
        /** Vérification du chrono : 10 fois par seconde (dixièmes de seconde au basket). */
        private const val TICK_MS = 100L
    }
}
