package com.volleylive.app

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.library.view.GlInterface

/**
 * Gère ce qui est affiché par-dessus la caméra, sous forme de 3 calques OpenGL (du bas vers le haut) :
 *  1. vidéo plein écran (intro / pause entre deux sets / écran de fin), invisible pendant le match.
 *     Chaque vidéo joue son animation d'entrée une fois, puis boucle sur sa seconde moitié
 *     (voir [LOOP_FROM_MS]). La pause reprend la vidéo de l'écran de fin ;
 *  2. overlay : tableau de score en match, résumé du match sur les écrans de pause et de fin ;
 *  3. transitions (vidéos avec transparence), jouées à chaque changement de scène : la transition
 *     du club (10 s, avec sa bande-son) d'Intro vers Match, la transition ECVB courte pour tous
 *     les autres changements.
 *
 * Le changement de scène a lieu pendant la transition, quand elle couvre tout l'écran.
 * Gère aussi le son : musique (micro coupé) en intro / pause / fin, micro en match, avec des fondus
 * pendant les transitions. Seule la transition Intro → Match a un son (mixé dans le live).
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

    /**
     * Pendant le match : redessine le tableau quand le logo partenaire change, puis image par image
     * pendant son glissement (environ 30 images/s, moins d'une seconde toutes les 8 s).
     */
    private val ticker = Handler(Looper.getMainLooper())
    private var shownPartner = -1
    private var sliding = false
    private val tick = object : Runnable {
        override fun run() {
            var delay = IDLE_TICK_MS
            if (shownScene == Scene.MATCH) {
                val now = SystemClock.elapsedRealtime()
                val slide = renderer.partnerSliding(state, now)
                // Dernière image du glissement comprise (logo bien en place)
                if (slide || sliding || renderer.partnerIndex(state, now) != shownPartner) refreshOverlay()
                sliding = slide
                // Réveil pile au prochain changement de logo, pour ne pas rater le début du glissement
                val untilNext = OverlayRenderer.PARTNER_ROTATION_MS - now % OverlayRenderer.PARTNER_ROTATION_MS
                delay = if (slide) FRAME_MS else minOf(IDLE_TICK_MS, untilNext).coerceAtLeast(1L)
            }
            ticker.postDelayed(this, delay)
        }
    }

    private var videoFilter: SurfaceFilterRender? = null
    private var overlayFilter: ImageObjectFilterRender? = null
    private var videoPlayer: MediaPlayer? = null

    /** Une vidéo de transition : son calque (avec transparence) et son lecteur, préparé d'avance. */
    private class Transition(
        val resId: Int,
        /** Changement d'écran, quand la vidéo couvre tout l'écran. */
        val cutMs: Long,
        /** Le son du nouvel écran remonte à partir de cet instant ; null : dès le changement d'écran. */
        val soundInMs: Long?,
        /** Sa bande-son est envoyée dans le live (mixée par [AudioMixer]). */
        val withSound: Boolean,
    ) {
        var filter: AlphaVideoFilterRender? = null
        var surface: Surface? = null
        var player: MediaPlayer? = null
        var ready = false
        var durationMs = DEFAULT_TRANSITION_MS

        /** Libère le lecteur (le calque reste en place, invisible). */
        fun unload() {
            player?.release()
            player = null
            ready = false
        }
    }

    /** Transition ECVB courte : tous les changements d'écran… */
    private val quick = Transition(R.raw.transition, cutMs = QUICK_CUT_MS, soundInMs = null, withSound = false)
    /**
     * …sauf Intro → Match : transition du club (couvre l'écran de 1 s à 8,4 s, puis s'ouvre sur le
     * match ; le micro remonte pendant l'ouverture). Version allégée si le téléphone ne sait pas lire
     * la vidéo pleine définition. Préparée seulement quand l'Intro est affichée.
     */
    private val stinger = Transition(
        if (canDecodeFullStinger()) R.raw.stinger else R.raw.stinger_low,
        cutMs = STINGER_CUT_MS, soundInMs = STINGER_SOUND_IN_MS, withSound = true,
    )
    private val transitions = listOf(quick, stinger)
    private var current: Transition? = null
    /** Son du nouvel écran encore coupé (transition qui le garde jusqu'à son ouverture). */
    private var soundPending = false

    private var state: ScoreState = ScoreManager.state
    private var shownScene: Scene? = null
    private var transitioning = false

    /** Installe les calques sur l'aperçu/flux. À rappeler à chaque redémarrage de l'aperçu. */
    fun attach(gl: GlInterface) {
        detach()
        val video = SurfaceFilterRender { onVideoSurfaceReady() }.apply { alpha = 0f }
        val overlay = ImageObjectFilterRender()
        for (t in transitions) {
            var layer: AlphaVideoFilterRender? = null
            // Surface d'un ancien calque (aperçu redémarré entre-temps) : ignorée
            layer = AlphaVideoFilterRender { surface -> if (t.filter === layer) onTransitionSurfaceReady(t, surface) }
                .apply { alpha = 0f }
            t.filter = layer
        }
        gl.clearFilters()
        (listOf(video, overlay) + transitions.mapNotNull { it.filter }).forEach {
            gl.addFilter(it)
            it.setScale(100f, 100f)
            it.setPosition(0f, 0f)
        }
        videoFilter = video
        overlayFilter = overlay
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
        for (t in transitions) {
            t.unload()
            t.filter = null
            t.surface = null
        }
        current = null
        soundPending = false
        audio.stopEffect()
        videoFilter = null
        overlayFilter = null
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
        // Intro → Match : transition du club (si elle est prête), sinon la transition courte
        val wanted = if (shownScene == Scene.INTRO && state.scene == Scene.MATCH) stinger else quick
        val t = if (wanted.ready) wanted else quick
        val player = t.player
        val filter = t.filter
        if (player == null || filter == null || !t.ready) {
            // Transition indisponible : on change de scène directement
            finishTransition(cut = true)
            return
        }
        current = t
        player.seekTo(0)
        player.start()
        if (t.withSound) audio.playEffect()
        // Le calque n'est rendu visible qu'après quelques images : les premières sont transparentes
        // et cela évite d'afficher une image vide le temps que la vidéo démarre.
        main.postDelayed({ filter.alpha = 1f }, SHOW_DELAY_MS)
        // Son : celui en cours s'éteint doucement jusqu'au changement d'écran, puis le nouveau remonte
        // progressivement : pendant que les volets s'ouvrent (transition courte), ou seulement quand la
        // transition du club s'ouvre sur le match (sa bande-son occupe le live jusque-là)
        audio.fadeOutFor(t.cutMs)
        val soundIn = t.soundInMs
        if (soundIn == null) {
            val fadeIn = (t.durationMs - t.cutMs).coerceAtLeast(500L)
            main.postDelayed({ showScene(state.scene, fadeIn) }, t.cutMs)
        } else {
            main.postDelayed({
                soundPending = true
                showScene(state.scene, holdSound = true)
            }, t.cutMs)
            main.postDelayed({ releaseSound((t.durationMs - soundIn).coerceAtLeast(500L)) }, soundIn)
        }
        // Sécurité si la fin de la vidéo n'est jamais signalée
        main.postDelayed({ finishTransition(cut = false) }, t.durationMs + TIMEOUT_MARGIN_MS)
    }

    /** Le son du nouvel écran remonte en [fadeMs] (il était gardé coupé pendant la transition). */
    private fun releaseSound(fadeMs: Long) {
        if (!soundPending) return
        soundPending = false
        audio.fadeInFor(fadeMs, shownScene != Scene.MATCH)
    }

    private fun finishTransition(cut: Boolean) {
        if (!transitioning) return
        main.removeCallbacksAndMessages(null)
        val finished = current
        current = null
        if (cut) {
            // Vidéo interrompue (ou indisponible) : sa bande-son s'arrête aussi
            audio.stopEffect()
            soundPending = false
            showScene(state.scene)
        } else {
            releaseSound(SOUND_RESCUE_MS)
        }
        for (t in transitions) t.filter?.alpha = 0f
        // La transition du club ne resservira qu'au prochain passage par l'Intro : lecteur libéré
        if (finished === stinger) stinger.unload()
        transitioning = false
        // La scène a encore changé pendant la transition : on enchaîne
        if (state.scene != shownScene) startTransition()
    }

    private fun onTransitionSurfaceReady(t: Transition, surface: Surface) {
        t.unload()
        t.surface = surface
        if (t === quick || shownScene == Scene.INTRO) prepareTransition(t)
    }

    /** Prépare le lecteur d'une transition sur son calque (une fois la surface du calque créée). */
    private fun prepareTransition(t: Transition) {
        val surface = t.surface ?: return
        if (t.player != null || !surface.isValid) return
        t.player = MediaPlayer().apply {
            context.resources.openRawResourceFd(t.resId).use { afd ->
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            setSurface(surface)
            setVolume(0f, 0f)
            setOnPreparedListener {
                t.ready = true
                if (it.duration > 0) t.durationMs = it.duration.toLong()
            }
            setOnCompletionListener { if (current === t) finishTransition(cut = false) }
            setOnErrorListener { _, _, _ ->
                t.ready = false
                if (current === t) finishTransition(cut = true)
                true
            }
            prepareAsync()
        }
    }

    // ---- Scènes ----

    /**
     * Affiche [scene] ; avec [fadeInMs], son son remonte progressivement (fin de transition) ;
     * avec [holdSound], il reste coupé (remonté plus tard par [releaseSound]).
     */
    private fun showScene(scene: Scene, fadeInMs: Long? = null, holdSound: Boolean = false) {
        shownScene = scene
        val music = scene != Scene.MATCH
        when {
            holdSound -> {}
            fadeInMs != null -> audio.fadeInFor(fadeInMs, music)
            else -> audio.crossfade(music)
        }
        // Sur l'Intro, la transition du club est prête pour le passage au match
        if (scene == Scene.INTRO) prepareTransition(stinger)
        when (scene) {
            Scene.INTRO -> playVideo(R.raw.intro)
            Scene.PAUSE, Scene.OUTRO -> playVideo(R.raw.outro)
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
                Scene.PAUSE -> renderer.renderPause(state)
                Scene.OUTRO -> renderer.renderOutro(state)
                else -> renderer.empty()
            }
        )
    }

    /** La surface de la vidéo est prête (créée par le thread OpenGL) : on lance la vidéo de la scène. */
    private fun onVideoSurfaceReady() {
        when (shownScene) {
            Scene.INTRO -> playVideo(R.raw.intro)
            Scene.PAUSE, Scene.OUTRO -> playVideo(R.raw.outro)
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
        /** Transition courte : les volets couvrent tout l'écran entre 0,3 s et 1,1 s. */
        private const val QUICK_CUT_MS = 730L
        /** Transition du club : écran couvert de 1 s à 8,4 s, puis ouverture sur le match. */
        private const val STINGER_CUT_MS = 1_200L
        private const val STINGER_SOUND_IN_MS = 8_400L
        /** Sécurité : fin forcée de la transition, après sa durée + cette marge. */
        private const val TIMEOUT_MARGIN_MS = 1_500L
        /** Remontée du son si la transition s'est terminée avant de le faire. */
        private const val SOUND_RESCUE_MS = 800L
        /** Durée d'une transition tant qu'elle n'est pas connue. */
        private const val DEFAULT_TRANSITION_MS = 1_700L
        /** Reprise des vidéos d'intro et de fin (image clé placée à 4,5 s à l'encodage). */
        private const val LOOP_FROM_MS = 4_500L
        /** Vérification du logo partenaire hors glissement, et cadence pendant le glissement. */
        private const val IDLE_TICK_MS = 500L
        private const val FRAME_MS = 33L

        /**
         * Le téléphone a-t-il un décodeur vidéo matériel capable de lire la transition du club en
         * pleine définition (3840 × 1080 : image et transparence côte à côte, 30 images/s) ?
         */
        private fun canDecodeFullStinger(): Boolean = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                if (info.isEncoder) return@any false
                val software = if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
                else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
                if (software) return@any false
                val type = info.supportedTypes.firstOrNull { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
                    ?: return@any false
                info.getCapabilitiesForType(type).videoCapabilities?.areSizeAndRateSupported(3840, 1080, 30.0) == true
            }
        }.getOrDefault(false)
    }
}
