package com.scorecast.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock

/** Aperçus affichés dans les écrans de réglage (rendus avec le même code que le live). */
object Previews {

    /** Un match fictif mais crédible pour l'aperçu, selon le sport. */
    fun sampleState(
        sport: Sport, homeName: String, homeColor: Int, homeAccent: Int,
        awayName: String = "ADVERSAIRE", awayColor: Int = 0xFF2B2B33.toInt(), competition: String = "",
    ): ScoreState {
        val base = ScoreState(
            sport = sport,
            homeName = homeName.ifBlank { "MON CLUB" },
            awayName = awayName.ifBlank { "ADVERSAIRE" },
            competition = competition,
            homeColor = homeColor,
            homeAccent = homeAccent,
            awayColor = awayColor,
            scene = Scene.MATCH,
            // Chrono « en marche » (affiché en blanc), figé à l'instant de l'aperçu
            clockRunning = sport.hasClock,
            clockStartedAt = SystemClock.elapsedRealtime(),
        )
        return when (sport) {
            Sport.FOOT -> base.copy(homeScore = 2, awayScore = 1, period = 2, clockBaseMs = 22 * 60_000L + 14_000)
            Sport.HAND -> base.copy(homeScore = 17, awayScore = 15, period = 2, clockBaseMs = 11 * 60_000L + 42_000, homeTimeouts = 1)
            Sport.BASKET -> base.copy(homeScore = 58, awayScore = 54, period = 3, clockBaseMs = 3 * 60_000L + 38_000, homeFouls = 3, awayFouls = 5, awayTimeouts = 1)
            Sport.VOLLEY -> base.copy(homeScore = 18, awayScore = 15, homeSets = 1, awaySets = 1, setNumber = 3, serving = Team.HOME)
        }
    }

    /** Tableau de score seul, recadré (environ 1100 × 170 dans le repère 1920 × 1080). */
    fun scoreboard(context: Context, state: ScoreState, logo: Bitmap?): Bitmap {
        val full = OverlayRenderer(context, 1280, 720, logo).renderScoreboard(state, SystemClock.elapsedRealtime())
        val k = 1280 / 1920f
        val w = (1130 * k).toInt()
        val h = ((if (state.sport == Sport.BASKET || state.sport == Sport.HAND) 200 else 170) * k).toInt()
        return Bitmap.createBitmap(full, 0, 0, w, h)
    }

    /** Écran généré (intro / pause / fin), en réduction. */
    fun screen(context: Context, state: ScoreState, scene: Scene, logo: Bitmap?): Bitmap =
        OverlayRenderer(context, 640, 360, logo).renderGeneratedScreen(state, scene)
}
