package com.volleylive.app

import org.json.JSONArray
import org.json.JSONObject

enum class Team { HOME, AWAY }

/** Ce qui est diffusé : écran d'attente, match (caméra + score), ou écran de fin. */
enum class Scene { INTRO, MATCH, OUTRO }

/** État complet du match, immuable : chaque action produit un nouvel état. */
data class ScoreState(
    val homeName: String = "ECVB",
    val awayName: String = "VISITEUR",
    val competition: String = Prefs.DEFAULT_COMPETITION,
    val homeScore: Int = 0,
    val awayScore: Int = 0,
    val homeSets: Int = 0,
    val awaySets: Int = 0,
    val setNumber: Int = 1,
    val serving: Team? = null,
    val overlayVisible: Boolean = true,
    val scene: Scene = Scene.INTRO,
    /** Son du live coupé (bouton « Couper le son »). */
    val audioMuted: Boolean = false,
    /** Logos des partenaires affichés pendant le match (un à la fois, en bas à droite). */
    val partnersVisible: Boolean = false,
    /** Scores des sets terminés, dans l'ordre (domicile, extérieur). */
    val setScores: List<Pair<Int, Int>> = emptyList(),
) {
    /** Points pour gagner le set en cours (25, ou 15 au tie-break du 5e set). */
    val pointsToWin: Int get() = if (setNumber >= 5) 15 else 25

    /** Équipe qui a gagné le set en cours selon les règles (≥ 25/15 et 2 points d'écart), sinon null. */
    val setWinner: Team?
        get() = when {
            homeScore >= pointsToWin && homeScore - awayScore >= 2 -> Team.HOME
            awayScore >= pointsToWin && awayScore - homeScore >= 2 -> Team.AWAY
            else -> null
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("homeName", homeName)
        put("awayName", awayName)
        put("competition", competition)
        put("homeScore", homeScore)
        put("awayScore", awayScore)
        put("homeSets", homeSets)
        put("awaySets", awaySets)
        put("setNumber", setNumber)
        put("serving", serving?.name?.lowercase() ?: JSONObject.NULL)
        put("overlayVisible", overlayVisible)
        put("setWinner", setWinner?.name?.lowercase() ?: JSONObject.NULL)
        put("scene", scene.name.lowercase())
        put("audioMuted", audioMuted)
        put("partnersVisible", partnersVisible)
        put("setScores", JSONArray(setScores.map { JSONArray(listOf(it.first, it.second)) }))
    }
}
