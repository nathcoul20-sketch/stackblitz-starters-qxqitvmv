package com.scorecast.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** Identifiant du club transmis d'un écran à l'autre. */
const val EXTRA_CLUB_ID = "clubId"

/** Numéro d'étape à afficher (écrans accessibles depuis plusieurs étapes). */
const val EXTRA_STEP = "step"

/** Petits outils d'interface partagés par les écrans. */
object Ui {
    const val STEPS = 5

    fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density).toInt()

    /** En-tête d'étape (layout step_header) : bouton retour, « ÉTAPE n SUR 5 », titre, sous-titre. */
    fun header(activity: androidx.activity.ComponentActivity, step: Int, title: String, subtitle: String? = null) {
        activity.findViewById<ImageButton>(R.id.back).setOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }
        activity.findViewById<TextView>(R.id.step).text = "ÉTAPE $step SUR $STEPS"
        activity.findViewById<TextView>(R.id.title).text = title
        val sub = activity.findViewById<TextView>(R.id.subtitle)
        sub.text = subtitle ?: ""
        sub.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        val bar = activity.findViewById<LinearLayout>(R.id.progress)
        bar.removeAllViews()
        for (i in 1..STEPS) {
            bar.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(activity, 18f), dp(activity, 4f)).apply {
                    marginStart = dp(activity, 4f)
                }
                background = rounded(if (i <= step) activity.getColor(R.color.sc_brand) else activity.getColor(R.color.sc_line), dp(activity, 2f).toFloat())
            })
        }
    }

    fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    fun gradient(top: Int, bottom: Int, radius: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)).apply { cornerRadius = radius }

    /**
     * Pastille d'un club : son logo s'il en a un, sinon ses initiales sur sa couleur principale.
     */
    fun clubBadge(context: Context, club: Club, logo: Bitmap?, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val r = sizePx * 0.24f
        if (logo != null) {
            p.color = Color.WHITE
            c.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), r, r, p)
            val inset = sizePx * 0.1f
            val box = sizePx - 2 * inset
            val scale = minOf(box / logo.width, box / logo.height)
            val w = logo.width * scale
            val h = logo.height * scale
            c.drawBitmap(logo, null, RectF((sizePx - w) / 2, (sizePx - h) / 2, (sizePx + w) / 2, (sizePx + h) / 2), p)
        } else {
            p.color = club.primary
            c.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), r, r, p)
            p.color = Colors.onColor(club.primary)
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.createFromAsset(context.assets, "fonts/BebasNeue-Regular.ttf")
            p.textSize = sizePx * 0.5f
            c.drawText(initials(club.name), sizePx / 2f, sizePx * 0.68f, p)
        }
        return bmp
    }

    fun initials(name: String): String {
        val words = name.trim().split(Regex("[\\s\\-]+")).filter { it.isNotEmpty() && it.first().isLetterOrDigit() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(3).uppercase()
            else -> words.take(3).joinToString("") { it.first().uppercase() }
        }
    }

    /**
     * Charge une image choisie par l'utilisateur, réduite à [maxSize] px de côté (le logo n'a pas
     * besoin d'être plus grand : 300 px max à l'écran en 1080p).
     */
    fun loadScaledImage(context: Context, uri: Uri, maxSize: Int = 600): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = minOf(1f, maxSize.toFloat() / maxOf(decoded.width, decoded.height))
        if (scale >= 1f) decoded
        else Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
    }.getOrNull()

    /** Copie un fichier choisi par l'utilisateur dans [dest]. Renvoie la taille copiée (octets). */
    fun copyUri(context: Context, uri: Uri, dest: File): Long {
        val tmp = File(dest.parentFile, dest.name + ".part")
        val size = context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
        } ?: throw IllegalStateException("Fichier illisible")
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true); tmp.delete()
        }
        return size
    }

    fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "fichier"

    fun sizeText(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(java.util.Locale.FRANCE, "%.1f Mo", bytes / 1_000_000.0)
        else -> "${(bytes / 1000).coerceAtLeast(1)} ko"
    }
}
