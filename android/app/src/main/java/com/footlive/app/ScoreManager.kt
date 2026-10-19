package com.footlive.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Source unique de vérité pour le match. Appelé depuis le thread UI (boutons locaux)
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

    fun now(): Long = SystemClock.elapsedRealtime()

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

    /**
     * Applique une action nommée (utilisée par la télécommande et les boutons locaux).
     * [value] : paramètre éventuel (nom du buteur, secondes à ajouter au chrono…).
     * [index] : numéro du but concerné pour « scorer » (dernier but si absent).
     */
    fun apply(action: String, value: String? = null, index: Int? = null): Boolean = synchronized(lock) {
        val s = state
        val now = now()
        val next = when (action) {
            "home_goal" -> addGoal(s, Team.HOME, now)
            "away_goal" -> addGoal(s, Team.AWAY, now)
            "home_goal_minus" -> removeGoal(s, Team.HOME) ?: return false
            "away_goal_minus" -> removeGoal(s, Team.AWAY) ?: return false
            "scorer" -> {
                val i = index ?: s.goals.lastIndex
                val goal = s.goals.getOrNull(i) ?: return false
                val name = value?.trim()?.take(24) ?: ""
                s.copy(goals = s.goals.toMutableList().also { it[i] = goal.copy(scorer = name) })
            }
            "home_red_plus" -> s.copy(homeRed = (s.homeRed + 1).coerceAtMost(5))
            "away_red_plus" -> s.copy(awayRed = (s.awayRed + 1).coerceAtMost(5))
            "home_red_minus" -> s.copy(homeRed = (s.homeRed - 1).coerceAtLeast(0))
            "away_red_minus" -> s.copy(awayRed = (s.awayRed - 1).coerceAtLeast(0))

            // ---- Chrono : pas d'historique (« Annuler » ne doit pas remonter le temps) ----
            "clock_toggle" -> {
                commit(if (s.clockRunning) stopClock(s, now) else s.copy(clockRunning = true, clockStartedAt = now))
                return true
            }
            "clock_adjust" -> {
                val delta = (value?.toLongOrNull() ?: return false) * 1000
                // Ne descend pas sous 0 : le temps déjà compté depuis le départ est pris en compte
                val minBase = if (s.clockRunning) -(now - s.clockStartedAt) else 0L
                commit(s.copy(clockBaseMs = (s.clockBaseMs + delta).coerceAtLeast(minBase)))
                return true
            }

            // ---- Périodes ----
            "end_period" -> endPeriod(s, now)
            "extra_time" -> s.copy(
                period = 3, clockRunning = false, clockBaseMs = 0, clockStartedAt = 0,
                finished = false, penalties = false, scene = Scene.MATCH,
            )
            "penalties_toggle" -> if (s.penalties) s.copy(penalties = false) else
                stopClock(s, now).copy(penalties = true, scene = Scene.MATCH)
            "home_pen" -> s.copy(homePens = s.homePens + 1)
            "away_pen" -> s.copy(awayPens = s.awayPens + 1)
            "home_pen_minus" -> s.copy(homePens = (s.homePens - 1).coerceAtLeast(0))
            "away_pen_minus" -> s.copy(awayPens = (s.awayPens - 1).coerceAtLeast(0))

            "toggle_overlay" -> s.copy(overlayVisible = !s.overlayVisible)
            // Changement de scène : pas d'historique (« Annuler » ne concerne que le match)
            "scene_intro", "scene_match", "scene_halftime", "scene_outro" -> {
                val scene = Scene.valueOf(action.removePrefix("scene_").uppercase())
                if (scene != s.scene) commit(s.copy(scene = scene))
                return true
            }
            "reset" -> ScoreState(
                homeName = s.homeName,
                awayName = s.awayName,
                competition = s.competition,
                halfLength = s.halfLength,
                overlayVisible = s.overlayVisible,
                scene = s.scene,
            )
            "undo" -> {
                val previous = history.removeLastOrNull() ?: return false
                // On garde l'écran affiché, et le chrono tel quel si on reste dans la même période
                var restored = previous.copy(scene = s.scene)
                if (previous.period == s.period) {
                    restored = restored.copy(
                        clockRunning = s.clockRunning,
                        clockBaseMs = s.clockBaseMs,
                        clockStartedAt = s.clockStartedAt,
                    )
                }
                state = restored
                version++
                notifyListeners(restored)
                return true
            }
            else -> return false
        }
        push(s)
        commit(next)
        true
    }

    fun configure(homeName: String?, awayName: String?, competition: String?, halfLength: Int?) = synchronized(lock) {
        val s = state
        val next = s.copy(
            homeName = homeName?.trim()?.takeIf { it.isNotEmpty() }?.take(20) ?: s.homeName,
            awayName = awayName?.trim()?.takeIf { it.isNotEmpty() }?.take(20) ?: s.awayName,
            competition = competition?.trim()?.take(60) ?: s.competition,
            halfLength = halfLength?.coerceIn(10, 45) ?: s.halfLength,
        )
        if (next != s) {
            push(s)
            commit(next)
        }
    }

    private fun addGoal(s: ScoreState, team: Team, now: Long): ScoreState {
        val minute = if (s.penalties) "" else s.minuteText(now)
        val goals = s.goals + Goal(team, minute, at = now)
        return if (team == Team.HOME) s.copy(homeGoals = s.homeGoals + 1, goals = goals)
        else s.copy(awayGoals = s.awayGoals + 1, goals = goals)
    }

    /** Retire le dernier but de l'équipe (erreur de saisie). */
    private fun removeGoal(s: ScoreState, team: Team): ScoreState? {
        val i = s.goals.indexOfLast { it.team == team }
        val goals = if (i >= 0) s.goals.filterIndexed { idx, _ -> idx != i } else s.goals
        return when (team) {
            Team.HOME -> if (s.homeGoals == 0) null else s.copy(homeGoals = s.homeGoals - 1, goals = goals)
            Team.AWAY -> if (s.awayGoals == 0) null else s.copy(awayGoals = s.awayGoals - 1, goals = goals)
        }
    }

    private fun stopClock(s: ScoreState, now: Long): ScoreState =
        if (!s.clockRunning) s else s.copy(clockRunning = false, clockBaseMs = s.elapsedMs(now), clockStartedAt = 0)

    /**
     * Fin de période : 1re mi-temps → écran Mi-temps et chrono prêt pour la 2e ;
     * 1re prolongation → chrono prêt pour la 2e ; fin du match → écran de fin.
     */
    private fun endPeriod(s: ScoreState, now: Long): ScoreState {
        val stopped = stopClock(s, now)
        return when (s.period) {
            1 -> stopped.copy(period = 2, clockBaseMs = 0, scene = Scene.HALFTIME)
            3 -> stopped.copy(period = 4, clockBaseMs = 0)
            else -> stopped.copy(finished = true, scene = Scene.OUTRO)
        }
    }

    private fun push(s: ScoreState) {
        history.addLast(s)
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    private fun commit(next: ScoreState) {
        state = next
        version++
        notifyListeners(next)
    }

    private fun notifyListeners(s: ScoreState) {
        mainHandler.post { listeners.forEach { it(s) } }
    }
}
