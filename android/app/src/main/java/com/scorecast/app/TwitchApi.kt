package com.scorecast.app

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Accès à l'API Twitch pour changer le titre du live.
 *
 * Connexion du compte : « Device Code Flow » (application publique, sans mot de passe dans l'appli).
 * L'utilisateur ouvre twitch.tv/activate, se connecte et autorise l'appli ; on récupère alors un jeton
 * avec le droit `channel:manage:broadcast`, conservé dans la fiche du club et renouvelé automatiquement.
 *
 * Toutes les méthodes font des appels réseau : à appeler hors du thread principal.
 */
class TwitchApi(private val club: Club, private val repo: ClubRepository) {

    /** Accès aux jetons du club (chaque modification est enregistrée). */
    private val prefs = object {
        var twitchClientId: String
            get() = club.twitchClientId
            set(v) { club.twitchClientId = v; repo.save(club) }
        var twitchAccessToken: String
            get() = club.twitchAccessToken
            set(v) { club.twitchAccessToken = v; repo.save(club) }
        var twitchRefreshToken: String
            get() = club.twitchRefreshToken
            set(v) { club.twitchRefreshToken = v; repo.save(club) }
        var twitchUserId: String
            get() = club.twitchUserId
            set(v) { club.twitchUserId = v; repo.save(club) }
        var twitchLogin: String
            get() = club.twitchLogin
            set(v) { club.twitchLogin = v; repo.save(club) }
    }

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    class TwitchException(message: String) : IOException(message)

    private val http = OkHttpClient()
    private val clientId get() = prefs.twitchClientId

    val isConnected: Boolean get() = prefs.twitchRefreshToken.isNotEmpty() && clientId.isNotEmpty()

    /** Étape 1 de la connexion : obtient le code à saisir sur twitch.tv/activate. */
    fun startDeviceLogin(): DeviceCode {
        val json = post(
            "https://id.twitch.tv/oauth2/device",
            FormBody.Builder().add("client_id", clientId).add("scopes", SCOPE).build(),
        )
        return DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            intervalSeconds = json.optInt("interval", 5),
            expiresInSeconds = json.optInt("expires_in", 1800),
        )
    }

    /**
     * Étape 2 : vérifie si l'utilisateur a validé. Renvoie true une fois connecté,
     * false si l'autorisation est encore en attente. Lève une exception si le code a expiré ou a été refusé.
     */
    fun pollDeviceLogin(code: DeviceCode): Boolean {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scopes", SCOPE)
            .add("device_code", code.deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val response = http.newCall(Request.Builder().url(TOKEN_URL).post(body).build()).execute()
        response.use {
            val json = JSONObject(it.body?.string().orEmpty().ifEmpty { "{}" })
            if (!it.isSuccessful) {
                if (json.optString("message") == "authorization_pending") return false
                throw TwitchException(json.optString("message", "Connexion refusée"))
            }
            saveTokens(json)
        }
        // On mémorise l'identifiant de la chaîne, nécessaire pour changer le titre
        val user = authorized { token -> get("https://api.twitch.tv/helix/users", token) }
            .getJSONArray("data").getJSONObject(0)
        prefs.twitchUserId = user.getString("id")
        prefs.twitchLogin = user.getString("login")
        return true
    }

    fun disconnect() {
        prefs.twitchAccessToken = ""
        prefs.twitchRefreshToken = ""
        prefs.twitchUserId = ""
        prefs.twitchLogin = ""
    }

    /** Change le titre de la chaîne (140 caractères max). */
    fun updateTitle(title: String) {
        val payload = JSONObject().put("title", title.take(140)).toString()
            .toRequestBody("application/json".toMediaType())
        authorized { token ->
            val request = Request.Builder()
                .url("https://api.twitch.tv/helix/channels?broadcaster_id=${prefs.twitchUserId}")
                .header("Authorization", "Bearer $token")
                .header("Client-Id", clientId)
                .patch(payload)
                .build()
            http.newCall(request).execute().use {
                if (it.code == 401) throw Unauthorized()
                if (!it.isSuccessful) throw TwitchException(errorMessage(it.body?.string(), it.code))
            }
            JSONObject()
        }
    }

    // ---- Interne ----

    private class Unauthorized : IOException()

    /** Exécute [block] avec le jeton ; s'il a expiré, le renouvelle une fois et recommence. */
    private fun authorized(block: (String) -> JSONObject): JSONObject = try {
        block(prefs.twitchAccessToken)
    } catch (e: Unauthorized) {
        refreshToken()
        try {
            block(prefs.twitchAccessToken)
        } catch (e: Unauthorized) {
            throw TwitchException("Autorisation Twitch expirée : reconnectez le compte")
        }
    }

    private fun refreshToken() {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", prefs.twitchRefreshToken)
            .build()
        val response = http.newCall(Request.Builder().url(TOKEN_URL).post(body).build()).execute()
        response.use {
            val text = it.body?.string()
            if (!it.isSuccessful) {
                disconnect()
                throw TwitchException("Autorisation Twitch expirée : reconnectez le compte")
            }
            saveTokens(JSONObject(text.orEmpty()))
        }
    }

    private fun saveTokens(json: JSONObject) {
        prefs.twitchAccessToken = json.getString("access_token")
        prefs.twitchRefreshToken = json.optString("refresh_token", prefs.twitchRefreshToken)
    }

    private fun get(url: String, token: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Client-Id", clientId)
            .build()
        http.newCall(request).execute().use {
            if (it.code == 401) throw Unauthorized()
            val text = it.body?.string()
            if (!it.isSuccessful) throw TwitchException(errorMessage(text, it.code))
            return JSONObject(text.orEmpty())
        }
    }

    private fun post(url: String, body: FormBody): JSONObject {
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use {
            val text = it.body?.string()
            if (!it.isSuccessful) throw TwitchException(errorMessage(text, it.code))
            return JSONObject(text.orEmpty())
        }
    }

    private fun errorMessage(body: String?, code: Int): String =
        runCatching { JSONObject(body.orEmpty()).optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "Erreur Twitch ($code)"

    companion object {
        private const val SCOPE = "channel:manage:broadcast"
        private const val TOKEN_URL = "https://id.twitch.tv/oauth2/token"

        /** Titre du live : « Compétition | DOMICILE vs EXTÉRIEUR ». */
        fun buildTitle(competition: String, home: String, away: String): String =
            listOf(competition.trim(), "${home.trim()} vs ${away.trim()}")
                .filter { it.isNotBlank() }
                .joinToString(" | ")
    }
}
