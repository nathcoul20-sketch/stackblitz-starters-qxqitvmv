package com.volleylive.app

import android.content.Context

/** Réglages mémorisés entre deux utilisations de l'application. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("volleylive", Context.MODE_PRIVATE)

    /** Nom de la plateforme choisie (« Twitch » par défaut). */
    var platform: String
        get() = sp.getString("platformName", "Twitch") ?: "Twitch"
        set(v) = sp.edit().putString("platformName", v).apply()

    var streamKey: String
        get() = sp.getString("streamKey", "") ?: ""
        set(v) = sp.edit().putString("streamKey", v).apply()

    var homeName: String
        get() = sp.getString("homeName", "ECVB") ?: "ECVB"
        set(v) = sp.edit().putString("homeName", v).apply()

    var awayName: String
        get() = sp.getString("awayName", "VISITEUR") ?: "VISITEUR"
        set(v) = sp.edit().putString("awayName", v).apply()

    /** Compétition : un nom de la liste, ou un nom libre (« Autre »). */
    var competition: String
        get() = sp.getString("competition", null)
            ?.takeIf { it.isNotBlank() && it != "Championnat Régional Masculin" } // ancienne valeur par défaut
            ?: DEFAULT_COMPETITION
        set(v) = sp.edit().putString("competition", v).apply()

    /** Qualité du live choisie sur l'écran caméra (Auto, Haute, Moyenne, Basse). */
    var quality: String
        get() = sp.getString("quality", null) ?: if (sp.getBoolean("quality1080", false)) "HIGH" else "AUTO"
        set(v) = sp.edit().putString("quality", v).apply()

    /** Logos des partenaires aussi pendant le match (un à la fois, en bas à droite). */
    var partnersInMatch: Boolean
        get() = sp.getBoolean("partnersInMatch", false)
        set(v) = sp.edit().putBoolean("partnersInMatch", v).apply()

    // ---- Twitch (titre du live automatique) ----

    var twitchClientId: String
        get() = sp.getString("twitchClientId", "") ?: ""
        set(v) = sp.edit().putString("twitchClientId", v).apply()

    var twitchAccessToken: String
        get() = sp.getString("twitchAccessToken", "") ?: ""
        set(v) = sp.edit().putString("twitchAccessToken", v).apply()

    var twitchRefreshToken: String
        get() = sp.getString("twitchRefreshToken", "") ?: ""
        set(v) = sp.edit().putString("twitchRefreshToken", v).apply()

    var twitchUserId: String
        get() = sp.getString("twitchUserId", "") ?: ""
        set(v) = sp.edit().putString("twitchUserId", v).apply()

    var twitchLogin: String
        get() = sp.getString("twitchLogin", "") ?: ""
        set(v) = sp.edit().putString("twitchLogin", v).apply()

    /** Nom de la chaîne Twitch (chat sur la télécommande) si le compte n'est pas connecté. */
    var twitchChannel: String
        get() = sp.getString("twitchChannel", "") ?: ""
        set(v) = sp.edit().putString("twitchChannel", v).apply()

    var twitchAutoTitle: Boolean
        get() = sp.getBoolean("twitchAutoTitle", true)
        set(v) = sp.edit().putBoolean("twitchAutoTitle", v).apply()

    /** Code PIN de la télécommande, généré une fois puis conservé (évite de re-scanner à chaque match). */
    val pin: String
        get() = sp.getString("pin", null) ?: (1000..9999).random().toString().also {
            sp.edit().putString("pin", it).apply()
        }

    companion object {
        /** Compétitions proposées dans la liste déroulante (accueil et télécommande). */
        val COMPETITIONS = listOf(
            "Championnat National 3 Masculin",
            "Championnat Régional 1 Masculin",
            "Championnat Régional 2 Féminin",
            "Championnat Départemental Masculin",
            "Championnat Départemental Féminin",
        )
        val DEFAULT_COMPETITION = COMPETITIONS[1]
        /** Dernier choix de la liste : saisie libre. */
        const val OTHER_COMPETITION = "Autre…"
    }
}
