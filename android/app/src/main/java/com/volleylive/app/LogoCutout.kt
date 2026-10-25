package com.volleylive.app

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Retire le fond blanc uni d'un logo (logo enregistré en JPG ou en PNG sans transparence), pour
 * l'afficher directement sur la vidéo :
 *  - le blanc relié au bord de l'image devient transparent, avec un bord adouci (pixels clairs
 *    de l'anticrénelage rendus semi-transparents, leur couleur d'origine restituée) ;
 *  - les « trous » blancs à l'intérieur des lettres (O, A, B…) et des anneaux deviennent aussi transparents ;
 *  - le blanc voulu reste blanc : texte blanc dans un pavé de couleur (pavé avec 3 trous ou plus,
 *    bien rempli), grande zone blanche (plus de 6 % du logo).
 * Un logo déjà transparent, ou posé sur un fond de couleur, est rendu tel quel.
 */
object LogoCutout {

    /** Écart au blanc (255 − plus petite composante) en dessous duquel un pixel est du fond. */
    private const val HARD = 28
    /** Jusqu'à cet écart, le pixel est encore « clair » : semi-transparent s'il touche le fond. */
    private const val SOFT = 80
    /** Une zone blanche intérieure plus grande que cette part du logo est gardée (dessin voulu). */
    private const val MAX_HOLE = 0.06f

    private const val FREE: Byte = 0
    private const val REMOVED: Byte = 1
    private const val BUSY: Byte = 2
    private const val HOLE: Byte = 3

    fun removeWhiteBackground(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 3 || h < 3) return src
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val dist = IntArray(n) {
            val p = px[it]
            255 - minOf(p shr 16 and 255, p shr 8 and 255, p and 255)
        }

        val border = IntArray(2 * w + 2 * (h - 2))
        var b = 0
        for (x in 0 until w) { border[b++] = x; border[b++] = (h - 1) * w + x }
        for (y in 1 until h - 1) { border[b++] = y * w; border[b++] = y * w + w - 1 }
        // Bord déjà transparent : logo préparé pour la vidéo. Bord coloré : le logo a son propre fond.
        if (border.count { px[it] ushr 24 >= 250 } < border.size * 0.9f) return src
        if (border.count { dist[it] <= HARD } < border.size * 0.5f) return src

        val mark = ByteArray(n)
        val queue = IntArray(n)
        var tail = 0
        fun push(i: Int) {
            if (mark[i] == FREE && dist[i] <= SOFT) {
                mark[i] = BUSY
                queue[tail++] = i
            }
        }
        /** Étend la zone claire à partir des pixels déjà dans la file ; elle occupe queue[0 until tail]. */
        fun grow() {
            var head = 0
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                if (x > 0) push(i - 1)
                if (x < w - 1) push(i + 1)
                if (i >= w) push(i - w)
                if (i < n - w) push(i + w)
            }
        }

        // 1. Fond : tout le clair relié au bord
        tail = 0
        border.forEach { push(it) }
        grow()
        for (k in 0 until tail) mark[queue[k]] = REMOVED

        // 2. Parties colorées : aire, cadre et nombre de trous blancs de chacune
        val part = IntArray(n) { -1 }
        val areas = ArrayList<Int>()
        val boxes = ArrayList<Int>()
        val stack = IntArray(n)
        for (s in 0 until n) {
            if (part[s] >= 0 || mark[s] != FREE || dist[s] <= SOFT) continue
            val id = areas.size
            var top = 0
            stack[top++] = s
            part[s] = id
            var count = 0
            var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
            while (top > 0) {
                val i = stack[--top]
                count++
                val x = i % w
                val y = i / w
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                for (dir in 0 until 4) {
                    val j = when (dir) {
                        0 -> if (x > 0) i - 1 else -1
                        1 -> if (x < w - 1) i + 1 else -1
                        2 -> i - w
                        else -> if (i < n - w) i + w else -1
                    }
                    if (j >= 0 && part[j] < 0 && mark[j] == FREE && dist[j] > SOFT) {
                        part[j] = id
                        stack[top++] = j
                    }
                }
            }
            areas.add(count)
            boxes.add((x1 - x0 + 1) * (y1 - y0 + 1))
        }

        // 3. Trous blancs à l'intérieur du logo, chacun rattaché à la partie colorée qui l'entoure
        val holeOf = IntArray(n) { -1 }
        val holeSize = ArrayList<Int>()
        val holeOwner = ArrayList<Int>()
        val holesPerPart = IntArray(areas.size)
        for (s in 0 until n) {
            if (mark[s] != FREE || dist[s] > HARD) continue
            tail = 0
            push(s)
            grow()
            val id = holeSize.size
            var owner = -1
            for (k in 0 until tail) {
                val i = queue[k]
                mark[i] = HOLE
                holeOf[i] = id
                if (owner < 0) {
                    val x = i % w
                    if (x > 0 && part[i - 1] >= 0) owner = part[i - 1]
                    else if (x < w - 1 && part[i + 1] >= 0) owner = part[i + 1]
                    else if (i >= w && part[i - w] >= 0) owner = part[i - w]
                    else if (i < n - w && part[i + w] >= 0) owner = part[i + w]
                }
            }
            holeSize.add(tail)
            holeOwner.add(owner)
            if (owner >= 0) holesPerPart[owner]++
        }
        val removeHole = BooleanArray(holeSize.size) { id ->
            val owner = holeOwner[id]
            val panel = owner >= 0 && holesPerPart[owner] >= 3 && areas[owner] >= 0.35f * boxes[owner]
            holeSize[id] <= MAX_HOLE * n && !panel
        }

        // 4. Transparence : totale pour le blanc, partielle (couleur restituée) pour le bord adouci
        var changed = false
        for (i in 0 until n) {
            val drop = mark[i] == REMOVED || (holeOf[i] >= 0 && removeHole[holeOf[i]])
            if (!drop) continue
            changed = true
            val a = ((dist[i] - HARD).toFloat() / (SOFT - HARD)).coerceIn(0f, 1f)
            if (a <= 0f) {
                px[i] = 0
                continue
            }
            val p = px[i]
            fun unblend(c: Int) = ((c - 255f * (1f - a)) / a).roundToInt().coerceIn(0, 255)
            val alpha = (a * (p ushr 24)).roundToInt().coerceIn(0, 255)
            px[i] = (alpha shl 24) or (unblend(p shr 16 and 255) shl 16) or
                (unblend(p shr 8 and 255) shl 8) or unblend(p and 255)
        }
        if (!changed) return src
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }
}
