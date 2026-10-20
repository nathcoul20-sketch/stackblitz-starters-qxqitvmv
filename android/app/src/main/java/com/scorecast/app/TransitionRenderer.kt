package com.scorecast.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Transition générée aux couleurs du club (même effet que la transition de Foot Live) :
 * deux volets se referment en diagonale (couleur secondaire en haut, couleur principale en bas, avec
 * traits de vitesse), le logo du club surgit au centre, puis les volets se rouvrent.
 *
 * Dessinée image par image dans un bitmap transparent, posé sur un calque au-dessus du live.
 * Toutes les coordonnées sont dans le repère 1920x1080 du design (comme [OverlayRenderer]).
 * Utilisée depuis un seul thread (celui de l'animation).
 */
class TransitionRenderer(
    context: Context,
    val width: Int,
    val height: Int,
    private val logo: Bitmap?,
) {
    private val bebas = Typeface.createFromAsset(context.assets, "fonts/BebasNeue-Regular.ttf")
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = bebas }

    // Diagonale qui sépare les deux volets (passe par le centre de l'écran)
    private val vx: Float
    private val vy: Float
    private val nx: Float
    private val ny: Float
    private val travel = hypot(W, H) * 0.6f

    /** Trait de vitesse du volet principal. */
    private class Streak(val x: Float, val y: Float, val len: Float, val thick: Float, val color: Int)
    private val streaks: List<Streak>

    init {
        val norm = hypot(W, -H * 0.75f)
        vx = W / norm
        vy = -H * 0.75f / norm
        nx = -vy
        ny = vx
        val rnd = Random(9)
        streaks = List(70) {
            val white = rnd.nextFloat() < 0.3f
            val a = rnd.nextInt(25, 90)
            Streak(
                rnd.nextFloat() * TEX_W,
                (rnd.nextFloat() * 1.2f - 0.1f) * H,
                rnd.nextFloat() * 700f + 200f,
                listOf(2f, 3f, 4f, 6f, 10f, 18f)[rnd.nextInt(6)],
                if (white) Color.argb(a + 60, 255, 255, 255) else Color.argb(a, 0, 0, 0),
            )
        }
    }

    /**
     * Dessine l'image [frame] (0 à [FRAMES], peut être fractionnaire) dans [bitmap].
     * [primary] : couleur principale du club, [secondary] : couleur secondaire, [name] : nom (si pas de logo).
     */
    fun draw(bitmap: Bitmap, frame: Float, primary: Int, secondary: Int, name: String) {
        val c = Canvas(bitmap)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.scale(width / W, height / H)
        val f = frame

        val k = easeInOut(f / 9f) - easeInOut((f - 34f) / 10f) // 0 = ouvert, 1 = fermé
        val top = if (Colors.tooClose(primary, secondary)) Color.rgb(8, 8, 8) else secondary

        if (k > 0.001f) {
            // Volet du bas : couleur principale, dégradé et traits de vitesse qui défilent
            val bottom = halfPlane(1f, (1 - k) * travel - 4f)
            fill.shader = LinearGradient(0f, 0f, W, 0f, primary, Colors.shade(primary, 0.62f), Shader.TileMode.CLAMP)
            c.drawPath(bottom, fill)
            fill.shader = null
            c.save()
            c.clipPath(bottom)
            drawStreaks(c, f)
            c.restore()

            // Volet du haut : couleur secondaire (ou presque noir si trop proche de la principale)
            val upper = halfPlane(-1f, -(1 - k) * travel + 4f)
            fill.shader = LinearGradient(0f, 0f, 0f, H, Colors.shade(top, 1.08f), Colors.shade(top, 0.8f), Shader.TileMode.CLAMP)
            c.drawPath(upper, fill)
            fill.shader = null

            // Liseré sur la diagonale au moment où les volets se rejoignent
            if (k > 0.85f) {
                val a = if (f < 20f) (255 * max(0f, 1 - abs(f - 10f) / 6f)).toInt() else 140
                val seam = if (Colors.luminance(top) < 0.6 && Colors.luminance(primary) < 0.75) Color.WHITE
                    else Colors.deep(primary)
                stroke.color = seam
                stroke.alpha = a
                stroke.strokeWidth = 10f
                c.drawLine(W / 2 - vx * FAR, H / 2 - vy * FAR, W / 2 + vx * FAR, H / 2 + vy * FAR, stroke)
            }
        }

        // Logo : surgit en tournant (effet ressort), onde de choc, puis s'efface quand les volets s'ouvrent
        if (f >= 9f && f < 35f) {
            val p = (f - 9f) / 8f
            val rot = if (p < 1) (1 - easeOutBack(p)) * -25f else 0f
            val scale = if (p < 1) 0.4f + 0.6f * easeOutBack(p) else 1f
            drawEmblem(c, scale, min(1f, p * 3f), rot, name, primary)
            flashRing(c, (f - 14f) / 10f)
        } else if (f >= 35f && f < 40f) {
            val p = (f - 35f) / 5f
            drawEmblem(c, 1f - 0.6f * easeOut(p), 1f - p, 0f, name, primary)
        }
    }

    private fun drawStreaks(c: Canvas, f: Float) {
        val off = (f * 40f) % TEX_W
        val slant = 0.38f
        for (s in streaks) {
            var x = s.x - off
            if (x < -1000f) x += TEX_W
            if (x > W + 400f) continue
            val y = s.y; val len = s.len; val th = s.thick
            val dx = slant * th
            fill.color = s.color
            path.reset()
            path.moveTo(x, y); path.lineTo(x + len, y); path.lineTo(x + len - dx, y + th); path.lineTo(x - dx, y + th)
            path.close()
            c.drawPath(path, fill)
        }
    }

    private val path = Path()

    /** Logo du club (ou son nom s'il n'a pas de logo) centré, à l'échelle [scale]. */
    private fun drawEmblem(c: Canvas, scale: Float, alpha: Float, rot: Float, name: String, primary: Int) {
        if (scale <= 0.01f || alpha <= 0f) return
        c.save()
        c.rotate(rot, W / 2, H / 2)
        val bmp = logo
        if (bmp != null) {
            val h = H * 0.62f * scale
            val w = h * bmp.width / bmp.height
            val maxW = W * 0.6f * scale
            val s = min(1f, maxW / w)
            val dw = w * s; val dh = h * s
            bitmapPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            c.drawBitmap(bmp, null, RectF(W / 2 - dw / 2, H / 2 - dh / 2, W / 2 + dw / 2, H / 2 + dh / 2), bitmapPaint)
        } else {
            text.textSize = 260f * scale
            val measured = text.measureText(name)
            if (measured > 1500f * scale) text.textSize *= 1500f * scale / measured
            text.color = Color.WHITE
            text.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            text.setShadowLayer(18f, 0f, 6f, Colors.deep(primary))
            c.drawText(name, W / 2, H / 2 + text.textSize * 0.35f, text)
        }
        c.restore()
    }

    /** Onde de choc blanche à l'arrivée du logo (progress 0 → 1). */
    private fun flashRing(c: Canvas, progress: Float) {
        if (progress <= 0f || progress >= 1f) return
        val r = 200f + progress * 900f
        stroke.color = Color.WHITE
        stroke.alpha = (220 * (1 - progress)).toInt()
        stroke.strokeWidth = 40f * (1 - progress) + 2f
        c.drawCircle(W / 2, H / 2, r, stroke)
    }

    /** Demi-plan délimité par la diagonale décalée de [shift] le long de la normale, du côté [side]. */
    private fun halfPlane(side: Float, shift: Float): Path {
        val cx = W / 2 + nx * shift
        val cy = H / 2 + ny * shift
        val p1x = cx - vx * FAR; val p1y = cy - vy * FAR
        val p2x = cx + vx * FAR; val p2y = cy + vy * FAR
        return Path().apply {
            moveTo(p1x, p1y)
            lineTo(p2x, p2y)
            lineTo(p2x + side * nx * FAR, p2y + side * ny * FAR)
            lineTo(p1x + side * nx * FAR, p1y + side * ny * FAR)
            close()
        }
    }

    private fun easeInOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2
    }

    private fun easeOutBack(t: Float, s: Float = 1.9f): Float {
        val x = t.coerceIn(0f, 1f) - 1
        return x * x * ((s + 1) * x + s) + 1
    }

    private fun easeOut(t: Float): Float {
        val x = 1 - t.coerceIn(0f, 1f)
        return 1 - x * x * x
    }

    companion object {
        private const val W = 1920f
        private const val H = 1080f
        private const val FAR = 5000f
        private const val TEX_W = 3840f

        /** Images par seconde de l'animation (repère de temps des 50 images, comme Foot Live). */
        const val FPS = 30f
        const val FRAMES = 50
        /** Image où l'écran est entièrement couvert : c'est là qu'on change de scène. */
        const val CUT_FRAME = 22
        val DURATION_MS = (FRAMES * 1000 / FPS).toLong()
        val CUT_MS = (CUT_FRAME * 1000 / FPS).toLong()
    }
}
