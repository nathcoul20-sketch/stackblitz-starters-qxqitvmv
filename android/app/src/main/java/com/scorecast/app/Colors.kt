package com.scorecast.app

import android.graphics.Color

/** Outils couleur : palette proposée, contraste du texte, conversions. */
object Colors {

    /**
     * Palette proposée pour les couleurs de club : 12 teintes × 4 nuances, plus les neutres.
     * Les teintes couvrent les couleurs de maillot courantes (rouge, bordeaux, orange, jaune, vert,
     * turquoise, bleu ciel, bleu roi, marine, violet, rose…).
     */
    val palette: List<Int> by lazy {
        val hues = listOf(
            // rouge, bordeaux, orange, or, jaune, vert pomme, vert, turquoise, ciel, bleu, violet, rose
            0xE30613, 0x8E1B3A, 0xF26B1D, 0xF5B800, 0xFFE600, 0x7BC043,
            0x0B8F3C, 0x00A19A, 0x3AA8E0, 0x1E4FD8, 0x6B2FA0, 0xE5468A,
        )
        val shades = listOf(1.35f, 1f, 0.7f, 0.45f)
        val list = mutableListOf<Int>()
        for (factor in shades) for (h in hues) list += shade(0xFF000000.toInt() or h, factor)
        // Neutres : blanc → noir, et quelques couleurs « classiques » de club
        list += listOf(
            0xFFFFFFFF, 0xFFD9D9D9, 0xFFA6A6A6, 0xFF6E6E6E, 0xFF3C3C3C, 0xFF000000,
            0xFF0B1F44, 0xFF14284B, 0xFF7A0019, 0xFFC8A951, 0xFF5B3A29, 0xFF2F4F2F,
        ).map { it.toInt() }
        list
    }

    /** Éclaircit (facteur > 1, vers le blanc) ou assombrit (facteur < 1) une couleur. */
    fun shade(color: Int, factor: Float): Int {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return if (factor >= 1f) {
            val t = (factor - 1f).coerceIn(0f, 1f)
            Color.rgb(
                (r + (255 - r) * t).toInt(), (g + (255 - g) * t).toInt(), (b + (255 - b) * t).toInt(),
            )
        } else {
            Color.rgb((r * factor).toInt(), (g * factor).toInt(), (b * factor).toInt())
        }
    }

    /** Luminance relative (WCAG). */
    fun luminance(color: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(Color.red(color)) + 0.7152 * ch(Color.green(color)) + 0.0722 * ch(Color.blue(color))
    }

    /** Texte lisible sur [color] : noir sur fond clair, blanc sur fond foncé. */
    fun onColor(color: Int): Int = if (luminance(color) > 0.36) Color.BLACK else Color.WHITE

    /** Les deux couleurs sont-elles trop proches pour être distinguées côte à côte ? */
    fun tooClose(a: Int, b: Int): Boolean {
        val la = luminance(a); val lb = luminance(b)
        val contrast = (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        return contrast < 1.6 && dr * dr + dg * dg + db * db < 90 * 90
    }

    fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    /** « #1E4FD8 », « 1e4fd8 » ou « #FFF » → couleur opaque, null si invalide. */
    fun parse(text: String): Int? {
        var t = text.trim().removePrefix("#")
        if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6 || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return 0xFF000000.toInt() or t.toInt(16)
    }

    /** Version plus sombre pour les fonds (dégradés des écrans générés). */
    fun deep(color: Int): Int = shade(color, 0.35f)
}
