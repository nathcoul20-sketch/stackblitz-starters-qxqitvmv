package com.scorecast.app

/**
 * Les sports gérés et leurs règles « essentielles ».
 *
 * [periodMinutes] : durée par défaut d'une période (mi-temps, quart-temps) ; [periodRange] : réglage autorisé.
 * [countdown] : le chrono décompte (basket) au lieu de monter (foot, hand).
 * [autoStop] : le chrono s'arrête tout seul à la fin de la période (basket, hand). Au foot il continue
 * (temps additionnel).
 */
enum class Sport(
    val id: String,
    val label: String,
    val emoji: String,
    val hasClock: Boolean,
    val periodMinutes: Int,
    val periodRange: IntRange,
    val regularPeriods: Int,
    val countdown: Boolean,
    val autoStop: Boolean,
) {
    FOOT("foot", "Football", "⚽", true, 45, 10..45, 2, countdown = false, autoStop = false),
    VOLLEY("volley", "Volley-ball", "🏐", false, 0, 0..0, 0, countdown = false, autoStop = false),
    BASKET("basket", "Basket-ball", "🏀", true, 10, 4..12, 4, countdown = true, autoStop = true),
    HAND("hand", "Handball", "🤾", true, 30, 10..30, 2, countdown = false, autoStop = true);

    /** Nom d'une période, pour les réglages : « mi-temps », « quart-temps ». */
    val periodNoun: String
        get() = if (this == BASKET) "quart-temps" else "mi-temps"

    /** Libellé de l'écran de pause (scène HALFTIME). */
    val breakLabel: String
        get() = if (this == VOLLEY) "Pause" else "Mi-temps"

    /** Ce que l'on marque : but, point. */
    val scoreNoun: String
        get() = when (this) {
            FOOT, HAND -> "but"
            VOLLEY, BASKET -> "point"
        }

    companion object {
        fun fromId(id: String?): Sport = entries.firstOrNull { it.id == id } ?: FOOT
    }
}
