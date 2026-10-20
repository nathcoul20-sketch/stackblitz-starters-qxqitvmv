package com.scorecast.app

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copie d'un club d'un téléphone à l'autre, par le Wi-Fi local (ou le point d'accès d'un des deux).
 *
 * Le téléphone qui envoie prépare une archive (fiche du club + logo + partenaires + médias),
 * démarre un petit serveur web et affiche un QR code contenant son adresse et un code à usage
 * unique. Le téléphone qui reçoit scanne le QR code depuis l'appli, télécharge l'archive et
 * enregistre le club (s'il existe déjà, il est mis à jour).
 */
object ClubShare {

    const val PORT = 8090
    private const val PREFIX = "SCORECAST-CLUB|"

    /** Serveur d'envoi : sert l'archive à qui présente le bon code. */
    class Sender(private val zip: File, val token: String) : NanoHTTPD(PORT) {
        override fun serve(session: IHTTPSession): Response {
            if (session.uri != "/club" || session.parameters["t"]?.firstOrNull() != token) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Refusé")
            }
            return newFixedLengthResponse(Response.Status.OK, "application/zip", FileInputStream(zip), zip.length())
        }
    }

    /** Prépare l'envoi : archive + serveur. Renvoie le texte du QR code (null si pas de réseau local). */
    fun startSending(context: Context, repo: ClubRepository, club: Club): Pair<Sender, String>? {
        val ip = NetworkUtils.localIpAddresses().firstOrNull() ?: return null
        val zip = File(context.cacheDir, "club_partage.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("club.json"))
            out.write(club.toJson().toString().toByteArray())
            out.closeEntry()
            repo.dir(club).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.forEach { f ->
                out.putNextEntry(ZipEntry("files/" + f.name))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        val sender = Sender(zip, token)
        sender.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        return sender to "${PREFIX}http://$ip:$PORT/club?t=$token"
    }

    /** Le texte scanné est-il un QR code d'envoi de club ScoreCast ? Renvoie l'adresse à télécharger. */
    fun parse(qr: String): String? = qr.trim().takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
        ?.takeIf { it.startsWith("http://") }

    /** Télécharge et enregistre le club (à appeler hors du thread principal). */
    fun receive(context: Context, repo: ClubRepository, url: String): Club {
        val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).build()
        val tmp = File(context.cacheDir, "club_recu.zip")
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Le téléphone qui envoie a refusé (${resp.code})")
            resp.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("Réponse vide")
        }
        var clubJson: JSONObject? = null
        val staging = File(context.cacheDir, "club_recu").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(tmp.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                when {
                    entry.name == "club.json" -> clubJson = JSONObject(zin.readBytes().toString(Charsets.UTF_8))
                    entry.name.startsWith("files/") -> {
                        val name = File(entry.name).name // jamais de chemin : protège contre « ../ »
                        if (name.isNotBlank()) File(staging, name).outputStream().use { zin.copyTo(it) }
                    }
                }
            }
        }
        tmp.delete()
        val club = Club.fromJson(clubJson ?: throw IllegalStateException("Archive incomplète"))
        // Remplace les fichiers du club (même identifiant : mise à jour d'un club déjà copié)
        val dir = repo.dir(club)
        dir.listFiles()?.forEach { it.delete() }
        staging.listFiles()?.forEach { f -> f.copyTo(File(dir, f.name), overwrite = true) }
        staging.deleteRecursively()
        repo.save(club)
        return club
    }
}
