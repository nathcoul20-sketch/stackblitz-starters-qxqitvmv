package com.scorecast.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * Dessine ce qui est incrusté par-dessus la vidéo, dans un bitmap transparent de la taille du flux :
 * - en match : le tableau de score aux couleurs du club (en haut à gauche) et les bandeaux
 *   (« BUT ! », « TEMPS MORT », « EXCLUSION ») ;
 * - sur les écrans d'intro, de pause et de fin : un écran complet généré (dégradé aux couleurs du club,
 *   logo, équipes, score) si le club n'a pas importé de vidéo, sinon un simple cartouche de résultat
 *   posé sur sa vidéo.
 *
 * Toutes les coordonnées sont exprimées dans le repère 1920x1080 du design, puis mises à l'échelle :
 * le rendu est identique en 720p et en 1080p.
 */
class OverlayRenderer(
    context: Context,
    private val width: Int,
    private val height: Int,
    private val logo: Bitmap?,
) {

    private val bebas = Typeface.createFromAsset(context.assets, "fonts/BebasNeue-Regular.ttf")
    private val josefin = Typeface.Builder(context.assets, "fonts/JosefinSans.ttf")
        .setFontVariationSettings("'wght' 600")
        .build() ?: Typeface.DEFAULT_BOLD

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Bitmap entièrement transparent (overlay masqué). */
    fun empty(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    /**
     * Ce qui change à l'écran en match : sert à ne redessiner le tableau de score
     * que lorsque l'affichage change (chrono, bandeaux, exclusions).
     */
    fun scoreboardSignature(s: ScoreState, now: Long): String = buildString {
        append(s.clockText(now)).append('|').append(s.addedText(now))
        append('|').append(goalBanner(s, now) != null)
        append('|').append(timeoutBanner(s, now) != null)
        append('|').append(suspensionBanner(s, now) != null)
        if (s.sport == Sport.HAND) {
            append('|').append(s.activeSuspensions(Team.HOME, now).map { (it + 999) / 1000 })
            append('|').append(s.activeSuspensions(Team.AWAY, now).map { (it + 999) / 1000 })
        }
    }

    // =====================================================================================
    // Tableau de score
    // =====================================================================================

    fun renderScoreboard(s: ScoreState, now: Long): Bitmap {
        val bitmap = empty()
        if (!s.overlayVisible) return bitmap
        val c = designCanvas(bitmap)
        val homeOn = Colors.onColor(s.homeColor)
        val awayOn = Colors.onColor(s.awayColor)
        val accent = s.homeAccent
        val accentOn = Colors.onColor(accent)
        val highlight = highlight(s)

        var x = X0
        val top = Y0
        val bottom = top + ROW

        // Logo du club, sur fond blanc
        if (logo != null) {
            rect(c, x, top, LOGO_W, ROW, Color.WHITE)
            drawLogo(c, x + 5, top + 5, LOGO_W - 10, ROW - 10)
            x += LOGO_W
        }
        val start = X0
        val homeX = x
        rect(c, homeX, top, NAME_W, ROW, s.homeColor)
        x += NAME_W

        // Partie centrale : score (ou sets + points au volley)
        val middleX = x
        val middleW: Float
        if (s.sport == Sport.VOLLEY) {
            middleW = SETS_W * 2 + PTS_W
            rect(c, x, top, SETS_W, ROW, DARK)
            drawText(c, s.homeSets.toString(), x + SETS_W / 2, top + 50, 50f, bebas, highlight)
            rect(c, x + SETS_W, top, PTS_W, ROW, accent)
            drawText(c, "${s.homeScore} - ${s.awayScore}", x + SETS_W + PTS_W / 2, top + 52, 60f, bebas, accentOn,
                maxWidth = PTS_W - 16)
            rect(c, x + SETS_W + PTS_W, top, SETS_W, ROW, DARK)
            drawText(c, s.awaySets.toString(), x + SETS_W * 1.5f + PTS_W, top + 50, 50f, bebas, highlight)
        } else {
            middleW = SCORE_W
            rect(c, x, top, SCORE_W, ROW, accent)
            drawText(c, "${s.homeScore} - ${s.awayScore}", x + SCORE_W / 2, top + 52, 60f, bebas, accentOn,
                maxWidth = SCORE_W - 16)
        }
        x += middleW
        val awayX = x
        rect(c, awayX, top, NAME_W, ROW, s.awayColor)
        x += NAME_W
        val end = x

        // Liseré sous les noms (couleur secondaire du club)
        rect(c, homeX, bottom - 4, NAME_W, 4f, accent)
        rect(c, awayX, bottom - 4, NAME_W, 4f, accent)

        // Foot : cartons rouges, côté score
        var homeMark = 0f
        var awayMark = 0f
        if (s.sport == Sport.FOOT) {
            for (i in 0 until s.homeRed) rect(c, middleX - 14 - i * 16 - 12, top + 14, 12f, 20f, RED)
            for (i in 0 until s.awayRed) rect(c, awayX + 14 + i * 16, top + 14, 12f, 20f, RED)
            if (s.homeRed > 0) homeMark = 18f + 16 * s.homeRed
            if (s.awayRed > 0) awayMark = 18f + 16 * s.awayRed
        }
        // Volley : service (petit triangle côté score)
        if (s.sport == Sport.VOLLEY && s.serving != null) {
            val home = s.serving == Team.HOME
            val cx = if (home) middleX - 20 else awayX + 20
            val dir = if (home) 1 else -1
            polygon(c, if (home) homeOn else awayOn, cx - 7 * dir, top + 22, cx + 7 * dir, top + 32, cx - 7 * dir, top + 42)
            if (home) homeMark = 34f else awayMark = 34f
        }

        drawText(c, s.homeName.uppercase(), homeX + NAME_W / 2 - homeMark / 2, top + 48, 46f, bebas, homeOn,
            maxWidth = NAME_W - 28 - homeMark)
        drawText(c, s.awayName.uppercase(), awayX + NAME_W / 2 + awayMark / 2, top + 48, 46f, bebas, awayOn,
            maxWidth = NAME_W - 28 - awayMark)

        // Chrono, à droite
        if (s.sport.hasClock) {
            rect(c, end, top, CLOCK_W, ROW, DARK)
            val running = s.clockRunning
            drawText(c, s.clockText(now), end + CLOCK_W / 2, top + 48, 46f, bebas,
                if (!running && s.clockStarted && !s.finished) GREY else Color.WHITE)
            s.addedText(now)?.let { added ->
                rect(c, end + CLOCK_W, top, ADDED_W, ROW, accent)
                drawText(c, added, end + CLOCK_W + ADDED_W / 2, top + 48, 42f, bebas, accentOn, maxWidth = ADDED_W - 12)
            }
        }

        var y = bottom
        val w = end - start

        // Basket / hand : ligne d'informations sous chaque équipe (fautes, temps morts, exclusions)
        if (s.sport == Sport.BASKET || s.sport == Sport.HAND) {
            rect(c, start, y, w, INFO, INFO_BG)
            teamInfo(c, s, Team.HOME, homeX, y, now, highlight)
            teamInfo(c, s, Team.AWAY, awayX, y, now, highlight)
            y += INFO
        }

        // Bandeau : période · compétition
        rect(c, start, y, w, SUB, SUB_BG)
        val label = s.periodShort.uppercase()
        val labelWidth = drawText(c, label, start + 16, y + 24, 19f, josefin, highlight, align = Paint.Align.LEFT)
        if (s.competition.isNotBlank()) {
            drawText(c, "·  " + s.competition, start + 16 + labelWidth + 12, y + 24, 19f, josefin, Color.WHITE,
                maxWidth = w - labelWidth - 44, align = Paint.Align.LEFT)
        }
        y += SUB

        if (s.sport == Sport.FOOT && s.penalties) {
            rect(c, start, y, w, 40f, accent)
            drawText(c, "TIRS AU BUT", start + 16, y + 30, 30f, bebas, accentOn, align = Paint.Align.LEFT)
            drawText(c, "${s.homePens} - ${s.awayPens}", start + w - 16, y + 31, 34f, bebas, accentOn,
                align = Paint.Align.RIGHT)
            y += 40
        }

        // Bandeaux d'événement (un seul à la fois, le plus récent)
        val goal = goalBanner(s, now)
        val timeout = timeoutBanner(s, now)
        val susp = suspensionBanner(s, now)
        val latest = listOfNotNull(
            goal?.let { it.at to 0 }, timeout?.let { s.timeoutAt to 1 }, susp?.let { it.at to 2 },
        ).maxByOrNull { it.first }?.second
        when (latest) {
            0 -> {
                val g = goal!!
                val detail = listOf(g.scorer.uppercase(), g.minute).filter { it.isNotBlank() }.joinToString("   ")
                eventBanner(c, s, start, y + 12, w, "BUT !", s.name(g.team).uppercase(), detail, g.team)
            }
            1 -> eventBanner(c, s, start, y + 12, w, "TEMPS MORT", s.name(timeout!!).uppercase(), "", timeout)
            2 -> eventBanner(c, s, start, y + 12, w, "EXCLUSION 2'", s.name(susp!!.team).uppercase(), "", susp.team)
        }
        return bitmap
    }

    /** Fautes / temps morts / exclusions d'une équipe, dans la ligne sous son nom. */
    private fun teamInfo(c: Canvas, s: ScoreState, team: Team, x: Float, y: Float, now: Long, highlight: Int) {
        var cx = x + 14
        val base = y + 21
        // Temps morts restants : un point plein par temps mort disponible
        val allowed = s.timeoutsAllowed
        val left = allowed - s.timeouts(team)
        for (i in 0 until allowed) {
            fill.color = if (i < left) highlight else DIM
            c.drawCircle(cx + 5, y + INFO / 2, 5f, fill)
            cx += 15
        }
        cx += 8
        if (s.sport == Sport.BASKET) {
            val fouls = s.fouls(team)
            val wFouls = drawText(c, "FAUTES $fouls", cx, base, 17f, josefin, Color.WHITE, align = Paint.Align.LEFT)
            cx += wFouls + 12
            if (s.bonus(team)) {
                val bw = 72f
                roundRect(c, cx, y + 5, bw, INFO - 10, 4f, highlight)
                drawText(c, "BONUS", cx + bw / 2, base, 16f, josefin, Colors.onColor(highlight))
            }
        } else {
            // Hand : exclusions en cours, avec le temps restant
            for (rem in s.activeSuspensions(team, now).take(3)) {
                val bw = 76f
                roundRect(c, cx, y + 5, bw, INFO - 10, 4f, SUSP)
                val secs = (rem + 999) / 1000
                drawText(c, String.format(java.util.Locale.ROOT, "%d:%02d", secs / 60, secs % 60),
                    cx + bw / 2, base, 17f, josefin, Color.WHITE)
                cx += bw + 6
            }
        }
    }

    private fun eventBanner(
        c: Canvas, s: ScoreState, x: Float, y: Float, w: Float,
        title: String, team: String, detail: String, side: Team,
    ) {
        val h = 72f
        val accent = s.homeAccent
        val teamColor = if (side == Team.HOME) s.homeColor else s.awayColor
        text.typeface = bebas
        text.textSize = 56f
        val titleW = maxOf(150f, text.measureText(title) + 50f)
        polygon(c, accent, x, y, x + titleW, y, x + titleW - 20, y + h, x, y + h)
        polygon(c, teamColor, x + titleW + 6, y, x + w, y, x + w, y + h, x + titleW - 14, y + h)
        drawText(c, title, x + (titleW - 10) / 2, y + 54, 56f, bebas, Colors.onColor(accent), maxWidth = titleW - 30)
        val on = Colors.onColor(teamColor)
        val tx = x + titleW + 10
        val maxW = w - titleW - 30
        if (detail.isEmpty()) {
            drawText(c, team, tx, y + 52, 44f, bebas, on, maxWidth = maxW, align = Paint.Align.LEFT)
        } else {
            drawText(c, team, tx, y + 36, 34f, bebas, on, maxWidth = maxW, align = Paint.Align.LEFT)
            drawText(c, detail, tx, y + 62, 22f, josefin, on, maxWidth = maxW, align = Paint.Align.LEFT)
        }
    }

    // =====================================================================================
    // Écrans d'intro, de pause et de fin
    // =====================================================================================

    /**
     * Écran complet généré (aucune vidéo importée pour cette scène) : opaque, il masque la caméra.
     */
    fun renderGeneratedScreen(s: ScoreState, scene: Scene): Bitmap {
        val bitmap = empty()
        val c = designCanvas(bitmap)
        val accent = s.homeAccent
        val highlight = highlight(s)

        // Fond : dégradé de la couleur du club vers le noir, et deux bandes obliques
        fill.shader = LinearGradient(0f, 0f, 1920f, 1080f,
            Colors.shade(s.homeColor, 0.55f), Color.rgb(8, 8, 12), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1920f, 1080f, fill)
        fill.shader = null
        fill.alpha = 255
        polygonAlpha(c, accent, 40, 1240f, 0f, 1420f, 0f, 900f, 1080f, 720f, 1080f)
        polygonAlpha(c, s.homeColor, 70, 1460f, 0f, 1520f, 0f, 1000f, 1080f, 940f, 1080f)

        // Logo
        var y = 110f
        if (logo != null) {
            val box = 300f
            drawLogo(c, 960 - box / 2, y, box, box)
            y += box + 30
        } else {
            y += 120
        }

        val title = when (scene) {
            Scene.INTRO -> "LE MATCH VA BIENTÔT COMMENCER"
            Scene.HALFTIME -> s.sport.breakLabel.uppercase()
            Scene.OUTRO -> "FIN DU MATCH"
            Scene.MATCH -> ""
        }
        drawText(c, title, 960f, y + 70, 92f, bebas, highlight, maxWidth = 1500f)
        y += 110

        // Plaques des équipes, score au milieu
        val plateY = y + 20
        val plateH = 120f
        val plateW = 600f
        val gap = if (scene == Scene.INTRO) 200f else 380f
        val lx = 960 - gap / 2 - plateW
        val rx = 960 + gap / 2
        plate(c, lx, plateY, plateW, plateH, s.homeColor, accent, left = true)
        plate(c, rx, plateY, plateW, plateH, s.awayColor, accent, left = false)
        drawText(c, s.homeName.uppercase(), lx + plateW / 2, plateY + 88, 84f, bebas, Colors.onColor(s.homeColor),
            maxWidth = plateW - 60)
        drawText(c, s.awayName.uppercase(), rx + plateW / 2, plateY + 88, 84f, bebas, Colors.onColor(s.awayColor),
            maxWidth = plateW - 60)
        if (scene == Scene.INTRO) {
            drawText(c, "VS", 960f, plateY + 92, 96f, bebas, Color.WHITE)
        } else {
            val main = if (s.sport == Sport.VOLLEY) "${s.homeSets} - ${s.awaySets}" else "${s.homeScore} - ${s.awayScore}"
            drawText(c, main, 960f, plateY + 100, 150f, bebas, Color.WHITE, maxWidth = gap - 30)
        }
        y = plateY + plateH + 70

        // Détails sous le score
        val details = resultDetails(s)
        if (scene != Scene.INTRO && details.isNotEmpty()) {
            drawText(c, details, 960f, y, 40f, josefin, highlight, maxWidth = 1500f)
            y += 60
        }
        if (scene != Scene.INTRO && s.sport == Sport.FOOT) {
            for ((xCenter, lines) in listOf(lx + plateW / 2 to s.scorers(Team.HOME), rx + plateW / 2 to s.scorers(Team.AWAY))) {
                var ly = y
                for (line in lines.take(5)) {
                    drawText(c, line, xCenter, ly, 32f, josefin, Color.WHITE, maxWidth = plateW)
                    ly += 44
                }
            }
        }

        // Compétition, en bas
        if (s.competition.isNotBlank()) {
            drawText(c, s.competition.uppercase(), 960f, 1010f, 36f, josefin, Color.WHITE, maxWidth = 1600f)
        }
        return bitmap
    }

    /**
     * Cartouche de résultat posé en bas d'une vidéo importée (écrans de pause et de fin).
     * Rien sur l'intro : la vidéo du club se suffit à elle-même.
     */
    fun renderResultCard(s: ScoreState, scene: Scene): Bitmap {
        val bitmap = empty()
        if (scene == Scene.INTRO || scene == Scene.MATCH) return bitmap
        val c = designCanvas(bitmap)
        val accent = s.homeAccent
        val plateW = 520f
        val h = 100f
        val scoreW = 260f
        val y = 1080f - 80 - h
        val total = plateW * 2 + scoreW
        val x = 960 - total / 2
        // Titre au-dessus
        val title = if (scene == Scene.OUTRO) "FIN DU MATCH" else s.sport.breakLabel.uppercase()
        text.typeface = bebas
        text.textSize = 40f
        val tw = text.measureText(title) + 50
        rect(c, 960 - tw / 2, y - 52, tw, 52f, accent)
        drawText(c, title, 960f, y - 13, 40f, bebas, Colors.onColor(accent))

        rect(c, x, y, plateW, h, s.homeColor)
        rect(c, x + plateW, y, scoreW, h, DARK)
        rect(c, x + plateW + scoreW, y, plateW, h, s.awayColor)
        drawText(c, s.homeName.uppercase(), x + plateW / 2, y + 72, 68f, bebas, Colors.onColor(s.homeColor), maxWidth = plateW - 40)
        drawText(c, s.awayName.uppercase(), x + plateW * 1.5f + scoreW, y + 72, 68f, bebas, Colors.onColor(s.awayColor), maxWidth = plateW - 40)
        val main = if (s.sport == Sport.VOLLEY) "${s.homeSets} - ${s.awaySets}" else "${s.homeScore} - ${s.awayScore}"
        drawText(c, main, x + plateW + scoreW / 2, y + 80, 92f, bebas, Color.WHITE, maxWidth = scoreW - 30)
        val details = resultDetails(s)
        if (details.isNotEmpty()) {
            rect(c, x, y + h, total, 44f, SUB_BG)
            drawText(c, details, 960f, y + h + 31, 26f, josefin, Color.WHITE, maxWidth = total - 40)
        }
        return bitmap
    }

    /** Ligne de détail : sets (volley), tirs au but (foot). */
    private fun resultDetails(s: ScoreState): String = when {
        s.sport == Sport.VOLLEY -> {
            val sets = s.setScores + if (s.homeScore + s.awayScore > 0) listOf(s.homeScore to s.awayScore) else emptyList()
            sets.joinToString("   ") { "${it.first}-${it.second}" }
        }
        s.sport == Sport.FOOT && (s.penalties || s.homePens + s.awayPens > 0) -> "T.A.B. ${s.homePens} - ${s.awayPens}"
        else -> ""
    }

    // =====================================================================================
    // Bandeaux temporaires
    // =====================================================================================

    private fun goalBanner(s: ScoreState, now: Long): Goal? =
        if (s.sport != Sport.FOOT) null
        else s.goals.lastOrNull()?.takeIf { !s.penalties && now - it.at in 0 until GOAL_BANNER_MS }

    private fun timeoutBanner(s: ScoreState, now: Long): Team? =
        s.timeoutTeam?.takeIf { now - s.timeoutAt in 0 until TIMEOUT_BANNER_MS }

    private fun suspensionBanner(s: ScoreState, now: Long): Suspension? =
        s.suspensions.lastOrNull()?.takeIf { now - it.at in 0 until SUSP_BANNER_MS }

    // =====================================================================================
    // Dessin
    // =====================================================================================

    /** Couleur vive pour les textes mis en valeur sur fond noir : couleur secondaire, sinon principale, sinon blanc. */
    private fun highlight(s: ScoreState): Int = listOf(s.homeAccent, s.homeColor)
        .firstOrNull { Colors.luminance(it) > 0.18 } ?: Color.WHITE

    private fun plate(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int, accent: Int, left: Boolean) {
        val k = 30f
        if (left) {
            polygon(c, color, x + k, y, x + w, y, x + w - k, y + h, x, y + h)
            polygon(c, accent, x, y + h - 8, x + w - k, y + h - 8, x + w - k - 2, y + h, x, y + h)
        } else {
            polygon(c, color, x + k, y, x + w, y, x + w - k, y + h, x, y + h)
            polygon(c, accent, x + 2, y + h - 8, x + w - k, y + h - 8, x + w - k - 2, y + h, x, y + h)
        }
    }

    /** Logo du club, contenu dans la boîte en gardant ses proportions. */
    private fun drawLogo(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val bmp = logo ?: return
        val scale = minOf(w / bmp.width, h / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val dst = RectF(x + (w - dw) / 2, y + (h - dh) / 2, x + (w + dw) / 2, y + (h + dh) / 2)
        c.drawBitmap(bmp, null, dst, bitmapPaint)
    }

    private fun designCanvas(bitmap: Bitmap) = Canvas(bitmap).apply {
        scale(width / 1920f, height / 1080f)
    }

    private fun rect(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        fill.color = color
        c.drawRect(x, y, x + w, y + h, fill)
    }

    private fun roundRect(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        fill.color = color
        c.drawRoundRect(x, y, x + w, y + h, r, r, fill)
    }

    private fun polygon(c: Canvas, color: Int, vararg pts: Float) {
        fill.color = color
        c.drawPath(path(pts), fill)
    }

    private fun polygonAlpha(c: Canvas, color: Int, alpha: Int, vararg pts: Float) {
        fill.color = color
        fill.alpha = alpha
        c.drawPath(path(pts), fill)
        fill.alpha = 255
    }

    private fun path(pts: FloatArray) = Path().apply {
        moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) {
            lineTo(pts[i], pts[i + 1]); i += 2
        }
        close()
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
        private val DARK = Color.argb(240, 14, 14, 18)
        private val SUB_BG = Color.argb(200, 0, 0, 0)
        private val INFO_BG = Color.argb(215, 22, 22, 28)
        private val DIM = Color.argb(255, 70, 70, 78)
        private val GREY = Color.rgb(170, 170, 178)
        private val RED = Color.rgb(227, 6, 19)
        private val SUSP = Color.rgb(200, 30, 40)

        // Tableau de score (repère 1920x1080)
        private const val X0 = 48f
        private const val Y0 = 40f
        private const val ROW = 64f
        private const val LOGO_W = 64f
        private const val NAME_W = 260f
        private const val SCORE_W = 160f
        private const val SETS_W = 56f
        private const val PTS_W = 170f
        private const val CLOCK_W = 150f
        private const val ADDED_W = 110f
        private const val INFO = 30f
        private const val SUB = 34f

        const val GOAL_BANNER_MS = 10_000L
        const val TIMEOUT_BANNER_MS = 8_000L
        const val SUSP_BANNER_MS = 6_000L
    }
}
