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
 * un à la fois en bas à droite pendant le match. Pour la vidéo, leur fond blanc est retiré
 * ([LogoCutout]) ; le résultat est gardé dans un dossier cache à côté des originaux.
 */
object Partners {

    const val MAX = 8

    private fun dir(context: Context) = File(context.filesDir, "partners").apply { mkdirs() }

    /** Logos détourés. Changer [CUT_VERSION] (réglage du détourage modifié) les refait tous. */
    private const val CUT_VERSION = 1
    private fun cutDir(filesDir: File) = File(filesDir, "partners_cut_v$CUT_VERSION").apply { mkdirs() }

    /** Fichiers des logos, dans l'ordre d'ajout. */
    fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name } ?: emptyList()

    /** Logos prêts pour la vidéo, sans leur fond blanc (détourés une fois, puis lus dans le cache). */
    fun logos(context: Context): List<Bitmap> =
        files(context).mapNotNull { file -> runCatching { cutLogo(context.filesDir, file) }.getOrNull() }

    private fun cutLogo(filesDir: File, file: File): Bitmap? {
        val cached = File(cutDir(filesDir), file.name)
        if (cached.exists()) BitmapFactory.decodeFile(cached.path)?.let { return it }
        val original = BitmapFactory.decodeFile(file.path) ?: return null
        val cut = runCatching { LogoCutout.removeWhiteBackground(original) }.getOrDefault(original)
        runCatching { cached.outputStream().use { cut.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return cut
    }

    /** Ajoute un logo choisi dans la galerie (réduit à 600 px de côté). Renvoie false si l'image est illisible. */
    fun add(context: Context, uri: Uri): Boolean {
        val bmp = loadScaledImage(context, uri, 600) ?: return false
        val file = File(dir(context), "partenaire_${System.currentTimeMillis()}.png")
        val saved = runCatching {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
        // Détourage tout de suite : l'ouverture de la caméra n'a plus qu'à lire le résultat
        if (saved) runCatching { cutLogo(context.filesDir, file) }
        return saved
    }

    fun remove(file: File) {
        file.delete()
        file.parentFile?.parentFile?.let { File(cutDir(it), file.name).delete() }
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
