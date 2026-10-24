package com.volleylive.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Dessine ce qui est incrusté par-dessus la vidéo, dans un bitmap transparent de la taille du flux :
 * - en match : le tableau de score (fond = scoreboard.png fourni, textes dessinés par-dessus) ;
 * - sur l'écran de fin : titre, équipes, sets gagnés, détail des sets, partenaires et compétition,
 *   aux emplacements prévus par le visuel de la vidéo de fin ;
 * - les logos des partenaires (écran de fin, et un à la fois pendant le match si l'option est activée).
 *
 * Toutes les coordonnées sont exprimées dans le repère 1920x1080 du design, puis mises à l'échelle :
 * le rendu est identique en 720p et en 1080p.
 */
class OverlayRenderer(
    context: Context,
    private val width: Int,
    private val height: Int,
    /** Logos des partenaires (écran de fin, et rotation pendant le match si activée). */
    private val partners: List<Bitmap> = emptyList(),
) {

    private val scoreboard: Bitmap = BitmapFactory.decodeResource(
        context.resources, R.drawable.scoreboard, BitmapFactory.Options().apply { inScaled = false }
    )

    private val bebas = Typeface.createFromAsset(context.assets, "fonts/BebasNeue-Regular.ttf")
    private val josefin = Typeface.Builder(context.assets, "fonts/JosefinSans.ttf")
        .setFontVariationSettings("'wght' 600")
        .build() ?: Typeface.DEFAULT_BOLD
    /** Josefin plus fine : textes secondaires de l'écran de fin, comme sur le visuel. */
    private val josefinLight = Typeface.Builder(context.assets, "fonts/JosefinSans.ttf")
        .setFontVariationSettings("'wght' 400")
        .build() ?: Typeface.DEFAULT
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    /** Bitmap entièrement transparent (écran d'intro, overlay masqué). */
    fun empty(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    /** Logo partenaire affiché en ce moment pendant le match (change toutes les 8 s), -1 si aucun. */
    fun partnerIndex(s: ScoreState, now: Long): Int =
        if (s.partnersVisible && partners.isNotEmpty()) ((now / PARTNER_ROTATION_MS) % partners.size).toInt() else -1

    fun renderScoreboard(s: ScoreState, now: Long): Bitmap {
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

        // Partenaires : un logo à la fois, en bas à droite
        val index = partnerIndex(s, now)
        if (index >= 0) partnerTile(canvas, 1920f - 48 - 260f, 1080f - 48 - 150f, 260f, 150f, partners[index])
        return bitmap
    }

    /**
     * Éléments posés sur la vidéo de fin, aux emplacements de son visuel (repère 1920x1080) :
     * étiquette « FIN DU MATCH », plaques des équipes, sets gagnés au centre, détail des sets,
     * logos des partenaires dans le bandeau sombre du bas, compétition dans la case du bas.
     * Les fonds sombres (case du score, bandeaux) font partie de la vidéo.
     */
    fun renderOutro(s: ScoreState): Bitmap {
        val bitmap = empty()
        val c = designCanvas(bitmap)

        // Étiquette blanche du titre
        val title = "FIN DU MATCH"
        text.typeface = bebas
        text.textSize = 72f
        val tw = (text.measureText(title) + 70f).coerceIn(300f, 1400f)
        fill.color = Color.WHITE
        c.drawRect(960 - tw / 2, 320f, 960 + tw / 2, 396f, fill)
        drawText(c, title, 960f, 382f, 72f, bebas, maxWidth = tw - 40, color = Color.BLACK)

        // Plaques des équipes (parallélogrammes + liseré blanc), noms en blanc
        plate(c, 170f, HOME_COLOR)
        plate(c, 1150f, AWAY_COLOR)
        drawText(c, s.homeName.uppercase(), 170f + 300f, 508f, 84f, bebas, maxWidth = 540f)
        drawText(c, s.awayName.uppercase(), 1150f + 300f, 508f, 84f, bebas, maxWidth = 540f)

        // Sets gagnés, en grand au centre
        drawText(c, "${s.homeSets} - ${s.awaySets}", 960f, 520f, 140f, bebas, maxWidth = 330f)

        // Détail des sets (+ set en cours s'il n'a pas été validé)
        val sets = s.setScores +
            if (s.homeScore + s.awayScore > 0) listOf(s.homeScore to s.awayScore) else emptyList()
        if (sets.isNotEmpty()) {
            val detail = sets.joinToString("    ") { "${it.first}-${it.second}" }
            drawText(c, detail, 960f, 591f, 32f, josefinLight, maxWidth = 1500f)
        }

        // Partenaires : jusqu'à 8 logos centrés dans le bandeau sombre
        if (partners.isNotEmpty()) {
            drawText(c, "MERCI À NOS PARTENAIRES", 960f, 830f, 26f, josefinLight, color = PARTNER_LABEL)
            val shown = partners.take(Partners.MAX)
            val tileW = 190f
            val gap = 20f
            var x = 960 - (shown.size * tileW + (shown.size - 1) * gap) / 2
            for (bmp in shown) {
                partnerTile(c, x, 850f, tileW, 110f, bmp)
                x += tileW + gap
            }
        }

        // Compétition, dans la case du bas
        if (s.competition.isNotBlank()) {
            drawText(c, s.competition.uppercase(), 960f, 1035f, 30f, josefinLight, maxWidth = 440f)
        }
        return bitmap
    }

    private fun plate(c: Canvas, x: Float, color: Int) {
        val y = 420f
        val w = 600f
        val h = 120f
        val k = 30f
        polygon(c, color, x + k, y, x + w, y, x + w - k, y + h, x, y + h)
        polygon(c, Color.WHITE, x, y + h - 8, x + w - k, y + h - 8, x + w - k - 2, y + h, x, y + h)
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

    /** Tuile blanche arrondie avec le logo centré dedans (proportions conservées). */
    private fun partnerTile(c: Canvas, x: Float, y: Float, w: Float, h: Float, bmp: Bitmap) {
        fill.color = Color.WHITE
        c.drawRoundRect(x, y, x + w, y + h, 14f, 14f, fill)
        val pad = 14f
        val scale = minOf((w - 2 * pad) / bmp.width, (h - 2 * pad) / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        c.drawBitmap(bmp, null, RectF(x + (w - dw) / 2, y + (h - dh) / 2, x + (w + dw) / 2, y + (h + dh) / 2), bitmapPaint)
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
        color: Int = Color.WHITE,
    ) {
        text.typeface = typeface
        text.textSize = size
        text.color = color
        // Réduit la taille si le texte est trop long pour sa case
        val measured = text.measureText(value)
        if (measured > maxWidth) text.textSize = size * maxWidth / measured
        canvas.drawText(value, x, baseline, text)
    }

    companion object {
        /** Couleurs des plaques sur l'écran de fin (celles du visuel). */
        private val HOME_COLOR = Color.rgb(229, 21, 17)
        private val AWAY_COLOR = Color.rgb(20, 80, 255)
        private val PARTNER_LABEL = Color.rgb(213, 213, 216)
        const val PARTNER_ROTATION_MS = 8_000L
    }
}
