package com.footlive.app

import android.content.Context

/** Réglages mémorisés entre deux utilisations de l'application. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("footlive", Context.MODE_PRIVATE)

    /** Nom de la plateforme choisie (« Twitch » par défaut). */
    var platform: String
        get() = sp.getString("platformName", "Twitch") ?: "Twitch"
        set(v) = sp.edit().putString("platformName", v).apply()

    var serverUrl: String
        get() = sp.getString("serverUrl", "") ?: ""
        set(v) = sp.edit().putString("serverUrl", v).apply()

    var streamKey: String
        get() = sp.getString("streamKey", "") ?: ""
        set(v) = sp.edit().putString("streamKey", v).apply()

    var homeName: String
        get() = sp.getString("homeName", "MAYBÉLÉGER FC") ?: "MAYBÉLÉGER FC"
        set(v) = sp.edit().putString("homeName", v).apply()

    var awayName: String
        get() = sp.getString("awayName", "VISITEUR") ?: "VISITEUR"
        set(v) = sp.edit().putString("awayName", v).apply()

    var competition: String
        get() = sp.getString("competition", "Départemental 2") ?: ""
        set(v) = sp.edit().putString("competition", v).apply()

    /** Durée d'une mi-temps en minutes. */
    var halfLength: Int
        get() = sp.getInt("halfLength", 45)
        set(v) = sp.edit().putInt("halfLength", v).apply()

    var quality1080: Boolean
        get() = sp.getBoolean("quality1080", false)
        set(v) = sp.edit().putBoolean("quality1080", v).apply()

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

    var twitchAutoTitle: Boolean
        get() = sp.getBoolean("twitchAutoTitle", true)
        set(v) = sp.edit().putBoolean("twitchAutoTitle", v).apply()

    /** Code PIN de la télécommande, généré une fois puis conservé (évite de re-scanner à chaque match). */
    val pin: String
        get() = sp.getString("pin", null) ?: (1000..9999).random().toString().also {
            sp.edit().putString("pin", it).apply()
        }
}
