package com.scorecast.app

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Handler
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
    /**
     * Transition générée : elle est dessinée par-dessus l'image de l'overlay (calque déjà éprouvé),
     * image par image depuis le thread principal. [animStart] = 0 : aucune animation en cours.
     */
    private var animStart = 0L
    private var cutDone = false
    /** Dernière image « normale » de l'overlay (sans transition), sur laquelle on pose l'animation. */
    private var overlayBase: Bitmap? = null
    private var transitionLayer: Bitmap? = null
    private val composePool = arrayOfNulls<Bitmap>(3)
    private var composeIndex = 0
    private val bitmapPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val animFrame = object : Runnable {
        override fun run() = onAnimFrame()
    }
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
        animStart = 0L
        shownScene = null
    }

    /** Arrêt définitif (fin de l'activité). */
    fun release() {
        detach()
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

    /** Volets + logo aux couleurs du club, dessinés image par image par-dessus l'overlay. */
    private fun startGeneratedTransition() {
        if (generated == null || overlayFilter == null) {
            showScene(state.scene)
            return
        }
        transitioning = true
        cutDone = false
        animStart = SystemClock.uptimeMillis()
        audio.fadeOutFor(TransitionRenderer.CUT_MS)
        main.post(animFrame)
    }

    private fun onAnimFrame() {
        if (animStart == 0L) return
        val elapsed = SystemClock.uptimeMillis() - animStart
        if (!cutDone && elapsed >= TransitionRenderer.CUT_MS) {
            // L'écran est couvert : on change de scène, le nouveau son remonte pendant l'ouverture
            cutDone = true
            showScene(state.scene, TransitionRenderer.DURATION_MS - TransitionRenderer.CUT_MS)
        }
        if (elapsed >= TransitionRenderer.DURATION_MS) {
            finishTransition()
            return
        }
        pushOverlay()
        main.postDelayed(animFrame, (1000f / TransitionRenderer.FPS).toLong())
    }

    /** Envoie l'overlay : l'image normale, avec l'image courante de la transition par-dessus si besoin. */
    private fun pushOverlay() {
        val base = overlayBase ?: return
        val filter = overlayFilter ?: return
        val renderer = generated
        if (animStart == 0L || renderer == null) {
            filter.setImage(base)
            return
        }
        val layer = transitionLayer?.takeIf { it.width == renderer.width && it.height == renderer.height }
            ?: Bitmap.createBitmap(renderer.width, renderer.height, Bitmap.Config.ARGB_8888).also { transitionLayer = it }
        val frame = (SystemClock.uptimeMillis() - animStart) * TransitionRenderer.FPS / 1000f
        renderer.draw(layer, frame, state.homeColor, state.homeAccent, state.homeName)
        // 3 images utilisées à tour de rôle : le calque envoie l'une au GPU pendant qu'on dessine la suivante
        val i = composeIndex++ % composePool.size
        val out = composePool[i]?.takeIf { it.width == base.width && it.height == base.height }
            ?: Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888).also { composePool[i] = it }
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        c.drawBitmap(base, 0f, 0f, null)
        c.drawBitmap(layer, null, android.graphics.Rect(0, 0, out.width, out.height), bitmapPaint)
        filter.setImage(out)
    }

    private fun finishTransition() {
        if (!transitioning) return
        main.removeCallbacksAndMessages(null)
        if (shownScene != state.scene) showScene(state.scene)
        transitionFilter?.alpha = 0f
        transitionPlayer?.pause()
        val wasGenerated = animStart != 0L
        animStart = 0L
        transitioning = false
        if (wasGenerated) pushOverlay()
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
        overlayBase = when {
            scene == null -> renderer.empty()
            scene == Scene.MATCH -> renderer.renderScoreboard(state, now)
            videoFor(scene) != null -> renderer.renderResultCard(state, scene)
            else -> renderer.renderGeneratedScreen(state, scene)
        }
        pushOverlay()
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
