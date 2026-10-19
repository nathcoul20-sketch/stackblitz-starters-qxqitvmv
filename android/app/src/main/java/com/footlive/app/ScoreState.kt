package com.footlive.app

import org.json.JSONArray
import org.json.JSONObject

enum class Team { HOME, AWAY }

/** Ce qui est diffusé : écran d'attente, match (caméra + score), mi-temps, ou écran de fin. */
enum class Scene { INTRO, MATCH, HALFTIME, OUTRO }

/**
 * Un but. [minute] est la minute affichée (« 23' », « 45+2' »), vide si le chrono n'a pas été lancé.
 * [at] : instant du but (SystemClock.elapsedRealtime), sert à afficher le bandeau « BUT ! ».
 */
data class Goal(val team: Team, val minute: String, val scorer: String = "", val at: Long = 0L)

/**
 * État complet du match, immuable : chaque action produit un nouvel état.
 *
 * Chrono : le temps écoulé dans la période en cours vaut [clockBaseMs] (+ le temps depuis
 * [clockStartedAt] s'il tourne). Les instants sont en SystemClock.elapsedRealtime() du téléphone
 * qui filme, seule horloge de référence (la télécommande ne fait qu'afficher).
 */
data class ScoreState(
    val homeName: String = "MAYBÉLÉGER FC",
    val awayName: String = "VISITEUR",
    val competition: String = "Départemental 2",
    val homeGoals: Int = 0,
    val awayGoals: Int = 0,
    val goals: List<Goal> = emptyList(),
    val homeRed: Int = 0,
    val awayRed: Int = 0,
    /** 1 et 2 : mi-temps ; 3 et 4 : prolongations. */
    val period: Int = 1,
    /** Durée d'une mi-temps en minutes (45 en seniors, moins chez les jeunes). */
    val halfLength: Int = 45,
    val clockRunning: Boolean = false,
    val clockBaseMs: Long = 0L,
    val clockStartedAt: Long = 0L,
    /** Le match est terminé (« Fin du match » validé). */
    val finished: Boolean = false,
    /** Séance de tirs au but en cours / jouée. */
    val penalties: Boolean = false,
    val homePens: Int = 0,
    val awayPens: Int = 0,
    val overlayVisible: Boolean = true,
    val scene: Scene = Scene.INTRO,
) {
    /** Durée d'une prolongation : 15 min en seniors, un tiers de la mi-temps chez les jeunes. */
    val extraLength: Int get() = if (halfLength >= 45) 15 else maxOf(5, halfLength / 3)

    val periodLengthMs: Long get() = (if (period <= 2) halfLength else extraLength) * 60_000L

    /** Minutes déjà jouées avant la période en cours (2e mi-temps : 45, 1re prolongation : 90…). */
    val periodOffsetMs: Long
        get() = when (period) {
            1 -> 0
            2 -> halfLength
            3 -> 2 * halfLength
            else -> 2 * halfLength + extraLength
        } * 60_000L

    /** Le chrono a-t-il déjà tourné dans cette période ? */
    val clockStarted: Boolean get() = clockRunning || clockBaseMs > 0

    fun elapsedMs(now: Long): Long =
        clockBaseMs + if (clockRunning) (now - clockStartedAt).coerceAtLeast(0L) else 0L

    /** Temps affiché par le chrono principal, bloqué à la fin réglementaire de la période (« 45:00 »). */
    fun clockText(now: Long): String =
        formatMs(periodOffsetMs + elapsedMs(now).coerceAtMost(periodLengthMs))

    /** Temps additionnel écoulé (« +2:13 »), ou null tant que la durée réglementaire n'est pas atteinte. */
    fun addedText(now: Long): String? {
        val extra = elapsedMs(now) - periodLengthMs
        if (extra < 0 || !clockStarted) return null
        val total = extra / 1000
        return String.format(java.util.Locale.ROOT, "+%d:%02d", total / 60, total % 60)
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

    val periodLabel: String
        get() = when {
            penalties -> "Tirs au but"
            finished -> "Terminé"
            period == 1 -> "1re mi-temps"
            period == 2 -> "2e mi-temps"
            period == 3 -> "1re prolongation"
            else -> "2e prolongation"
        }

    /** Libellé du bouton qui termine la période en cours. */
    val endPeriodLabel: String
        get() = when (period) {
            1 -> "Fin 1re mi-temps"
            3 -> "Fin 1re prolongation"
            else -> "Fin du match"
        }

    /** Buteurs d'une équipe, regroupés : « DUPONT 12', 67' ». Les buts sans buteur sont ignorés. */
    fun scorers(team: Team): List<String> =
        goals.filter { it.team == team && it.scorer.isNotBlank() }
            .groupBy { it.scorer.trim().uppercase() }
            .map { (name, list) ->
                val minutes = list.map { it.minute }.filter { it.isNotEmpty() }
                if (minutes.isEmpty()) name else "$name ${minutes.joinToString(", ")}"
            }

    fun toJson(now: Long): JSONObject = JSONObject().apply {
        put("homeName", homeName)
        put("awayName", awayName)
        put("competition", competition)
        put("homeGoals", homeGoals)
        put("awayGoals", awayGoals)
        put("goals", JSONArray(goals.map {
            JSONObject().put("team", it.team.name.lowercase()).put("minute", it.minute).put("scorer", it.scorer)
        }))
        put("homeRed", homeRed)
        put("awayRed", awayRed)
        put("period", period)
        put("periodLabel", periodLabel)
        put("endPeriodLabel", endPeriodLabel)
        put("halfLength", halfLength)
        put("clockRunning", clockRunning)
        put("clockStarted", clockStarted)
        put("elapsedMs", elapsedMs(now))
        put("periodLengthMs", periodLengthMs)
        put("periodOffsetMs", periodOffsetMs)
        put("finished", finished)
        put("penalties", penalties)
        put("homePens", homePens)
        put("awayPens", awayPens)
        put("overlayVisible", overlayVisible)
        put("scene", scene.name.lowercase())
    }

    companion object {
        fun formatMs(ms: Long): String {
            val total = ms / 1000
            return String.format(java.util.Locale.ROOT, "%02d:%02d", total / 60, total % 60)
        }
    }
}
