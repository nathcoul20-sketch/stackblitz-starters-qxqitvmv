package com.volleylive.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.library.view.GlInterface

/**
 * Gère ce qui est affiché par-dessus la caméra, sous forme de 3 calques OpenGL (du bas vers le haut) :
 *  1. vidéo plein écran (intro en boucle / écran de fin), invisible pendant le match ;
 *  2. overlay : tableau de score en match, résultat sur l'écran de fin ;
 *  3. transition animée, jouée à chaque changement de scène.
 *
 * Le changement de scène a lieu au milieu de la transition, quand elle couvre tout l'écran.
 * Toutes les méthodes publiques s'appellent depuis le thread principal.
 */
class SceneController(private val context: Context, private val renderer: OverlayRenderer) {

    private var videoFilter: SurfaceFilterRender? = null
    private var overlayFilter: ImageObjectFilterRender? = null
    private var transitionFilter: ImageObjectFilterRender? = null
    private var player: MediaPlayer? = null

    private var state: ScoreState = ScoreManager.state
    private var shownScene: Scene? = null
    private var transitioning = false
    private val transition = TransitionPlayer(context)

    /** Installe les calques sur l'aperçu/flux. À rappeler à chaque redémarrage de l'aperçu. */
    fun attach(gl: GlInterface) {
        detach()
        val video = SurfaceFilterRender { onVideoSurfaceReady() }.apply { alpha = 0f }
        val overlay = ImageObjectFilterRender()
        val trans = ImageObjectFilterRender().apply { alpha = 0f }
        gl.clearFilters()
        listOf(video, overlay, trans).forEach {
            gl.addFilter(it)
            it.setScale(100f, 100f)
            it.setPosition(0f, 0f)
        }
        videoFilter = video
        overlayFilter = overlay
        transitionFilter = trans
        showScene(state.scene)
    }

    fun detach() {
        transition.cancel()
        transitioning = false
        releasePlayer()
        videoFilter = null
        overlayFilter = null
        transitionFilter = null
        shownScene = null
    }

    fun release() {
        detach()
        transition.quit()
    }

    fun onState(s: ScoreState) {
        state = s
        if (overlayFilter == null) return
        if (!transitioning && s.scene != shownScene) startTransition() else refreshOverlay()
    }

    private fun startTransition() {
        val filter = transitionFilter ?: return
        transitioning = true
        filter.alpha = 1f
        transition.play(
            filter,
            onCut = { showScene(state.scene) },
            onEnd = {
                transitioning = false
                filter.alpha = 0f
                // La scène a encore changé pendant la transition : on enchaîne
                if (state.scene != shownScene) startTransition()
            },
        )
    }

    private fun showScene(scene: Scene) {
        shownScene = scene
        when (scene) {
            Scene.INTRO -> playVideo(R.raw.intro, loop = true)
            Scene.OUTRO -> playVideo(R.raw.outro, loop = false) // s'arrête sur la dernière image
            Scene.MATCH -> {
                videoFilter?.alpha = 0f
                releasePlayer()
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
        releasePlayer()
        val filter = videoFilter ?: return
        val surface = filter.surface ?: return // sera relancé par onVideoSurfaceReady()
        player = MediaPlayer().apply {
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

    private fun releasePlayer() {
        player?.release()
        player = null
    }
}

/**
 * Joue la transition (images WebP avec transparence dans assets/transition) sur un calque.
 * Le décodage se fait sur un thread dédié ; la lecture suit l'horloge (images sautées si le téléphone est lent).
 */
private class TransitionPlayer(private val context: Context) {

    private val frames: List<String> =
        context.assets.list(DIR)?.sorted()?.map { "$DIR/$it" } ?: emptyList()
    private val thread = HandlerThread("transition").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    /** Quelques bitmaps réutilisés pour éviter d'allouer ~4 Mo à chaque image. */
    private val pool = arrayOfNulls<Bitmap>(3)
    @Volatile private var generation = 0

    fun play(filter: ImageObjectFilterRender, onCut: () -> Unit, onEnd: () -> Unit) {
        val gen = ++generation
        if (frames.isEmpty()) {
            main.post { onCut(); onEnd() }
            return
        }
        val cut = (frames.size * CUT_RATIO).toInt()
        val start = SystemClock.uptimeMillis()
        var shown = -1
        var cutDone = false

        fun step() {
            if (gen != generation) return
            val index = ((SystemClock.uptimeMillis() - start) / FRAME_MS).toInt()
            if (index >= frames.size) {
                main.post { if (gen == generation) onEnd() }
                return
            }
            if (index != shown) {
                decode(index)?.let { filter.setImage(it) }
                shown = index
            }
            if (!cutDone && index >= cut) {
                cutDone = true
                main.post { if (gen == generation) onCut() }
            }
            worker.postAtTime({ step() }, start + (index + 1) * FRAME_MS)
        }
        worker.post { step() }
    }

    fun cancel() {
        generation++
        worker.removeCallbacksAndMessages(null)
    }

    fun quit() {
        cancel()
        thread.quitSafely()
    }

    private fun decode(index: Int): Bitmap? = try {
        val slot = index % pool.size
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inBitmap = pool[slot]
        }
        context.assets.open(frames[index]).use { BitmapFactory.decodeStream(it, null, options) }
            ?.also { pool[slot] = it }
    } catch (e: IllegalArgumentException) {
        // Bitmap du pool incompatible : on décode sans le réutiliser
        pool[index % pool.size] = null
        context.assets.open(frames[index]).use { BitmapFactory.decodeStream(it) }
    }

    companion object {
        private const val DIR = "transition"
        private const val FRAME_MS = 1000L / 30
        /** Moment du changement de scène : quand la transition couvre tout l'écran (≈ milieu). */
        private const val CUT_RATIO = 0.54f
    }
}
