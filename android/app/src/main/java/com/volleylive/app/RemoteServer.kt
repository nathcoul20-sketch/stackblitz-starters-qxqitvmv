package com.volleylive.app

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Serveur web embarqué dans le téléphone qui filme.
 * Le téléphone « télécommande » ouvre simplement http://<ip>:8080 dans son navigateur
 * (aucune application à installer, fonctionne aussi sur iPhone).
 *
 * Toutes les requêtes /api/... doivent fournir le code PIN affiché sur le téléphone qui filme.
 */
class RemoteServer(
    context: Context,
    port: Int,
    private val pin: String,
    /** Nombre de logos partenaires (la télécommande n'affiche le bouton que s'il y en a). */
    private val partnerCount: Int = 0,
) : NanoHTTPD(port) {

    private val rawPage: String =
        context.assets.open("remote.html").bufferedReader(Charsets.UTF_8).use { it.readText() }

    /**
     * Version de la page : si l'appli est mise à jour, une télécommande restée ouverte
     * avec l'ancienne page le détecte (voir /api/state) et se recharge toute seule.
     */
    private val pageVersion = Integer.toHexString(rawPage.hashCode())
    private val page = rawPage.replace("__PAGE_VERSION__", pageVersion)

    override fun serve(session: IHTTPSession): Response {
        val params = session.parameters.mapValues { it.value.firstOrNull() }
        return try {
            when (session.uri) {
                "/", "/index.html" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page)
                    .apply { addHeader("Cache-Control", "no-store") }
                "/api/state" -> authorized(params) { stateResponse() }
                "/api/action" -> authorized(params) {
                    if (session.method != Method.POST) return@authorized error(Response.Status.METHOD_NOT_ALLOWED, "POST requis")
                    val action = params["a"] ?: return@authorized error(Response.Status.BAD_REQUEST, "Action manquante")
                    if (!ScoreManager.apply(action)) return@authorized error(Response.Status.BAD_REQUEST, "Action refusée")
                    stateResponse()
                }
                "/api/config" -> authorized(params) {
                    if (session.method != Method.POST) return@authorized error(Response.Status.METHOD_NOT_ALLOWED, "POST requis")
                    ScoreManager.configure(params["home"], params["away"], params["competition"])
                    stateResponse()
                }
                else -> error(Response.Status.NOT_FOUND, "Introuvable")
            }
        } catch (e: Exception) {
            error(Response.Status.INTERNAL_ERROR, e.message ?: "Erreur")
        }
    }

    private inline fun authorized(params: Map<String, String?>, block: () -> Response): Response =
        if (params["pin"] == pin) block() else error(Response.Status.UNAUTHORIZED, "Code PIN incorrect")

    private fun stateResponse(): Response {
        val json = ScoreManager.state.toJson()
            .put("version", ScoreManager.version)
            .put("pageVersion", pageVersion)
            .put("partnerCount", partnerCount)
        return json(Response.Status.OK, json)
    }

    private fun error(status: Response.Status, message: String): Response =
        json(status, JSONObject().put("error", message))

    private fun json(status: Response.Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString()).apply {
            addHeader("Cache-Control", "no-store")
        }

    companion object {
        const val DEFAULT_PORT = 8080
    }
}
