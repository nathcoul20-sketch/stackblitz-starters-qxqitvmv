package com.volleylive.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/**
 * Dessine le tableau de score dans un bitmap transparent de la taille de la vidéo.
 * Le bitmap est ensuite plaqué par-dessus l'image caméra (aperçu + live).
 *
 * Toutes les coordonnées sont exprimées dans un repère 1920x1080, puis mises à l'échelle :
 * le rendu est identique en 720p et en 1080p.
 *
 * C'est ce fichier qu'il faudra adapter pour coller exactement au design final de l'overlay.
 */
class OverlayRenderer(private val width: Int, private val height: Int) {

    private val condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    private val homeName = Color.parseColor("#D00A0A")
    private val homeBlock = Color.parseColor("#B00000")
    private val awayBlock = Color.parseColor("#8F8F8F")
    private val awayName = Color.parseColor("#A8A8A8")
    private val dark = Color.parseColor("#3A3A3A")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f
        strokeJoin = Paint.Join.MITER
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    fun render(s: ScoreState): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (!s.overlayVisible) return bitmap
        val canvas = Canvas(bitmap)
        canvas.scale(width / 1920f, height / 1080f)

        // Bandeau compétition (dessiné d'abord, sous les blocs de score)
        if (s.competition.isNotBlank()) {
            shape(canvas, dark, 755f to 112f, 1165f to 112f, 1140f to 146f, 780f to 146f)
            drawText(canvas, s.competition, 960f, 138f, 22f, regular, maxWidth = 360f)
        }

        // Nom équipe domicile
        shape(canvas, homeName, 370f to 0f, 575f to 0f, 575f to 80f, 395f to 80f, 370f to 55f)
        drawText(canvas, s.homeName.uppercase(), 475f, 62f, 54f, condensedBold, maxWidth = 185f)

        // Bloc score domicile
        shape(canvas, homeBlock, 578f to 0f, 960f to 0f, 960f to 118f, 615f to 118f, 578f to 85f)
        drawText(canvas, "Set", 666f, 32f, 24f, regular)
        drawText(canvas, s.homeSets.toString(), 666f, 100f, 64f, condensedBold)
        drawText(canvas, s.homeScore.toString(), 815f, 104f, 104f, condensedBold)

        // Bloc score extérieur
        shape(canvas, awayBlock, 960f to 0f, 1325f to 0f, 1325f to 85f, 1290f to 118f, 960f to 118f)
        drawText(canvas, s.awaySets.toString(), 1243f, 100f, 64f, condensedBold)
        drawText(canvas, "Set", 1243f, 32f, 24f, regular)
        drawText(canvas, s.awayScore.toString(), 1113f, 104f, 104f, condensedBold)

        // Nom équipe extérieure
        shape(canvas, awayName, 1328f to 0f, 1533f to 0f, 1533f to 55f, 1508f to 80f, 1328f to 80f)
        drawText(canvas, s.awayName.uppercase(), 1430f, 62f, 54f, condensedBold, maxWidth = 185f)

        // Onglet numéro du set
        shape(canvas, dark, 862f to 0f, 1058f to 0f, 1030f to 32f, 890f to 32f)
        val suffix = if (s.setNumber == 1) "er" else "e"
        drawText(canvas, "${s.setNumber} $suffix Set", 960f, 25f, 24f, condensedBold)

        // Indicateur de service : petit point blanc à côté du score de l'équipe au service
        s.serving?.let { team ->
            fill.color = Color.WHITE
            val x = if (team == Team.HOME) 900f else 1020f
            canvas.drawCircle(x, 95f, 9f, fill)
        }

        return bitmap
    }

    private fun shape(canvas: Canvas, color: Int, vararg points: Pair<Float, Float>) {
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) lineTo(points[i].first, points[i].second)
            close()
        }
        fill.color = color
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
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
