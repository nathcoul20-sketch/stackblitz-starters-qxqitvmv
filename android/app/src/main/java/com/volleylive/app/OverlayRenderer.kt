package com.volleylive.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Dessine ce qui est incrusté par-dessus la vidéo, dans un bitmap transparent de la taille du flux :
 * - en match : le tableau de score (fond = scoreboard.png fourni, textes dessinés par-dessus) ;
 * - sur l'écran de fin : les noms, les sets gagnés et le détail des sets.
 *
 * Toutes les coordonnées sont exprimées dans le repère 1920x1080 du design, puis mises à l'échelle :
 * le rendu est identique en 720p et en 1080p.
 */
class OverlayRenderer(context: Context, private val width: Int, private val height: Int) {

    private val scoreboard: Bitmap = BitmapFactory.decodeResource(
        context.resources, R.drawable.scoreboard, BitmapFactory.Options().apply { inScaled = false }
    )

    private val bebas = Typeface.createFromAsset(context.assets, "fonts/BebasNeue-Regular.ttf")
    private val josefin = Typeface.Builder(context.assets, "fonts/JosefinSans.ttf")
        .setFontVariationSettings("'wght' 600")
        .build() ?: Typeface.DEFAULT_BOLD

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    /** Bitmap entièrement transparent (écran d'intro, overlay masqué). */
    fun empty(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    fun renderScoreboard(s: ScoreState): Bitmap {
        val bitmap = empty()
        if (!s.overlayVisible) return bitmap
        val canvas = designCanvas(bitmap)
        canvas.drawBitmap(scoreboard, 0f, 0f, bitmapPaint)

        // Noms des équipes
        drawText(canvas, s.homeName.uppercase(), 472f, 66f, 66f, bebas, maxWidth = 185f)
        drawText(canvas, s.awayName.uppercase(), 1430f, 66f, 66f, bebas, maxWidth = 185f)

        // Sets gagnés (sous les libellés « Set » du fond)
        drawText(canvas, s.homeSets.toString(), 666f, 104f, 70f, bebas)
        drawText(canvas, s.awaySets.toString(), 1243f, 104f, 70f, bebas)

        // Points du set en cours
        drawText(canvas, s.homeScore.toString(), 815f, 106f, 112f, bebas)
        drawText(canvas, s.awayScore.toString(), 1113f, 106f, 112f, bebas)

        // Onglet du haut : numéro du set
        val suffix = if (s.setNumber == 1) "er" else "e"
        drawText(canvas, "${s.setNumber} $suffix Set", 960f, 24f, 24f, josefin, maxWidth = 150f)

        // Bandeau du bas : compétition
        if (s.competition.isNotBlank()) {
            drawText(canvas, s.competition, 960f, 141f, 21f, josefin, maxWidth = 350f)
        }

        // Indicateur de service : petit point blanc à côté du score de l'équipe au service
        s.serving?.let { team ->
            canvas.drawCircle(if (team == Team.HOME) 905f else 1015f, 96f, 9f, dot)
        }
        return bitmap
    }

    /** Textes posés sur la vidéo de fin (« Le match est fini — Résultat : »). */
    fun renderOutro(s: ScoreState): Bitmap {
        val bitmap = empty()
        val canvas = designCanvas(bitmap)

        // Noms sur les deux coups de pinceau rouges
        drawText(canvas, s.homeName.uppercase(), 362f, 540f, 76f, bebas, maxWidth = 400f)
        drawText(canvas, s.awayName.uppercase(), 1582f, 548f, 76f, bebas, maxWidth = 400f)

        // Nombre de sets gagnés, en grand
        drawText(canvas, s.homeSets.toString(), 362f, 790f, 210f, bebas)
        drawText(canvas, s.awaySets.toString(), 1582f, 790f, 210f, bebas)

        // Détail des sets (+ set en cours s'il n'a pas été validé)
        val sets = s.setScores +
            if (s.homeScore + s.awayScore > 0) listOf(s.homeScore to s.awayScore) else emptyList()
        if (sets.isNotEmpty()) {
            val detail = sets.joinToString("   ") { "${it.first}-${it.second}" }
            drawText(canvas, detail, 960f, 1000f, 44f, josefin, maxWidth = 1000f)
        }
        return bitmap
    }

    private fun designCanvas(bitmap: Bitmap) = Canvas(bitmap).apply {
        scale(width / 1920f, height / 1080f)
    }

    private fun drawText(
        canvas: Canvas,
        value: String,
        x: Float,
        baseline: Float,
        size: Float,
        typeface: Typeface,
        maxWidth: Float = Float.MAX_VALUE,
    ) {
        text.typeface = typeface
        text.textSize = size
        // Réduit la taille si le texte est trop long pour sa case
        val measured = text.measureText(value)
        if (measured > maxWidth) text.textSize = size * maxWidth / measured
        canvas.drawText(value, x, baseline, text)
    }
}
