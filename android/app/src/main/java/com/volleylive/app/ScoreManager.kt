package com.volleylive.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Source unique de vérité pour le score. Appelé depuis le thread UI (boutons locaux)
 * et depuis les threads du serveur web (télécommande) : tout est synchronisé.
 * Les listeners sont toujours notifiés sur le thread principal.
 */
object ScoreManager {

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(ScoreState) -> Unit>()
    private val history = ArrayDeque<ScoreState>()
    private const val MAX_HISTORY = 200

    @Volatile
    var state: ScoreState = ScoreState()
        private set

    /** Incrémenté à chaque changement : permet à la télécommande de savoir s'il y a du nouveau. */
    @Volatile
    var version: Long = 0
        private set

    /** Appelé à chaque changement (thread de l'appelant) : sauvegarde du match en cours. */
    @Volatile
    var onCommit: ((ScoreState) -> Unit)? = null

    fun addListener(listener: (ScoreState) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeListener(listener: (ScoreState) -> Unit) {
        listeners.remove(listener)
    }

    fun init(initial: ScoreState) = synchronized(lock) {
        history.clear()
        commit(initial)
    }

    /** Applique une action nommée (utilisée par la télécommande et les boutons locaux). */
    fun apply(action: String): Boolean = synchronized(lock) {
        val s = state
        val next = when (action) {
            "home_plus" -> s.copy(homeScore = s.homeScore + 1, serving = Team.HOME)
            "away_plus" -> s.copy(awayScore = s.awayScore + 1, serving = Team.AWAY)
            "home_minus" -> s.copy(homeScore = (s.homeScore - 1).coerceAtLeast(0))
            "away_minus" -> s.copy(awayScore = (s.awayScore - 1).coerceAtLeast(0))
            "home_set_plus" -> s.copy(homeSets = (s.homeSets + 1).coerceAtMost(3))
            "away_set_plus" -> s.copy(awaySets = (s.awaySets + 1).coerceAtMost(3))
            "home_set_minus" -> s.copy(homeSets = (s.homeSets - 1).coerceAtLeast(0))
            "away_set_minus" -> s.copy(awaySets = (s.awaySets - 1).coerceAtLeast(0))
            "end_set" -> endSet(s)
            "serve_home" -> s.copy(serving = Team.HOME)
            "serve_away" -> s.copy(serving = Team.AWAY)
            "toggle_overlay" -> s.copy(overlayVisible = !s.overlayVisible)
            // Changement de scène : pas d'historique (« Annuler » ne concerne que le score)
            // Son du live : pas d'historique non plus
            "toggle_mute" -> {
                commit(s.copy(audioMuted = !s.audioMuted))
                return true
            }
            "toggle_partners" -> {
                commit(s.copy(partnersVisible = !s.partnersVisible))
                return true
            }
            "toggle_viewers" -> {
                commit(s.copy(viewersVisible = !s.viewersVisible))
                return true
            }
            "scene_intro", "scene_match", "scene_outro" -> {
                val scene = Scene.valueOf(action.removePrefix("scene_").uppercase())
                if (scene != s.scene) commit(s.copy(scene = scene))
                return true
            }
            "reset" -> ScoreState(
                homeName = s.homeName,
                awayName = s.awayName,
                competition = s.competition,
                overlayVisible = s.overlayVisible,
                scene = s.scene,
                audioMuted = s.audioMuted,
                partnersVisible = s.partnersVisible,
                viewersVisible = s.viewersVisible,
            )
            "undo" -> {
                // On garde la scène actuelle : annuler un point ne doit pas changer d'écran
                val previous = history.removeLastOrNull()
                    ?.copy(
                        scene = s.scene, audioMuted = s.audioMuted,
                        partnersVisible = s.partnersVisible, viewersVisible = s.viewersVisible,
                    ) ?: return false
                state = previous
                version++
                onCommit?.invoke(previous)
                notifyListeners(previous)
                return true
            }
            else -> return false
        }
        push(s)
        commit(next)
        true
    }

    fun configure(homeName: String?, awayName: String?, competition: String?) = synchronized(lock) {
        val s = state
        val next = s.copy(
            homeName = homeName?.trim()?.takeIf { it.isNotEmpty() }?.take(20) ?: s.homeName,
            awayName = awayName?.trim()?.takeIf { it.isNotEmpty() }?.take(20) ?: s.awayName,
            competition = competition?.trim()?.take(60) ?: s.competition,
        )
        if (next != s) {
            push(s)
            commit(next)
        }
    }

    /** Termine le set : le set est attribué à l'équipe qui mène, puis on passe au set suivant. */
    private fun endSet(s: ScoreState): ScoreState {
        val winner = when {
            s.homeScore > s.awayScore -> Team.HOME
            s.awayScore > s.homeScore -> Team.AWAY
            else -> return s
        }
        return s.copy(
            homeSets = s.homeSets + if (winner == Team.HOME) 1 else 0,
            awaySets = s.awaySets + if (winner == Team.AWAY) 1 else 0,
            homeScore = 0,
            awayScore = 0,
            setScores = s.setScores + (s.homeScore to s.awayScore),
            setNumber = s.setNumber + 1,
            serving = null,
        )
    }

    private fun push(s: ScoreState) {
        history.addLast(s)
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    private fun commit(next: ScoreState) {
        state = next
        version++
        onCommit?.invoke(next)
        notifyListeners(next)
    }

    private fun notifyListeners(s: ScoreState) {
        mainHandler.post { listeners.forEach { it(s) } }
    }
}
