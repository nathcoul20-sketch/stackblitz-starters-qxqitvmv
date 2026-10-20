package com.scorecast.app

import android.content.Context

/**
 * Plateformes de diffusion proposées.
 * [url] : serveur d'ingestion fixe (null : l'utilisateur le saisit, la plateforme le fournit avec la clé).
 * [backupUrl] : 2e serveur essayé en alternance si le premier refuse la connexion.
 */
enum class Platform(
    val id: String,
    val label: String,
    val color: Int,
    val url: String?,
    val backupUrl: String? = null,
    val keyHelp: String,
    val note: String = "",
) {
    TWITCH(
        "twitch", "Twitch", 0xFF9146FF.toInt(),
        // RTMPS sur le port 443 en premier : il passe aussi sur les Wi-Fi qui bloquent le port 1935
        url = "rtmps://ingest.global-contribute.live-video.net:443/app",
        backupUrl = "rtmp://ingest.global-contribute.live-video.net/app",
        keyHelp = "Tableau de bord des créateurs → Paramètres → Stream → « Clé de stream principale » (Copier).",
        note = "Vous pouvez aussi connecter le compte Twitch : le titre du live sera mis à jour automatiquement.",
    ),
    YOUTUBE(
        "youtube", "YouTube", 0xFFFF0033.toInt(),
        url = "rtmps://a.rtmps.youtube.com:443/live2",
        backupUrl = "rtmp://a.rtmp.youtube.com/live2",
        keyHelp = "YouTube Studio → Créer → Passer au direct → Diffusion → « Clé du flux » (Copier).",
        note = "La première activation du direct sur une chaîne YouTube peut prendre jusqu'à 24 h. " +
            "Le titre se règle dans YouTube Studio.",
    ),
    FACEBOOK(
        "facebook", "Facebook", 0xFF1877F2.toInt(),
        url = "rtmps://live-api-s.facebook.com:443/rtmp/",
        keyHelp = "Facebook → Vidéo en direct → « Logiciel de streaming » → Clé de stream (Copier).",
        note = "Le titre et la description se règlent sur Facebook, avant de lancer le live.",
    ),
    KICK(
        "kick", "Kick", 0xFF53FC18.toInt(),
        url = null,
        keyHelp = "Kick → Paramètres de la chaîne → Clé de stream : copiez l'« URL du stream » et la « Clé de stream ».",
    ),
    TIKTOK(
        "tiktok", "TikTok", 0xFF25F4EE.toInt(),
        url = null,
        keyHelp = "TikTok LIVE Studio / Centre LIVE → « Diffuser avec un logiciel » : copiez l'URL du serveur et la clé.",
        note = "La diffusion par clé n'est ouverte qu'à certains comptes TikTok.",
    ),
    INSTAGRAM(
        "instagram", "Instagram", 0xFFE1306C.toInt(),
        url = null,
        keyHelp = "Instagram (sur ordinateur) → Créer → Vidéo en direct → copiez l'URL du stream et la clé.",
        note = "Instagram affiche le live en format vertical.",
    ),
    CUSTOM(
        "custom", "Autre (RTMP)", 0xFF7A7A85.toInt(),
        url = null,
        keyHelp = "Collez l'adresse du serveur (rtmp:// ou rtmps://) et la clé fournies par la plateforme.",
    ),
    NONE(
        "none", "Sans diffusion", 0xFF3C3C46.toInt(),
        url = "",
        keyHelp = "",
        note = "Pour tester l'affichage et la télécommande sans rien diffuser.",
    );

    val needsServerUrl: Boolean get() = url == null
    val needsKey: Boolean get() = this != NONE

    companion object {
        fun fromId(id: String?): Platform? = entries.firstOrNull { it.id == id }
    }
}

/** Réglages globaux de l'appli (hors clubs) : code PIN, sport et club en cours. */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("scorecast", Context.MODE_PRIVATE)

    var sport: Sport
        get() = Sport.fromId(sp.getString("sport", null))
        set(v) = sp.edit().putString("sport", v.id).apply()

    var clubId: String?
        get() = sp.getString("clubId", null)
        set(v) = sp.edit().putString("clubId", v).apply()

    /** Code PIN de la télécommande, généré une fois puis conservé (évite de re-scanner à chaque match). */
    val pin: String
        get() = sp.getString("pin", null) ?: (1000..9999).random().toString().also {
            sp.edit().putString("pin", it).apply()
        }
}
