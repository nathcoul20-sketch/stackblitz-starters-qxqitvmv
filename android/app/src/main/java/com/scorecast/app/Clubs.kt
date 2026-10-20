package com.scorecast.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Les médias qu'un club peut importer (aucun n'est fourni par défaut). */
enum class MediaSlot(val id: String, val label: String, val help: String, val isAudio: Boolean = false) {
    INTRO("intro", "Écran d'intro", "Diffusé en boucle avant le match."),
    HALFTIME("halftime", "Écran de pause / mi-temps", "Diffusé en boucle pendant la pause, avec le score par-dessus."),
    OUTRO("outro", "Écran de fin", "Diffusé en boucle après le match, avec le résultat par-dessus."),
    TRANSITION("transition", "Transition", "Jouée à chaque changement d'écran ; l'écran change au milieu de la vidéo."),
    MUSIC("music", "Musique d'attente", "Remplace le son du micro sur les écrans d'intro, de pause et de fin.", isAudio = true);

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id }
    }
}

/**
 * Un club enregistré : identité visuelle, plateforme de diffusion, derniers réglages de match.
 * Les fichiers (logo, médias) sont copiés dans le stockage privé de l'appli (dossier du club).
 */
class Club(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var primary: Int = 0xFF1E4FD8.toInt(),
    var secondary: Int = 0xFFFFFFFF.toInt(),
    var hasLogo: Boolean = false,
    var lastSport: String = "",
    // Plateforme
    var platformId: String = "",
    /** Clé de diffusion et adresse du serveur, mémorisées par plateforme (id → valeur). */
    val streamKeys: MutableMap<String, String> = mutableMapOf(),
    val serverUrls: MutableMap<String, String> = mutableMapOf(),
    var quality1080: Boolean = false,
    // Twitch (titre du live automatique)
    var twitchClientId: String = "",
    var twitchAccessToken: String = "",
    var twitchRefreshToken: String = "",
    var twitchUserId: String = "",
    var twitchLogin: String = "",
    var twitchAutoTitle: Boolean = true,
    // Derniers réglages de match
    var competition: String = "",
    var awayName: String = "",
    var awayColor: Int = 0xFF2B2B33.toInt(),
    val periodMinutes: MutableMap<String, Int> = mutableMapOf(),
    /** Médias importés : emplacement → nom du fichier dans le dossier du club. */
    val media: MutableMap<MediaSlot, String> = mutableMapOf(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("primary", primary); put("secondary", secondary)
        put("hasLogo", hasLogo); put("lastSport", lastSport)
        put("platformId", platformId)
        put("streamKeys", JSONObject(streamKeys as Map<*, *>)); put("serverUrls", JSONObject(serverUrls as Map<*, *>))
        put("quality1080", quality1080)
        put("twitchClientId", twitchClientId); put("twitchAccessToken", twitchAccessToken)
        put("twitchRefreshToken", twitchRefreshToken); put("twitchUserId", twitchUserId)
        put("twitchLogin", twitchLogin); put("twitchAutoTitle", twitchAutoTitle)
        put("competition", competition); put("awayName", awayName); put("awayColor", awayColor)
        put("periodMinutes", JSONObject(periodMinutes as Map<*, *>))
        put("media", JSONObject().also { o -> media.forEach { (k, v) -> o.put(k.id, v) } })
    }

    companion object {
        private fun strings(o: JSONObject?): MutableMap<String, String> =
            o?.let { m -> m.keys().asSequence().associateWith { m.getString(it) }.toMutableMap() } ?: mutableMapOf()

        fun fromJson(o: JSONObject) = Club(
            id = o.getString("id"),
            name = o.optString("name"),
            primary = o.optInt("primary", 0xFF1E4FD8.toInt()),
            secondary = o.optInt("secondary", 0xFFFFFFFF.toInt()),
            hasLogo = o.optBoolean("hasLogo"),
            lastSport = o.optString("lastSport"),
            platformId = o.optString("platformId"),
            streamKeys = strings(o.optJSONObject("streamKeys")),
            serverUrls = strings(o.optJSONObject("serverUrls")),
            quality1080 = o.optBoolean("quality1080"),
            twitchClientId = o.optString("twitchClientId"),
            twitchAccessToken = o.optString("twitchAccessToken"),
            twitchRefreshToken = o.optString("twitchRefreshToken"),
            twitchUserId = o.optString("twitchUserId"),
            twitchLogin = o.optString("twitchLogin"),
            twitchAutoTitle = o.optBoolean("twitchAutoTitle", true),
            competition = o.optString("competition"),
            awayName = o.optString("awayName"),
            awayColor = o.optInt("awayColor", 0xFF2B2B33.toInt()),
            periodMinutes = o.optJSONObject("periodMinutes")?.let { p ->
                p.keys().asSequence().associateWith { p.getInt(it) }.toMutableMap()
            } ?: mutableMapOf(),
            media = o.optJSONObject("media")?.let { m ->
                m.keys().asSequence().mapNotNull { k -> MediaSlot.fromId(k)?.let { it to m.getString(k) } }
                    .toMap().toMutableMap()
            } ?: mutableMapOf(),
        )
    }
}

/** Enregistrement des clubs (fichier JSON privé) et de leurs fichiers. */
class ClubRepository(context: Context) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "clubs").apply { mkdirs() }
    private val index = File(root, "clubs.json")

    fun all(): List<Club> = synchronized(LOCK) {
        if (!index.exists()) return emptyList()
        runCatching {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).map { Club.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun get(id: String?): Club? = id?.let { wanted -> all().firstOrNull { it.id == wanted } }

    /** Ajoute ou remplace le club (même id). Le dernier enregistré passe en tête de liste. */
    fun save(club: Club) = synchronized(LOCK) {
        val list = all().filterNot { it.id == club.id }.toMutableList()
        list.add(0, club)
        write(list)
    }

    fun delete(club: Club) = synchronized(LOCK) {
        write(all().filterNot { it.id == club.id })
        dir(club).deleteRecursively()
    }

    fun dir(club: Club): File = File(root, club.id).apply { mkdirs() }

    fun logoFile(club: Club): File = File(dir(club), "logo.png")

    fun logo(club: Club): Bitmap? =
        if (club.hasLogo) runCatching { BitmapFactory.decodeFile(logoFile(club).path) }.getOrNull() else null

    fun mediaFile(club: Club, slot: MediaSlot): File? =
        club.media[slot]?.let { File(dir(club), it) }?.takeIf { it.exists() }

    private fun write(list: List<Club>) {
        val tmp = File(root, "clubs.json.tmp")
        tmp.writeText(JSONArray(list.map { it.toJson() }).toString())
        tmp.renameTo(index)
    }

    companion object {
        private val LOCK = Any()
    }
}
