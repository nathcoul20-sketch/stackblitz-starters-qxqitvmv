package com.footlive.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/**
 * Dessine ce qui est incrusté par-dessus la vidéo, dans un bitmap transparent de la taille du flux :
 * - en match : le tableau de score (en haut à gauche), le chrono, et le bandeau « BUT ! » ;
 * - sur les écrans Mi-temps et Fin : les noms, le score, les tirs au but et les buteurs.
 *
 * Toutes les coordonnées sont exprimées dans le repère 1920x1080 du design, puis mises à l'échelle :
 * le rendu est identique en 720p et en 1080p. Aperçu sur ordinateur : tools/visuels/overlay_preview.py
 * (mêmes cotes).
 */
class OverlayRenderer(context: Context, private val width: Int, private val height: Int) {

    private val bebas = Typeface.createFromAsset(context.assets, "fonts/BebasNeue-Regular.ttf")
    private val josefin = Typeface.Builder(context.assets, "fonts/JosefinSans.ttf")
        .setFontVariationSettings("'wght' 600")
        .build() ?: Typeface.DEFAULT_BOLD

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Bitmap entièrement transparent (écran d'intro, overlay masqué). */
    fun empty(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    /**
     * Ce qui change à l'écran en match : sert à ne redessiner le tableau de score
     * que lorsque l'affichage change (chaque seconde quand le chrono tourne).
     */
    fun scoreboardSignature(s: ScoreState, now: Long): String =
        "${s.clockText(now)}|${s.addedText(now)}|${goalBanner(s, now) != null}"

    fun renderScoreboard(s: ScoreState, now: Long): Bitmap {
        val bitmap = empty()
        if (!s.overlayVisible) return bitmap
        val c = designCanvas(bitmap)
        val x = X0
        val nameHome = x
        val score = x + NAME_W
        val nameAway = score + SCORE_W
        val clock = nameAway + NAME_W
        val bottom = Y0 + ROW

        rect(c, nameHome, Y0, NAME_W, ROW, BLACK)
        rect(c, score, Y0, SCORE_W, ROW, YELLOW)
        rect(c, nameAway, Y0, NAME_W, ROW, BLACK)
        rect(c, clock, Y0, CLOCK_W, ROW, NAVY)
        // Liseré jaune sous les noms
        rect(c, nameHome, bottom - 4, NAME_W, 4f, YELLOW)
        rect(c, nameAway, bottom - 4, NAME_W, 4f, YELLOW)

        // Cartons rouges, côté score
        for (i in 0 until s.homeRed) rect(c, score - 14 - i * 16 - 12, Y0 + 14, 12f, 20f, RED)
        for (i in 0 until s.awayRed) rect(c, nameAway + 14 + i * 16, Y0 + 14, 12f, 20f, RED)
        val homeCards = if (s.homeRed > 0) 18f + 16 * s.homeRed else 0f
        val awayCards = if (s.awayRed > 0) 18f + 16 * s.awayRed else 0f

        drawText(c, s.homeName.uppercase(), nameHome + NAME_W / 2 - homeCards / 2, Y0 + 48, 46f, bebas, WHITE,
            maxWidth = NAME_W - 28 - homeCards)
        drawText(c, s.awayName.uppercase(), nameAway + NAME_W / 2 + awayCards / 2, Y0 + 48, 46f, bebas, WHITE,
            maxWidth = NAME_W - 28 - awayCards)
        drawText(c, "${s.homeGoals} - ${s.awayGoals}", score + SCORE_W / 2, Y0 + 52, 60f, bebas, Color.BLACK,
            maxWidth = SCORE_W - 16)
        drawText(c, s.clockText(now), clock + CLOCK_W / 2, Y0 + 48, 46f, bebas, WHITE)
        s.addedText(now)?.let { added ->
            rect(c, clock + CLOCK_W, Y0, ADDED_W, ROW, YELLOW)
            drawText(c, added, clock + CLOCK_W + ADDED_W / 2, Y0 + 48, 42f, bebas, Color.BLACK, maxWidth = ADDED_W - 12)
        }

        // Bandeau : période · compétition
        val w = 2 * NAME_W + SCORE_W
        var y = bottom
        rect(c, x, y, w, SUB, SUB_BG)
        val label = s.periodLabel.uppercase()
        val labelWidth = drawText(c, label, x + 16, y + 24, 19f, josefin, YELLOW, align = Paint.Align.LEFT)
        if (s.competition.isNotBlank()) {
            drawText(c, "·  " + s.competition, x + 16 + labelWidth + 12, y + 24, 19f, josefin, WHITE,
                maxWidth = w - labelWidth - 44, align = Paint.Align.LEFT)
        }
        y += SUB

        if (s.penalties) {
            rect(c, x, y, w, 40f, YELLOW)
            drawText(c, "TIRS AU BUT", x + 16, y + 30, 30f, bebas, Color.BLACK, align = Paint.Align.LEFT)
            drawText(c, "${s.homePens} - ${s.awayPens}", x + w - 16, y + 31, 34f, bebas, Color.BLACK,
                align = Paint.Align.RIGHT)
            y += 40
        }

        goalBanner(s, now)?.let { goal ->
            y += 12
            val h = 72f
            polygon(c, YELLOW, x, y, x + 170, y, x + 150, y + h, x, y + h)
            polygon(c, BLACK, x + 176, y, x + w, y, x + w, y + h, x + 156, y + h)
            drawText(c, "BUT !", x + 80, y + 56, 62f, bebas, Color.BLACK)
            val team = if (goal.team == Team.HOME) s.homeName else s.awayName
            val detail = listOf(goal.scorer.uppercase(), goal.minute).filter { it.isNotBlank() }.joinToString("   ")
            if (detail.isEmpty()) {
                drawText(c, team.uppercase(), x + 186, y + 52, 44f, bebas, WHITE, maxWidth = w - 210, align = Paint.Align.LEFT)
            } else {
                drawText(c, team.uppercase(), x + 186, y + 36, 34f, bebas, WHITE, maxWidth = w - 210, align = Paint.Align.LEFT)
                drawText(c, detail, x + 186, y + 62, 22f, josefin, YELLOW, maxWidth = w - 210, align = Paint.Align.LEFT)
            }
        }
        return bitmap
    }

    /** Textes posés sur les vidéos Mi-temps et Fin (cartouches dessinés dans la vidéo). */
    fun renderResult(s: ScoreState): Bitmap {
        val bitmap = empty()
        val c = designCanvas(bitmap)
        val cy = (PLATE_TOP + PLATE_BOTTOM) / 2
        val lx = (PLATE_L_START + PLATE_L_END) / 2
        val rx = (PLATE_R_START + PLATE_R_END) / 2
        drawText(c, s.homeName.uppercase(), lx, cy + 29, 84f, bebas, WHITE, maxWidth = 500f)
        drawText(c, s.awayName.uppercase(), rx, cy + 29, 84f, bebas, WHITE, maxWidth = 500f)
        drawText(c, "${s.homeGoals} - ${s.awayGoals}", 960f, cy + 82, 240f, bebas, YELLOW, maxWidth = 420f)
        if (s.penalties || s.homePens + s.awayPens > 0) {
            drawText(c, "T.A.B. ${s.homePens} - ${s.awayPens}", 960f, cy + 150, 40f, josefin, YELLOW, maxWidth = 420f)
        }
        for ((xCenter, lines) in listOf(lx to s.scorers(Team.HOME), rx to s.scorers(Team.AWAY))) {
            var y = 612f
            for (line in lines.take(7)) {
                drawText(c, line, xCenter, y, 34f, josefin, WHITE, maxWidth = 540f)
                y += 46
            }
        }
        return bitmap
    }

    /** Dernier but s'il a été marqué il y a moins de [GOAL_BANNER_MS] (bandeau « BUT ! »). */
    private fun goalBanner(s: ScoreState, now: Long): Goal? =
        s.goals.lastOrNull()?.takeIf { !s.penalties && now - it.at in 0 until GOAL_BANNER_MS }

    private fun designCanvas(bitmap: Bitmap) = Canvas(bitmap).apply {
        scale(width / 1920f, height / 1080f)
    }

    private fun rect(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        fill.color = color
        c.drawRect(x, y, x + w, y + h, fill)
    }

    private fun polygon(c: Canvas, color: Int, vararg pts: Float) {
        val path = Path().apply {
            moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) {
                lineTo(pts[i], pts[i + 1]); i += 2
            }
            close()
        }
        fill.color = color
        c.drawPath(path, fill)
    }

    /** Écrit un texte, réduit s'il dépasse [maxWidth] (et recentré verticalement). Renvoie sa largeur. */
    private fun drawText(
        canvas: Canvas,
        value: String,
        x: Float,
        baseline: Float,
        size: Float,
        typeface: Typeface,
        color: Int,
        maxWidth: Float = Float.MAX_VALUE,
        align: Paint.Align = Paint.Align.CENTER,
    ): Float {
        text.typeface = typeface
        text.textSize = size
        text.color = color
        text.textAlign = align
        var measured = text.measureText(value)
        var y = baseline
        if (measured > maxWidth) {
            text.textSize = size * maxWidth / measured
            y -= (size - text.textSize) * 0.35f
            measured = maxWidth
        }
        canvas.drawText(value, x, y, text)
        return measured
    }

    companion object {
        private val YELLOW = Color.rgb(255, 241, 0)
        private val NAVY = Color.rgb(52, 52, 100)
        private val BLACK = Color.argb(235, 0, 0, 0)
        private val SUB_BG = Color.argb(190, 0, 0, 0)
        private val RED = Color.rgb(227, 6, 19)
        private val WHITE = Color.WHITE

        // Tableau de score (repère 1920x1080)
        private const val X0 = 48f
        private const val Y0 = 40f
        private const val ROW = 64f
        private const val NAME_W = 250f
        private const val SCORE_W = 150f
        private const val CLOCK_W = 140f
        private const val ADDED_W = 120f
        private const val SUB = 34f

        // Cartouches des écrans Mi-temps / Fin (identiques à tools/visuels/gen_screens.py)
        private const val PLATE_TOP = 420f
        private const val PLATE_BOTTOM = 524f
        private const val PLATE_L_START = 150f
        private const val PLATE_L_END = 730f
        private const val PLATE_R_START = 1190f
        private const val PLATE_R_END = 1770f

        const val GOAL_BANNER_MS = 10_000L
    }
}
