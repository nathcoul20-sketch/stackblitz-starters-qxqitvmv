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
 * - en match : le tableau de score ECVB, dessiné entièrement (net à toutes les résolutions) :
 *   noms, sets gagnés, points, set en cours et compétition, puis le détail des sets terminés ;
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
    /** Ballon blanc (fond transparent) affiché devant le nom de l'équipe au service. */
    private val serveBall: Bitmap = BitmapFactory.decodeResource(
        context.resources, R.drawable.serve_ball, BitmapFactory.Options().apply { inScaled = false }
    )
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    /** Bitmap entièrement transparent (écran d'intro, overlay masqué). */
    fun empty(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    /** Logo partenaire affiché en ce moment pendant le match (change toutes les 8 s), -1 si aucun. */
    fun partnerIndex(s: ScoreState, now: Long): Int =
        if (s.partnersVisible && partners.isNotEmpty()) ((now / PARTNER_ROTATION_MS) % partners.size).toInt() else -1

    /**
     * Tableau de score du match, centré en haut (repère 1920x1080) :
     *  - barre du haut, de la même largeur que la ligne principale : set en cours (ou « TIE-BREAK ») et compétition ;
     *  - ligne principale : nom | sets gagnés | points, de chaque côté d'un filet blanc central,
     *    avec un petit ballon devant le nom de l'équipe au service ;
     *  - onglet du bas : le score de chaque set terminé, il s'élargit à chaque nouveau set.
     */
    fun renderScoreboard(s: ScoreState, now: Long): Bitmap {
        val bitmap = empty()
        if (!s.overlayVisible) return bitmap
        val c = designCanvas(bitmap)
        val hx = 960f - (NAME_W + SETS_W + PTS_W)      // bord gauche du tableau
        val ax = 960f                                  // début de la moitié visiteurs
        val nx = ax + PTS_W + SETS_W                   // début du nom des visiteurs
        val ex = nx + NAME_W                           // bord droit du tableau
        val y = ROW_Y
        val b = ROW_Y + ROW_H

        // Ligne principale (angles extérieurs coupés)
        polygon(c, HOME_NAME, hx, y, hx + NAME_W, y, hx + NAME_W, b, hx + CUT, b, hx, b - CUT)
        rect(c, hx + NAME_W, y, SETS_W, ROW_H, HOME_SETS)
        rect(c, hx + NAME_W + SETS_W, y, PTS_W, ROW_H, HOME_PTS)
        rect(c, ax, y, PTS_W, ROW_H, AWAY_PTS)
        rect(c, ax + PTS_W, y, SETS_W, ROW_H, AWAY_SETS)
        polygon(c, AWAY_NAME, nx, y, ex, y, ex, b - CUT, ex - CUT, b, nx, b)
        rect(c, 958f, y, 4f, ROW_H, Color.WHITE)       // filet central

        // Noms, avec le ballon devant l'équipe au service
        teamName(c, s.homeName, hx, hx + NAME_W, s.serving == Team.HOME, ballLeft = false)
        teamName(c, s.awayName, nx, ex, s.serving == Team.AWAY, ballLeft = true)

        // Sets gagnés (petit libellé « SETS » au-dessus)
        for ((x, v) in listOf(hx + NAME_W + SETS_W / 2 to s.homeSets, ax + PTS_W + SETS_W / 2 to s.awaySets)) {
            drawText(c, "SETS", x, y + 22, 13f, josefin, color = Color.argb(170, 255, 255, 255))
            drawText(c, v.toString(), x, y + 72, 46f, bebas)
        }

        // Points du set en cours
        drawText(c, s.homeScore.toString(), hx + NAME_W + SETS_W + PTS_W / 2, y + 72, 84f, bebas, maxWidth = PTS_W - 16)
        drawText(c, s.awayScore.toString(), ax + PTS_W / 2, y + 72, 84f, bebas, maxWidth = PTS_W - 16)

        // Barre du haut : set en cours · compétition, sur toute la longueur du tableau
        val label = if (s.setNumber >= 5) "TIE-BREAK" else "SET ${s.setNumber}"
        val comp = s.competition.trim().uppercase()
        val rest = if (comp.isEmpty()) "" else "   ·   $comp"
        val maxText = ex - hx - 60
        text.typeface = josefin; text.textSize = 19f
        val w1 = text.measureText(label)
        text.typeface = josefinLight
        var restSize = 19f
        var w2 = text.measureText(rest)
        if (w1 + w2 > maxText) {
            restSize = 19f * (maxText - w1) / w2
            text.textSize = restSize
            w2 = text.measureText(rest)
        }
        rect(c, hx, 0f, ex - hx, y, TAB)
        val tx = 960 - (w1 + w2) / 2
        drawText(c, label, tx, 24f, 19f, josefin, align = Paint.Align.LEFT)
        if (rest.isNotEmpty()) drawText(c, rest, tx + w1, 24f - (19f - restSize) * 0.35f, restSize, josefinLight, color = TAB_TEXT, align = Paint.Align.LEFT)

        // Onglet du bas : sets terminés (gagnant en blanc, perdant en gris)
        if (s.setScores.isNotEmpty()) {
            val n = s.setScores.size
            val w = n * CELL_W + 36
            polygon(c, TAB, 960 - w / 2, b, 960 + w / 2, b, 960 + w / 2 - 16, b + 36, 960 - w / 2 + 16, b + 36)
            text.typeface = bebas; text.textSize = 31f
            val dash = text.measureText("-")
            var x = 960 - n * CELL_W / 2
            s.setScores.forEachIndexed { i, (h, a) ->
                val cx = x + CELL_W / 2
                if (i > 0) rect(c, x - 1, b + 8, 2f, 20f, CELL_SEP)
                drawText(c, h.toString(), cx - dash / 2 - 3, b + 28, 31f, bebas, color = if (h > a) Color.WHITE else LOSER, align = Paint.Align.RIGHT)
                drawText(c, "-", cx, b + 28, 31f, bebas, color = LOSER)
                drawText(c, a.toString(), cx + dash / 2 + 3, b + 28, 31f, bebas, color = if (a > h) Color.WHITE else LOSER, align = Paint.Align.LEFT)
                x += CELL_W
            }
        }

        // Partenaires : un logo à la fois, en bas à droite
        val index = partnerIndex(s, now)
        if (index >= 0) partnerTile(c, 1920f - 48 - 260f, 1080f - 48 - 150f, 260f, 150f, partners[index])
        return bitmap
    }

    /**
     * Nom centré dans sa case [x0, x1], réduit s'il est trop long (toujours centré, en largeur comme
     * en hauteur). La place du ballon de service est réservée des deux côtés : le nom garde la même
     * taille que l'équipe serve ou non.
     */
    private fun teamName(c: Canvas, name: String, x0: Float, x1: Float, serving: Boolean, ballLeft: Boolean) {
        val side = NAME_PAD + BALL_SIZE + 12
        if (serving) {
            val bx = if (ballLeft) x0 + NAME_PAD else x1 - NAME_PAD - BALL_SIZE
            val by = ROW_Y + (ROW_H - BALL_SIZE) / 2
            c.drawBitmap(serveBall, null, RectF(bx, by, bx + BALL_SIZE, by + BALL_SIZE), bitmapPaint)
        }
        drawText(c, name.uppercase(), (x0 + x1) / 2, ROW_Y + 63, 58f, bebas, maxWidth = x1 - x0 - 2 * side)
    }

    private fun rect(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        fill.color = color
        c.drawRect(x, y, x + w, y + h, fill)
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
        align: Paint.Align = Paint.Align.CENTER,
    ) {
        text.typeface = typeface
        text.textSize = size
        text.color = color
        text.textAlign = align
        // Réduit la taille si le texte est trop long pour sa case
        val measured = text.measureText(value)
        var y = baseline
        if (measured > maxWidth) {
            text.textSize = size * maxWidth / measured
            // Plus petit : on le remonte d'autant pour qu'il reste centré en hauteur dans sa case
            y -= (size - text.textSize) * 0.35f
        }
        canvas.drawText(value, x, y, text)
    }

    companion object {
        // Tableau de score du match (repère 1920x1080)
        private const val ROW_Y = 34f
        private const val ROW_H = 84f
        private const val NAME_W = 400f
        private const val NAME_PAD = 16f
        private const val SETS_W = 66f
        private const val PTS_W = 136f
        private const val CUT = 26f
        private const val CELL_W = 96f
        private const val BALL_SIZE = 38f
        private val HOME_NAME = Color.rgb(208, 10, 10)
        private val HOME_PTS = Color.rgb(163, 0, 0)
        private val HOME_SETS = Color.rgb(94, 0, 0)
        private val AWAY_NAME = Color.rgb(107, 107, 114)
        private val AWAY_PTS = Color.rgb(85, 85, 92)
        private val AWAY_SETS = Color.rgb(43, 43, 48)
        private val TAB = Color.argb(235, 20, 20, 24)
        private val TAB_TEXT = Color.rgb(205, 205, 210)
        private val LOSER = Color.rgb(154, 154, 160)
        private val CELL_SEP = Color.rgb(70, 70, 76)

        /** Couleurs des plaques sur l'écran de fin (celles du visuel). */
        private val HOME_COLOR = Color.rgb(229, 21, 17)
        private val AWAY_COLOR = Color.rgb(20, 80, 255)
        private val PARTNER_LABEL = Color.rgb(213, 213, 216)
        const val PARTNER_ROTATION_MS = 8_000L
    }
}
