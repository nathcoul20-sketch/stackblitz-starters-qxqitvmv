package com.volleylive.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Logos des partenaires du club, copiés dans l'appli (dossier privé « partners »).
 * Affichés sur l'écran de fin (« Merci à nos partenaires ») et, si l'option est activée,
 * un à la fois en bas à droite pendant le match.
 */
object Partners {

    const val MAX = 8

    private fun dir(context: Context) = File(context.filesDir, "partners").apply { mkdirs() }

    /** Fichiers des logos, dans l'ordre d'ajout. */
    fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name } ?: emptyList()

    fun logos(context: Context): List<Bitmap> =
        files(context).mapNotNull { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }

    /** Ajoute un logo choisi dans la galerie (réduit à 600 px de côté). Renvoie false si l'image est illisible. */
    fun add(context: Context, uri: Uri): Boolean {
        val bmp = loadScaledImage(context, uri, 600) ?: return false
        val file = File(dir(context), "partenaire_${System.currentTimeMillis()}.png")
        return runCatching {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
    }

    fun remove(file: File) {
        file.delete()
    }

    /**
     * Lit une image en entier puis la décode (décoder directement le flux de la galerie coupe parfois
     * le bas de l'image), réduite à [maxSize] px sur son plus grand côté.
     */
    private fun loadScaledImage(context: Context, uri: Uri, maxSize: Int): Bitmap? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                // Bitmap « logiciel » : nécessaire pour l'enregistrer et le dessiner dans l'incrustation
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, maxSize.toFloat() / maxOf(w, h))
                if (scale < 1f) decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val scale = minOf(1f, maxSize.toFloat() / maxOf(decoded.width, decoded.height))
            if (scale >= 1f) decoded
            else Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        }
    }.getOrNull()
}
