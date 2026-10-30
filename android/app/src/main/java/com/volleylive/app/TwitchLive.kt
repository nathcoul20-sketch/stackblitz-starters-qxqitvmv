package com.volleylive.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Infos du live Twitch, suivies par le téléphone qui filme pendant que l'écran caméra est ouvert :
 *  - nombre de spectateurs (API Twitch, compte connecté dans l'appli), mis à jour toutes les 30 s ;
 *  - chat de la chaîne, lu en direct (connexion anonyme en lecture seule au chat Twitch),
 *    transmis à la télécommande.
 */
object TwitchLive {

    data class ChatMessage(
        val id: Long,
        val user: String,
        val name: String,
        val color: String,
        val text: String,
        val at: Long,
        val twitchId: String,
    )

    /** Spectateurs en ce moment ; -1 : inconnu (compte non connecté, ou chaîne hors ligne). */
    @Volatile var viewers: Int = -1
        private set
    /** Le nombre de spectateurs peut-il être récupéré (compte Twitch connecté) ? */
    @Volatile var viewersAvailable = false
        private set
    /** Chaîne dont on lit le chat (vide : aucune). */
    @Volatile var channel: String = ""
        private set
    /** État du chat : off, connecting, connected, error. */
    @Volatile var chatState: String = "off"
        private set

    private const val MAX_MESSAGES = 150
    private val lock = Any()
    private val messages = ArrayDeque<ChatMessage>()
    private var nextId = 1L

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var viewersTask: ScheduledFuture<*>? = null
    private val http by lazy { OkHttpClient.Builder().pingInterval(60, TimeUnit.SECONDS).build() }
    @Volatile private var socket: WebSocket? = null
    @Volatile private var active = false

    /** Démarre le suivi (à l'ouverture de l'écran caméra). */
    fun start(prefs: Prefs) {
        stop()
        active = true
        val api = TwitchApi(prefs)
        viewersAvailable = api.isConnected && prefs.twitchUserId.isNotEmpty()
        channel = (if (api.isConnected) prefs.twitchLogin else prefs.twitchChannel)
            .trim().removePrefix("#").lowercase()
        if (viewersAvailable) {
            viewersTask = scheduler.scheduleWithFixedDelay({ pollViewers(api) }, 0, 30, TimeUnit.SECONDS)
        }
        if (channel.isNotEmpty()) scheduler.execute { connectChat() }
    }

    /** Arrête le suivi (fermeture de l'écran caméra). */
    fun stop() {
        active = false
        viewersTask?.cancel(false)
        viewersTask = null
        socket?.close(1000, null)
        socket = null
        viewers = -1
        chatState = "off"
    }

    /** Messages arrivés après [sinceId] (les 60 derniers au premier appel). */
    fun messagesSince(sinceId: Long): List<ChatMessage> = synchronized(lock) {
        val list = messages.filter { it.id > sinceId }
        if (sinceId <= 0) list.takeLast(60) else list
    }

    /** Réponse de /api/chat pour la télécommande. */
    fun chatJson(sinceId: Long): JSONObject {
        val list = messagesSince(sinceId)
        return statusJson().put("messages", JSONArray(list.map {
            JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)
                .put("text", it.text).put("at", it.at)
        })).put("removed", JSONArray(synchronized(lock) { removedIds.toList() }))
    }

    fun statusJson(): JSONObject = JSONObject()
        .put("viewers", viewers)
        .put("viewersAvailable", viewersAvailable)
        .put("channel", channel)
        .put("chat", chatState)

    // ---- Spectateurs ----

    private fun pollViewers(api: TwitchApi) {
        if (!active) return
        viewers = runCatching { api.viewerCount() ?: -1 }.getOrDefault(-1)
    }

    // ---- Chat (lecture seule, sans compte : pseudo anonyme « justinfan ») ----

    /** Messages supprimés par la modération (retirés aussi de la télécommande). */
    private val removedIds = ArrayDeque<Long>()

    private fun connectChat() {
        if (!active || channel.isEmpty()) return
        chatState = "connecting"
        val request = Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                webSocket.send("PASS SCHMOOPIIE")
                webSocket.send("NICK justinfan${(10_000..99_999).random()}")
                webSocket.send("JOIN #$channel")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                text.split("\r\n").filter { it.isNotBlank() }.forEach { handleLine(webSocket, it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== socket) return
                chatState = "error"
                retry()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === socket) retry()
            }
        })
    }

    private fun retry() {
        if (!active) return
        scheduler.schedule({ if (active) connectChat() }, 5, TimeUnit.SECONDS)
    }

    private fun handleLine(ws: WebSocket, raw: String) {
        if (raw.startsWith("PING")) {
            ws.send("PONG" + raw.removePrefix("PING"))
            return
        }
        var line = raw
        val tags = mutableMapOf<String, String>()
        if (line.startsWith("@")) {
            val space = line.indexOf(' ')
            if (space < 0) return
            line.substring(1, space).split(';').forEach { kv ->
                val eq = kv.indexOf('=')
                if (eq > 0) tags[kv.substring(0, eq)] = unescapeTag(kv.substring(eq + 1))
            }
            line = line.substring(space + 1)
        }
        // line : « :prefixe COMMANDE paramètres »
        val parts = line.split(' ', limit = 4)
        val command = if (line.startsWith(":")) parts.getOrNull(1) else parts.getOrNull(0)
        when (command) {
            "ROOMSTATE", "366" -> chatState = "connected"
            "RECONNECT" -> ws.close(1000, null)
            "PRIVMSG" -> {
                val user = line.substringAfter(':').substringBefore('!')
                var text = line.substringAfter(" PRIVMSG ").substringAfter(" :", "")
                if (text.startsWith("\u0001ACTION ")) text = text.removePrefix("\u0001ACTION ").removeSuffix("\u0001")
                if (text.isBlank()) return
                addMessage(
                    user = user,
                    name = tags["display-name"].orEmpty().ifBlank { user },
                    color = tags["color"].orEmpty(),
                    text = text,
                    twitchId = tags["id"].orEmpty(),
                )
                chatState = "connected"
            }
            // Modération : message supprimé, ou messages d'un utilisateur effacés
            "CLEARMSG" -> tags["target-msg-id"]?.let { target -> remove { it.twitchId == target } }
            "CLEARCHAT" -> {
                val target = line.substringAfter(" CLEARCHAT ").substringAfter(" :", "")
                if (target.isEmpty()) remove { true } else remove { it.user.equals(target, ignoreCase = true) }
            }
        }
    }

    private fun addMessage(user: String, name: String, color: String, text: String, twitchId: String) =
        synchronized(lock) {
            messages.addLast(ChatMessage(nextId++, user, name, color, text.take(500), System.currentTimeMillis(), twitchId))
            while (messages.size > MAX_MESSAGES) messages.removeFirst()
        }

    private fun remove(predicate: (ChatMessage) -> Boolean) = synchronized(lock) {
        val gone = messages.filter(predicate)
        messages.removeAll(gone.toSet())
        gone.forEach { removedIds.addLast(it.id) }
        while (removedIds.size > 200) removedIds.removeFirst()
    }

    private fun unescapeTag(v: String) = v
        .replace("\\s", " ").replace("\\:", ";").replace("\\r", "").replace("\\n", "").replace("\\\\", "\\")
}
