package com.scorecast.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class Team { HOME, AWAY }

/** Ce qui est diffusé : écran d'attente, match (caméra + score), pause / mi-temps, ou écran de fin. */
enum class Scene { INTRO, MATCH, HALFTIME, OUTRO }

/**
 * Un but (football). [minute] est la minute affichée (« 23' », « 45+2' »), vide si le chrono n'a pas
 * été lancé. [at] : instant du but (SystemClock.elapsedRealtime), sert au bandeau « BUT ! ».
 */
data class Goal(val team: Team, val minute: String, val scorer: String = "", val at: Long = 0L)

/** Exclusion temporaire de 2 minutes (handball), comptée en temps de jeu. */
data class Suspension(val team: Team, val startGameMs: Long, val at: Long)

/**
 * État complet du match, immuable : chaque action produit un nouvel état. Un seul modèle pour
 * tous les sports ; les champs qui ne concernent pas le sport choisi restent à zéro.
 *
 * Chrono (foot, basket, hand) : le temps écoulé dans la période vaut [clockBaseMs] (+ le temps depuis
 * [clockStartedAt] s'il tourne). Les instants sont en SystemClock.elapsedRealtime() du téléphone qui
 * filme, seule horloge de référence (la télécommande ne fait qu'afficher).
 */
data class ScoreState(
    val sport: Sport = Sport.FOOT,
    val homeName: String = "DOMICILE",
    val awayName: String = "VISITEUR",
    val competition: String = "",
    /** Couleur principale du club (case du nom), couleur secondaire (accents), couleur de l'adversaire. */
    val homeColor: Int = 0xFF1E5EFF.toInt(),
    val homeAccent: Int = 0xFFFFFFFF.toInt(),
    val awayColor: Int = 0xFF2B2B33.toInt(),
    val homeScore: Int = 0,
    val awayScore: Int = 0,

    // ---- Chrono et périodes (foot, basket, hand) ----
    /** Foot / hand : 1-2 mi-temps (3-4 prolongations au foot). Basket : 1-4 quarts-temps, 5+ prolongations. */
    val period: Int = 1,
    /** Durée d'une mi-temps ou d'un quart-temps, en minutes. */
    val periodMinutes: Int = sport.periodMinutes,
    val clockRunning: Boolean = false,
    val clockBaseMs: Long = 0L,
    val clockStartedAt: Long = 0L,
    /** Le match est terminé (« Fin du match » validé). */
    val finished: Boolean = false,

    // ---- Football ----
    val goals: List<Goal> = emptyList(),
    val homeRed: Int = 0,
    val awayRed: Int = 0,
    val penalties: Boolean = false,
    val homePens: Int = 0,
    val awayPens: Int = 0,

    // ---- Volley ----
    val homeSets: Int = 0,
    val awaySets: Int = 0,
    val setNumber: Int = 1,
    val serving: Team? = null,
    /** Scores des sets terminés, dans l'ordre (domicile, extérieur). */
    val setScores: List<Pair<Int, Int>> = emptyList(),

    // ---- Basket ----
    /** Fautes d'équipe du quart-temps en cours (les prolongations comptent avec le 4e). */
    val homeFouls: Int = 0,
    val awayFouls: Int = 0,

    // ---- Basket et hand : temps morts ----
    /** Temps morts pris (basket : dans la mi-temps / la prolongation en cours ; hand : dans le match). */
    val homeTimeouts: Int = 0,
    val awayTimeouts: Int = 0,
    /** Dernier temps mort demandé (bandeau « TEMPS MORT »). */
    val timeoutTeam: Team? = null,
    val timeoutAt: Long = 0L,

    // ---- Hand ----
    val suspensions: List<Suspension> = emptyList(),

    val overlayVisible: Boolean = true,
    /** Son du live coupé (micro et musique), en cas de besoin. */
    val muted: Boolean = false,
    val scene: Scene = Scene.INTRO,
) {
    // ---------------------------------------------------------------- Chrono

    /** Prolongation au foot : 15 min en seniors, un tiers de la mi-temps chez les jeunes. Basket : 5 min. */
    val extraMinutes: Int
        get() = when (sport) {
            Sport.FOOT -> if (periodMinutes >= 45) 15 else maxOf(5, periodMinutes / 3)
            Sport.BASKET -> 5
            else -> periodMinutes
        }

    val isExtraPeriod: Boolean
        get() = sport.hasClock && period > sport.regularPeriods

    val periodLengthMs: Long get() = (if (isExtraPeriod) extraMinutes else periodMinutes) * 60_000L

    /**
     * Temps déjà joué avant la période en cours, pour les chronos qui montent
     * (foot 2e mi-temps : 45:00 ; hand 2e mi-temps : 30:00). Toujours 0 au basket (décompte).
     */
    val periodOffsetMs: Long
        get() = when {
            sport == Sport.BASKET -> 0L
            !isExtraPeriod -> (period - 1) * periodMinutes * 60_000L
            else -> (sport.regularPeriods * periodMinutes + (period - sport.regularPeriods - 1) * extraMinutes) * 60_000L
        }

    /** Le chrono a-t-il déjà tourné dans cette période ? */
    val clockStarted: Boolean get() = clockRunning || clockBaseMs > 0

    fun elapsedMs(now: Long): Long =
        clockBaseMs + if (clockRunning) (now - clockStartedAt).coerceAtLeast(0L) else 0L

    /** Temps restant dans la période (basket). */
    fun remainingMs(now: Long): Long = (periodLengthMs - elapsedMs(now)).coerceAtLeast(0L)

    /** Temps de jeu total depuis le début du match, bloqué à la fin de la période (hand : exclusions). */
    fun gameMs(now: Long): Long = periodOffsetMs + elapsedMs(now).coerceAtMost(periodLengthMs)

    /**
     * Temps affiché par le chrono principal.
     * Foot / hand : temps de jeu qui monte, bloqué à la fin réglementaire de la période (« 45:00 »).
     * Basket : temps restant ; sous la minute, avec les dixièmes (« 42.7 »).
     */
    fun clockText(now: Long): String {
        if (sport == Sport.BASKET) {
            val rem = remainingMs(now)
            if (rem < 60_000 && clockStarted) {
                // Arrondi au dixième supérieur : « 0.0 » seulement quand le temps est vraiment écoulé
                val tenths = (rem + 99) / 100
                return String.format(Locale.ROOT, "%d.%d", tenths / 10, tenths % 10)
            }
            return formatMs(rem + 999) // 9:59.2 s'affiche 10:00 → 9:59 ensuite, comme une table de marque
        }
        return formatMs(gameMs(now))
    }

    /** Temps additionnel écoulé au foot (« +2:13 »), ou null tant que la durée réglementaire n'est pas atteinte. */
    fun addedText(now: Long): String? {
        if (sport != Sport.FOOT) return null
        val extra = elapsedMs(now) - periodLengthMs
        if (extra < 0 || !clockStarted) return null
        val total = extra / 1000
        return String.format(Locale.ROOT, "+%d:%02d", total / 60, total % 60)
    }

    /** Minute de jeu au sens du foot : « 1' » dès le coup d'envoi, « 45+2' » dans le temps additionnel. */
    fun minuteText(now: Long): String {
        if (!clockStarted) return ""
        val elapsed = elapsedMs(now)
        val offsetMin = periodOffsetMs / 60_000
        val lengthMin = periodLengthMs / 60_000
        return if (elapsed < periodLengthMs) {
            "${offsetMin + elapsed / 60_000 + 1}'"
        } else {
            "${offsetMin + lengthMin}+${(elapsed - periodLengthMs) / 60_000 + 1}'"
        }
    }

    // ---------------------------------------------------------------- Libellés

    val periodLabel: String
        get() = when {
            sport == Sport.VOLLEY -> if (setNumber == 1) "1er set" else "${setNumber}e set"
            penalties -> "Tirs au but"
            finished -> "Terminé"
            sport == Sport.BASKET -> when {
                period == 1 -> "1er quart-temps"
                period <= 4 -> "${period}e quart-temps"
                period == 5 -> "Prolongation"
                else -> "Prolongation ${period - 4}"
            }
            period == 1 -> "1re mi-temps"
            period == 2 -> "2e mi-temps"
            period == 3 -> "1re prolongation"
            else -> "2e prolongation"
        }

    /** Libellé court pour l'incrustation (« QT2 », « PROL. »), null si le libellé long tient. */
    val periodShort: String
        get() = when {
            sport == Sport.BASKET && !finished && !isExtraPeriod -> "QT$period"
            sport == Sport.BASKET && isExtraPeriod && !finished -> if (period == 5) "PROL." else "PROL. ${period - 4}"
            else -> periodLabel
        }

    /** Libellé du bouton qui termine la période en cours. */
    val endPeriodLabel: String
        get() = when (sport) {
            Sport.VOLLEY -> "Fin du set"
            Sport.BASKET -> when {
                period < 4 -> "Fin du ${if (period == 1) "1er" else "${period}e"} quart-temps"
                else -> "Fin du match"
            }
            Sport.FOOT -> when (period) {
                1 -> "Fin 1re mi-temps"
                3 -> "Fin 1re prolongation"
                else -> "Fin du match"
            }
            Sport.HAND -> if (period == 1) "Fin 1re mi-temps" else "Fin du match"
        }

    // ---------------------------------------------------------------- Volley

    /** Points pour gagner le set en cours (25, ou 15 au tie-break du 5e set). */
    val pointsToWin: Int get() = if (setNumber >= 5) 15 else 25

    /** Équipe qui a gagné le set en cours selon les règles (≥ 25/15 et 2 points d'écart), sinon null. */
    val setWinner: Team?
        get() = when {
            sport != Sport.VOLLEY -> null
            homeScore >= pointsToWin && homeScore - awayScore >= 2 -> Team.HOME
            awayScore >= pointsToWin && awayScore - homeScore >= 2 -> Team.AWAY
            else -> null
        }

    // ---------------------------------------------------------------- Basket / hand

    /** Temps morts autorisés dans la tranche en cours (basket : 2 en 1re mi-temps, 3 en 2e, 1 par prolongation ; hand : 3 par match). */
    val timeoutsAllowed: Int
        get() = when (sport) {
            Sport.BASKET -> when {
                period <= 2 -> 2
                period <= 4 -> 3
                else -> 1
            }
            Sport.HAND -> 3
            else -> 0
        }

    fun fouls(team: Team) = if (team == Team.HOME) homeFouls else awayFouls
    fun timeouts(team: Team) = if (team == Team.HOME) homeTimeouts else awayTimeouts

    /** Au basket, à partir de la 5e faute d'équipe du quart-temps, chaque faute donne des lancers francs. */
    fun bonus(team: Team) = sport == Sport.BASKET && fouls(team) >= 5

    /** Exclusions en cours d'une équipe, avec le temps restant (ms), la plus proche de la fin en premier. */
    fun activeSuspensions(team: Team, now: Long): List<Long> {
        val game = gameMs(now)
        return suspensions.filter { it.team == team }
            .map { it.startGameMs + SUSPENSION_MS - game }
            .filter { it > 0 }
            .sorted()
    }

    // ---------------------------------------------------------------- Divers

    fun name(team: Team) = if (team == Team.HOME) homeName else awayName

    /** Buteurs d'une équipe (foot), regroupés : « DUPONT 12', 67' ». Les buts sans buteur sont ignorés. */
    fun scorers(team: Team): List<String> =
        goals.filter { it.team == team && it.scorer.isNotBlank() }
            .groupBy { it.scorer.trim().uppercase() }
            .map { (name, list) ->
                val minutes = list.map { it.minute }.filter { it.isNotEmpty() }
                if (minutes.isEmpty()) name else "$name ${minutes.joinToString(", ")}"
            }

    fun toJson(now: Long): JSONObject = JSONObject().apply {
        put("sport", sport.id)
        put("homeName", homeName)
        put("awayName", awayName)
        put("competition", competition)
        put("homeColor", Colors.hex(homeColor))
        put("homeAccent", Colors.hex(homeAccent))
        put("awayColor", Colors.hex(awayColor))
        put("homeOnColor", Colors.hex(Colors.onColor(homeColor)))
        put("awayOnColor", Colors.hex(Colors.onColor(awayColor)))
        put("homeScore", homeScore)
        put("awayScore", awayScore)
        put("period", period)
        put("periodLabel", periodLabel)
        put("endPeriodLabel", endPeriodLabel)
        put("periodMinutes", periodMinutes)
        put("periodNoun", sport.periodNoun)
        put("periodRange", JSONArray(listOf(sport.periodRange.first, sport.periodRange.last)))
        put("countdown", sport.countdown)
        put("clockRunning", clockRunning)
        put("clockStarted", clockStarted)
        put("elapsedMs", elapsedMs(now))
        put("periodLengthMs", periodLengthMs)
        put("periodOffsetMs", periodOffsetMs)
        put("finished", finished)
        put("breakLabel", sport.breakLabel)
        // Foot
        put("goals", JSONArray(goals.map {
            JSONObject().put("team", it.team.name.lowercase()).put("minute", it.minute).put("scorer", it.scorer)
        }))
        put("homeRed", homeRed)
        put("awayRed", awayRed)
        put("penalties", penalties)
        put("homePens", homePens)
        put("awayPens", awayPens)
        // Volley
        put("homeSets", homeSets)
        put("awaySets", awaySets)
        put("setNumber", setNumber)
        put("serving", serving?.name?.lowercase() ?: JSONObject.NULL)
        put("setWinner", setWinner?.name?.lowercase() ?: JSONObject.NULL)
        put("setScores", JSONArray(setScores.map { JSONArray(listOf(it.first, it.second)) }))
        // Basket / hand
        put("homeFouls", homeFouls)
        put("awayFouls", awayFouls)
        put("homeTimeouts", homeTimeouts)
        put("awayTimeouts", awayTimeouts)
        put("timeoutsAllowed", timeoutsAllowed)
        put("homeSusp", JSONArray(activeSuspensions(Team.HOME, now)))
        put("awaySusp", JSONArray(activeSuspensions(Team.AWAY, now)))
        put("overlayVisible", overlayVisible)
        put("muted", muted)
        put("scene", scene.name.lowercase())
    }

    companion object {
        const val SUSPENSION_MS = 120_000L

        fun formatMs(ms: Long): String {
            val total = ms / 1000
            return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
        }
    }
}
