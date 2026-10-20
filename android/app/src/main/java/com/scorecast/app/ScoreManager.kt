package com.scorecast.app

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
    private const val MAX_HISTORY = 300

    /** Arrêt automatique du chrono en fin de période (basket, hand). */
    private val autoStop = Runnable { onPeriodTimeUp() }

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
     * [value] : paramètre éventuel (points marqués, nom du buteur, secondes à ajouter au chrono…).
     * [index] : numéro du but concerné pour « scorer » (dernier but si absent).
     */
    fun apply(action: String, value: String? = null, index: Int? = null): Boolean = synchronized(lock) {
        val s = state
        val now = now()
        val team = when {
            action.startsWith("home_") -> Team.HOME
            action.startsWith("away_") -> Team.AWAY
            else -> null
        }
        val verb = if (team != null) action.substringAfter('_') else action
        val amount = value?.toIntOrNull()?.coerceIn(1, 3) ?: 1

        val next: ScoreState = if (team != null) {
            teamAction(s, team, verb, amount, now) ?: return false
        } else when (action) {
            "scorer" -> {
                val i = index ?: s.goals.lastIndex
                val goal = s.goals.getOrNull(i) ?: return false
                val name = value?.trim()?.take(24) ?: ""
                s.copy(goals = s.goals.toMutableList().also { it[i] = goal.copy(scorer = name) })
            }

            // ---- Chrono : pas d'historique (« Annuler » ne doit pas remonter le temps) ----
            "clock_toggle" -> {
                if (!s.sport.hasClock) return false
                if (!s.clockRunning && s.sport.autoStop && s.elapsedMs(now) >= s.periodLengthMs) return false
                commit(if (s.clockRunning) stopClock(s, now) else s.copy(clockRunning = true, clockStartedAt = now))
                return true
            }
            "clock_adjust" -> {
                val delta = (value?.toLongOrNull() ?: return false) * 1000
                // Au basket, les boutons agissent sur le temps restant : « +10 s » ajoute du temps à jouer
                val signed = if (s.sport.countdown) -delta else delta
                val minBase = if (s.clockRunning) -(now - s.clockStartedAt) else 0L
                var base = (s.clockBaseMs + signed).coerceAtLeast(minBase)
                if (s.sport.autoStop) {
                    val maxBase = s.periodLengthMs - if (s.clockRunning) (now - s.clockStartedAt) else 0L
                    base = base.coerceAtMost(maxBase)
                }
                commit(s.copy(clockBaseMs = base))
                return true
            }

            // ---- Périodes ----
            "end_period" -> endPeriod(s, now) ?: return false
            "extra_time" -> when (s.sport) {
                Sport.FOOT -> s.copy(
                    period = 3, clockRunning = false, clockBaseMs = 0, clockStartedAt = 0,
                    finished = false, penalties = false, scene = Scene.MATCH,
                )
                Sport.BASKET -> s.copy(
                    period = maxOf(5, s.period + 1), clockRunning = false, clockBaseMs = 0, clockStartedAt = 0,
                    finished = false, homeTimeouts = 0, awayTimeouts = 0, scene = Scene.MATCH,
                )
                else -> return false
            }
            "penalties_toggle" -> {
                if (s.sport != Sport.FOOT) return false
                if (s.penalties) s.copy(penalties = false)
                else stopClock(s, now).copy(penalties = true, scene = Scene.MATCH)
            }

            // ---- Volley ----
            "serve_home" -> s.copy(serving = Team.HOME)
            "serve_away" -> s.copy(serving = Team.AWAY)

            "toggle_overlay" -> s.copy(overlayVisible = !s.overlayVisible)
            // Coupure du son : pas d'historique (« Annuler » ne doit pas remettre le son)
            "toggle_mute" -> {
                commit(s.copy(muted = !s.muted))
                return true
            }
            // Changement de scène : pas d'historique (« Annuler » ne concerne que le match)
            "scene_intro", "scene_match", "scene_halftime", "scene_outro" -> {
                val scene = Scene.valueOf(action.removePrefix("scene_").uppercase())
                if (scene != s.scene) commit(s.copy(scene = scene))
                return true
            }
            "reset" -> ScoreState(
                sport = s.sport,
                homeName = s.homeName,
                awayName = s.awayName,
                competition = s.competition,
                homeColor = s.homeColor,
                homeAccent = s.homeAccent,
                awayColor = s.awayColor,
                periodMinutes = s.periodMinutes,
                overlayVisible = s.overlayVisible,
                muted = s.muted,
                scene = s.scene,
            )
            "undo" -> {
                val previous = history.removeLastOrNull() ?: return false
                // On garde l'écran affiché, et le chrono tel quel si on reste dans la même période
                var restored = previous.copy(scene = s.scene, muted = s.muted)
                if (previous.period == s.period) {
                    restored = restored.copy(
                        clockRunning = s.clockRunning,
                        clockBaseMs = s.clockBaseMs,
                        clockStartedAt = s.clockStartedAt,
                    )
                }
                commit(restored)
                return true
            }
            else -> return false
        }
        push(s)
        commit(next)
        true
    }

    /** Actions « home_xxx » / « away_xxx ». Renvoie null si l'action n'a pas de sens (rien à annuler). */
    private fun teamAction(s: ScoreState, team: Team, verb: String, amount: Int, now: Long): ScoreState? {
        val home = team == Team.HOME
        return when (verb) {
            // Score : +1 (foot, hand, volley) ou +1/+2/+3 (basket)
            "plus", "goal" -> {
                val points = if (s.sport == Sport.BASKET) amount else 1
                var n = if (home) s.copy(homeScore = s.homeScore + points) else s.copy(awayScore = s.awayScore + points)
                if (s.sport == Sport.FOOT) {
                    val minute = if (s.penalties) "" else s.minuteText(now)
                    n = n.copy(goals = s.goals + Goal(team, minute, at = now))
                }
                if (s.sport == Sport.VOLLEY) n = n.copy(serving = team)
                n
            }
            "minus", "goal_minus" -> {
                val current = if (home) s.homeScore else s.awayScore
                if (current == 0) return null
                val points = if (s.sport == Sport.BASKET) minOf(amount, current) else 1
                var n = if (home) s.copy(homeScore = current - points) else s.copy(awayScore = current - points)
                if (s.sport == Sport.FOOT) {
                    val i = s.goals.indexOfLast { it.team == team }
                    if (i >= 0) n = n.copy(goals = s.goals.filterIndexed { idx, _ -> idx != i })
                }
                n
            }
            // Foot : cartons rouges, tirs au but
            "red_plus" -> if (home) s.copy(homeRed = (s.homeRed + 1).coerceAtMost(5)) else s.copy(awayRed = (s.awayRed + 1).coerceAtMost(5))
            "red_minus" -> if (home) s.copy(homeRed = (s.homeRed - 1).coerceAtLeast(0)) else s.copy(awayRed = (s.awayRed - 1).coerceAtLeast(0))
            "pen" -> if (home) s.copy(homePens = s.homePens + 1) else s.copy(awayPens = s.awayPens + 1)
            "pen_minus" -> if (home) s.copy(homePens = (s.homePens - 1).coerceAtLeast(0)) else s.copy(awayPens = (s.awayPens - 1).coerceAtLeast(0))
            // Volley : sets
            "set_plus" -> if (home) s.copy(homeSets = (s.homeSets + 1).coerceAtMost(3)) else s.copy(awaySets = (s.awaySets + 1).coerceAtMost(3))
            "set_minus" -> if (home) s.copy(homeSets = (s.homeSets - 1).coerceAtLeast(0)) else s.copy(awaySets = (s.awaySets - 1).coerceAtLeast(0))
            // Basket : fautes d'équipe
            "foul_plus" -> if (home) s.copy(homeFouls = (s.homeFouls + 1).coerceAtMost(15)) else s.copy(awayFouls = (s.awayFouls + 1).coerceAtMost(15))
            "foul_minus" -> if (home) s.copy(homeFouls = (s.homeFouls - 1).coerceAtLeast(0)) else s.copy(awayFouls = (s.awayFouls - 1).coerceAtLeast(0))
            // Basket, hand : temps morts (le chrono s'arrête)
            "timeout" -> {
                if (s.timeouts(team) >= s.timeoutsAllowed) return null
                val stopped = stopClock(s, now).copy(timeoutTeam = team, timeoutAt = now)
                if (home) stopped.copy(homeTimeouts = s.homeTimeouts + 1) else stopped.copy(awayTimeouts = s.awayTimeouts + 1)
            }
            "timeout_minus" -> {
                if (s.timeouts(team) == 0) return null
                val n = if (home) s.copy(homeTimeouts = s.homeTimeouts - 1) else s.copy(awayTimeouts = s.awayTimeouts - 1)
                if (s.timeoutTeam == team) n.copy(timeoutTeam = null) else n
            }
            // Hand : exclusions de 2 minutes (en temps de jeu)
            "susp" -> s.copy(suspensions = s.suspensions + Suspension(team, s.gameMs(now), now))
            "susp_minus" -> {
                val active = s.suspensions.withIndex().filter {
                    it.value.team == team && it.value.startGameMs + ScoreState.SUSPENSION_MS > s.gameMs(now)
                }
                val last = active.lastOrNull() ?: return null
                s.copy(suspensions = s.suspensions.filterIndexed { i, _ -> i != last.index })
            }
            else -> null
        }
    }

    fun configure(
        homeName: String?, awayName: String?, competition: String?, periodMinutes: Int?, awayColor: Int? = null,
    ) = synchronized(lock) {
        val s = state
        val next = s.copy(
            homeName = homeName?.trim()?.takeIf { it.isNotEmpty() }?.take(20) ?: s.homeName,
            awayName = awayName?.trim()?.takeIf { it.isNotEmpty() }?.take(20) ?: s.awayName,
            competition = competition?.trim()?.take(60) ?: s.competition,
            periodMinutes = periodMinutes?.takeIf { s.sport.hasClock }?.coerceIn(s.sport.periodRange) ?: s.periodMinutes,
            awayColor = awayColor ?: s.awayColor,
        )
        if (next != s) {
            push(s)
            commit(next)
        }
    }

    private fun stopClock(s: ScoreState, now: Long): ScoreState {
        if (!s.clockRunning) return s
        var elapsed = s.elapsedMs(now)
        if (s.sport.autoStop) elapsed = elapsed.coerceAtMost(s.periodLengthMs)
        return s.copy(clockRunning = false, clockBaseMs = elapsed, clockStartedAt = 0)
    }

    /** Fin de période, selon le sport. Renvoie null si l'action n'a pas de sens. */
    private fun endPeriod(s: ScoreState, now: Long): ScoreState? {
        val stopped = stopClock(s, now).copy(timeoutTeam = null)
        fun nextPeriod(p: Int) = stopped.copy(period = p, clockBaseMs = 0)
        return when (s.sport) {
            Sport.VOLLEY -> endSet(s)
            Sport.FOOT -> when (s.period) {
                1 -> nextPeriod(2).copy(scene = Scene.HALFTIME)
                3 -> nextPeriod(4)
                else -> stopped.copy(finished = true, scene = Scene.OUTRO)
            }
            Sport.HAND -> when (s.period) {
                1 -> nextPeriod(2).copy(scene = Scene.HALFTIME)
                else -> stopped.copy(finished = true, scene = Scene.OUTRO)
            }
            Sport.BASKET -> when {
                // Mi-temps après le 2e quart-temps ; nouvelles fautes à chaque quart-temps,
                // nouveaux temps morts en 2e mi-temps
                s.period == 2 -> nextPeriod(3).copy(
                    scene = Scene.HALFTIME, homeFouls = 0, awayFouls = 0, homeTimeouts = 0, awayTimeouts = 0,
                )
                s.period < 4 -> nextPeriod(s.period + 1).copy(homeFouls = 0, awayFouls = 0)
                else -> stopped.copy(finished = true, scene = Scene.OUTRO)
            }
        }
    }

    /** Volley : le set est attribué à l'équipe qui mène, puis on passe au set suivant. */
    private fun endSet(s: ScoreState): ScoreState? {
        val winner = when {
            s.homeScore > s.awayScore -> Team.HOME
            s.awayScore > s.homeScore -> Team.AWAY
            else -> return null
        }
        val homeSets = s.homeSets + if (winner == Team.HOME) 1 else 0
        val awaySets = s.awaySets + if (winner == Team.AWAY) 1 else 0
        val over = homeSets >= 3 || awaySets >= 3
        return s.copy(
            homeSets = homeSets,
            awaySets = awaySets,
            homeScore = 0,
            awayScore = 0,
            setScores = s.setScores + (s.homeScore to s.awayScore),
            setNumber = if (over) s.setNumber else s.setNumber + 1,
            serving = null,
            finished = over,
            scene = if (over) Scene.OUTRO else s.scene,
        )
    }

    /** Fin du temps de la période (basket, hand) : le chrono s'arrête pile à 0 / 30:00. */
    private fun onPeriodTimeUp() = synchronized(lock) {
        val s = state
        if (!s.clockRunning || !s.sport.autoStop) return@synchronized
        val now = now()
        if (s.elapsedMs(now) < s.periodLengthMs) {
            scheduleAutoStop(s)
            return@synchronized
        }
        commit(s.copy(clockRunning = false, clockBaseMs = s.periodLengthMs, clockStartedAt = 0))
    }

    private fun scheduleAutoStop(s: ScoreState) {
        mainHandler.removeCallbacks(autoStop)
        if (s.clockRunning && s.sport.autoStop) {
            val remaining = s.periodLengthMs - s.elapsedMs(now())
            mainHandler.postDelayed(autoStop, remaining.coerceAtLeast(0L))
        }
    }

    private fun push(s: ScoreState) {
        history.addLast(s)
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    private fun commit(next: ScoreState) {
        state = next
        version++
        scheduleAutoStop(next)
        notifyListeners(next)
    }

    private fun notifyListeners(s: ScoreState) {
        mainHandler.post { listeners.forEach { it(s) } }
    }
}
