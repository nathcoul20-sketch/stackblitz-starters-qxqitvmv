package com.scorecast.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
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
    /** Logos des partenaires du club (mi-temps, et rotation pendant le match si activée). */
    private val partners: List<Bitmap> = emptyList(),
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
        if (s.partnersVisible && partners.isNotEmpty()) append("|p").append(partnerIndex(now))
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

        val start = X0
        var y: Float
        val w: Float
        if (s.sport == Sport.VOLLEY) {
            // Volley : une ligne par équipe, avec les scores de chaque set joué
            w = drawVolleyRows(c, s, accent, accentOn, highlight)
            y = Y0 + V_ROW * 2
        } else {
            var x = X0
            val top = Y0
            val bottom = top + ROW

            // (pas de logo dans le tableau de score : trop petit pour être net ; il est sur les écrans
            // d'intro, de pause et de fin)
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

            y = bottom
            w = end - start

            // Basket / hand : ligne d'informations sous chaque équipe (fautes, temps morts, exclusions)
            if (s.sport == Sport.BASKET || s.sport == Sport.HAND) {
                rect(c, start, y, w, INFO, INFO_BG)
                teamInfo(c, s, Team.HOME, homeX, y, now, highlight)
                teamInfo(c, s, Team.AWAY, awayX, y, now, highlight)
                y += INFO
            }
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
        // Partenaires : un logo à la fois, en bas à droite, qui change toutes les 8 secondes
        if (s.partnersVisible && partners.isNotEmpty()) {
            val w2 = 260f
            val h2 = 150f
            partnerTile(c, 1920f - 48 - w2, 1080f - 48 - h2, w2, h2, partners[partnerIndex(now)])
        }
        return bitmap
    }

    private fun partnerIndex(now: Long): Int = ((now / PARTNER_ROTATION_MS) % partners.size).toInt()

    /** Logo d'un partenaire sur une carte blanche arrondie. */
    private fun partnerTile(c: Canvas, x: Float, y: Float, w: Float, h: Float, bmp: Bitmap) {
        roundRect(c, x, y, w, h, 14f, Color.WHITE)
        val pad = 14f
        val scale = minOf((w - 2 * pad) / bmp.width, (h - 2 * pad) / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        c.drawBitmap(bmp, null, RectF(x + (w - dw) / 2, y + (h - dh) / 2, x + (w + dw) / 2, y + (h + dh) / 2), bitmapPaint)
    }

    /** Bandeau « Merci à nos partenaires » : jusqu'à 8 logos centrés sur une ligne. */
    private fun partnerStrip(c: Canvas, labelBaseline: Float, color: Int) {
        if (partners.isEmpty()) return
        val shown = partners.take(MAX_PARTNERS)
        drawText(c, "MERCI À NOS PARTENAIRES", 960f, labelBaseline, 26f, josefin, color)
        val tileW = 190f
        val tileH = 110f
        val gap = 20f
        val total = shown.size * tileW + (shown.size - 1) * gap
        var x = 960 - total / 2
        for (bmp in shown) {
            partnerTile(c, x, labelBaseline + 20, tileW, tileH, bmp)
            x += tileW + gap
        }
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

    /**
     * Tableau de score du volley sur deux lignes (une par équipe) :
     * nom (+ ballon au service) | sets gagnés | score de chaque set terminé | points du set en cours.
     * Renvoie la largeur totale.
     */
    private fun drawVolleyRows(c: Canvas, s: ScoreState, accent: Int, accentOn: Int, highlight: Int): Float {
        val sets = s.setScores
        val w = V_NAME_W + V_SETS_W + sets.size * V_CELL + V_PTS_W
        for ((i, team) in listOf(Team.HOME, Team.AWAY).withIndex()) {
            val y = Y0 + i * V_ROW
            val color = if (team == Team.HOME) s.homeColor else s.awayColor
            val on = Colors.onColor(color)
            var x = X0
            // Nom de l'équipe, ballon si elle est au service
            rect(c, x, y, V_NAME_W, V_ROW, color)
            val serving = s.serving == team
            drawText(c, s.name(team).uppercase(), x + 18, y + 44, 42f, bebas, on,
                maxWidth = V_NAME_W - 36 - (if (serving) 36 else 0), align = Paint.Align.LEFT)
            if (serving) volleyBall(c, x + V_NAME_W - 26, y + V_ROW / 2, 13f, on, color)
            x += V_NAME_W
            // Sets gagnés
            rect(c, x, y, V_SETS_W, V_ROW, DARK)
            drawText(c, (if (team == Team.HOME) s.homeSets else s.awaySets).toString(), x + V_SETS_W / 2, y + 44, 44f,
                bebas, highlight)
            x += V_SETS_W
            // Scores des sets terminés : le vainqueur du set en blanc, l'autre en gris
            for ((h, a) in sets) {
                val mine = if (team == Team.HOME) h else a
                val theirs = if (team == Team.HOME) a else h
                rect(c, x, y, V_CELL, V_ROW, SET_BG)
                drawText(c, mine.toString(), x + V_CELL / 2, y + 44, 44f, bebas, if (mine > theirs) Color.WHITE else GREY)
                x += V_CELL
            }
            // Points du set en cours, mis en valeur
            rect(c, x, y, V_PTS_W, V_ROW, accent)
            drawText(c, (if (team == Team.HOME) s.homeScore else s.awayScore).toString(), x + V_PTS_W / 2, y + 46, 50f,
                bebas, accentOn)
        }
        // Fine séparation entre les deux lignes
        rect(c, X0, Y0 + V_ROW - 1, w, 2f, Color.argb(160, 0, 0, 0))
        return w
    }

    /** Petit ballon de volley (indicateur de service). */
    private fun volleyBall(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, lines: Int) {
        fill.color = color
        c.drawCircle(cx, cy, r, fill)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.16f; this.color = lines }
        c.drawArc(RectF(cx - r * 1.6f, cy - r * 2.2f, cx + r * 0.9f, cy + r * 0.2f), 20f, 110f, false, p)
        c.drawArc(RectF(cx - r * 0.2f, cy - r * 0.9f, cx + r * 2.2f, cy + r * 1.6f), 140f, 110f, false, p)
        c.drawArc(RectF(cx - r * 1.9f, cy - r * 0.3f, cx + r * 0.5f, cy + r * 2.1f), 260f, 110f, false, p)
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

        // Fond sombre, halo de la couleur du club derrière le logo, et angles décoratifs aux couleurs
        // du club (en haut à gauche, en bas à droite) qui ne passent jamais sous les textes
        // Fond entièrement opaque : la caméra ne doit jamais transparaître (la peinture partagée peut
        // garder l'opacité d'un dessin précédent, d'où le fill.color opaque avant chaque dégradé)
        c.drawColor(Color.rgb(6, 6, 9))
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, 0f, 0f, 1080f, Color.rgb(24, 25, 32), Color.rgb(6, 6, 9), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1920f, 1080f, fill)
        fill.color = Color.BLACK
        fill.shader = RadialGradient(960f, 330f, 820f,
            intArrayOf(withAlpha(s.homeColor, 110), withAlpha(s.homeColor, 0)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1920f, 1080f, fill)
        fill.shader = null
        polygon(c, s.homeColor, 0f, 0f, 330f, 0f, 0f, 620f)
        polygon(c, accent, 360f, 0f, 392f, 0f, 0f, 737f, 0f, 677f)
        polygon(c, s.homeColor, 1920f, 1080f, 1590f, 1080f, 1920f, 460f)
        polygon(c, accent, 1560f, 1080f, 1528f, 1080f, 1920f, 343f, 1920f, 403f)

        // Intro : seulement le logo du club et « Le match va bientôt commencer », centrés
        if (scene == Scene.INTRO) {
            val box = 340f
            val blockH = (if (logo != null) box + 60 else 0f) + 120
            var iy = (1080 - blockH) / 2
            if (logo != null) {
                drawLogo(c, 960 - box / 2, iy, box, box)
                iy += box + 60
            }
            drawText(c, "LE MATCH VA BIENTÔT COMMENCER", 960f, iy + 100, 128f, bebas, highlight, maxWidth = 1560f)
            return bitmap
        }

        // Logo en haut (taille et place fixes), puis le bloc résultat commun à toutes les configurations
        if (logo != null) drawLogo(c, 960 - LOGO_BOX / 2, 70f, LOGO_BOX, LOGO_BOX)
        drawResultBlock(c, s, scene)
        return bitmap
    }

    /**
     * Cartouche de résultat posé en bas d'une vidéo importée (écrans de pause et de fin).
     * Rien sur l'intro : la vidéo du club se suffit à elle-même.
     */
    fun renderResultCard(s: ScoreState, scene: Scene): Bitmap {
        val bitmap = empty()
        if (scene == Scene.INTRO || scene == Scene.MATCH) return bitmap
        drawResultBlock(designCanvas(bitmap), s, scene)
        return bitmap
    }

    /**
     * Bloc résultat des écrans de pause et de fin, à des positions FIXES (repère 1920x1080), identiques
     * sur l'écran généré et sur une vidéo importée : titre, équipes et score, détails, buteurs,
     * partenaires (pause), compétition. Chaque élément a son propre fond, lisible sur n'importe quelle vidéo.
     */
    private fun drawResultBlock(c: Canvas, s: ScoreState, scene: Scene) {
        val accent = s.homeAccent
        val withPartners = (scene == Scene.HALFTIME || scene == Scene.OUTRO) && partners.isNotEmpty()

        // Titre (« MI-TEMPS », « FIN DU MATCH »…) sur une étiquette à la couleur secondaire
        val title = if (scene == Scene.OUTRO) "FIN DU MATCH" else s.sport.breakLabel.uppercase()
        text.typeface = bebas
        text.textSize = 72f
        val tw = minOf(text.measureText(title) + 70, 1400f)
        rect(c, 960 - tw / 2, R_TITLE_Y, tw, 76f, accent)
        drawText(c, title, 960f, R_TITLE_Y + 62, 72f, bebas, Colors.onColor(accent), maxWidth = tw - 40)

        // Équipes et score
        val lx = 960 - R_GAP / 2 - R_PLATE_W
        val rx = 960 + R_GAP / 2
        plate(c, lx, R_PLATE_Y, R_PLATE_W, R_PLATE_H, s.homeColor, accent, left = true)
        plate(c, rx, R_PLATE_Y, R_PLATE_W, R_PLATE_H, s.awayColor, accent, left = false)
        rect(c, 960 - R_GAP / 2 + 10, R_PLATE_Y, R_GAP - 20, R_PLATE_H, DARK)
        drawText(c, s.homeName.uppercase(), lx + R_PLATE_W / 2, R_PLATE_Y + 88, 84f, bebas, Colors.onColor(s.homeColor),
            maxWidth = R_PLATE_W - 60)
        drawText(c, s.awayName.uppercase(), rx + R_PLATE_W / 2, R_PLATE_Y + 88, 84f, bebas, Colors.onColor(s.awayColor),
            maxWidth = R_PLATE_W - 60)
        val main = if (s.sport == Sport.VOLLEY) "${s.homeSets} - ${s.awaySets}" else "${s.homeScore} - ${s.awayScore}"
        drawText(c, main, 960f, R_PLATE_Y + 100, 140f, bebas, Color.WHITE, maxWidth = R_GAP - 50)

        // Détails : sets (volley), tirs au but (foot)
        val details = resultDetails(s)
        if (details.isNotEmpty()) {
            val total = R_PLATE_W * 2 + R_GAP
            rect(c, 960 - total / 2, R_DETAILS_Y, total, 48f, SUB_BG)
            drawText(c, details, 960f, R_DETAILS_Y + 35, 32f, josefin, Color.WHITE, maxWidth = total - 40)
        }

        // Buteurs (foot), sous chaque équipe
        if (s.sport == Sport.FOOT) {
            val max = if (withPartners) 3 else 5
            for ((x, lines) in listOf(lx to s.scorers(Team.HOME), rx to s.scorers(Team.AWAY))) {
                val shown = lines.take(max)
                if (shown.isEmpty()) continue
                rect(c, x, R_SCORERS_Y, R_PLATE_W, 16f + shown.size * 42f, SUB_BG)
                var ly = R_SCORERS_Y + 40
                for (line in shown) {
                    drawText(c, line, x + R_PLATE_W / 2, ly, 30f, josefin, Color.WHITE, maxWidth = R_PLATE_W - 30)
                    ly += 42
                }
            }
        }

        // Partenaires (pause et fin) : bandeau fixe en bas
        if (withPartners) {
            rect(c, 0f, R_PARTNERS_Y, 1920f, 190f, SUB_BG)
            partnerStrip(c, R_PARTNERS_Y + 40, Color.WHITE)
        }

        // Compétition, tout en bas
        if (s.competition.isNotBlank()) {
            text.typeface = josefin
            text.textSize = 30f
            val cw = minOf(text.measureText(s.competition.uppercase()) + 60, 1700f)
            rect(c, 960 - cw / 2, R_COMPET_Y, cw, 48f, SUB_BG)
            drawText(c, s.competition.uppercase(), 960f, R_COMPET_Y + 35, 30f, josefin, Color.WHITE, maxWidth = cw - 40)
        }
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

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

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

        // Volley : deux lignes, une colonne par set
        private const val V_ROW = 58f
        private const val V_NAME_W = 330f
        private const val V_SETS_W = 60f
        private const val V_CELL = 70f
        private const val V_PTS_W = 92f
        private val SET_BG = Color.argb(235, 30, 31, 38)

        // Écrans de pause et de fin : positions fixes (écran généré comme vidéo importée)
        private const val LOGO_BOX = 220f
        private const val R_TITLE_Y = 320f
        private const val R_PLATE_Y = 420f
        private const val R_PLATE_H = 120f
        private const val R_PLATE_W = 600f
        private const val R_GAP = 380f
        private const val R_DETAILS_Y = 556f
        private const val R_SCORERS_Y = 616f
        private const val R_PARTNERS_Y = 790f
        private const val R_COMPET_Y = 1000f

        const val GOAL_BANNER_MS = 10_000L
        const val PARTNER_ROTATION_MS = 8_000L
        const val MAX_PARTNERS = 8
        const val TIMEOUT_BANNER_MS = 8_000L
        const val SUSP_BANNER_MS = 6_000L
    }
}
