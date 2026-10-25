package com.volleylive.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Sauvegarde continue du match en cours (fichier privé), pour le reprendre si l'appli se ferme :
 * appel, batterie vide, fausse manipulation, ou Android qui ferme l'appli en arrière-plan.
 */
object MatchStore {

    /** Réglages du live à rouvrir avec le match. */
    data class Session(val url: String, val backupUrl: String)

    data class Saved(val state: ScoreState, val session: Session, val savedAt: Long)

    /** Au-delà, une sauvegarde est considérée comme un vieux match oublié. */
    private const val MAX_AGE_MS = 12 * 3600_000L

    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null
    @Volatile private var session: Session? = null

    private fun init(context: Context) {
        if (file == null) file = File(context.applicationContext.filesDir, "match_en_cours.json")
    }

    /** Démarre l'enregistrement automatique (à chaque action) pour ce live. */
    fun start(context: Context, s: Session) {
        init(context)
        session = s
        ScoreManager.onCommit = { state -> save(state) }
        save(ScoreManager.state)
    }

    /** Fin normale (l'utilisateur quitte le live) : plus rien à reprendre. */
    fun clear(context: Context) {
        init(context)
        session = null
        ScoreManager.onCommit = null
        val f = file ?: return
        // Après les sauvegardes déjà en file d'attente, et avant de revenir à l'accueil
        runCatching { io.submit { f.delete() }.get() }
    }

    fun load(context: Context): Saved? {
        init(context)
        val f = file ?: return null
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val savedAt = o.getLong("savedAt")
            if (System.currentTimeMillis() - savedAt > MAX_AGE_MS) return null
            val s = o.getJSONObject("session")
            Saved(stateFromJson(o.getJSONObject("state")), Session(s.optString("url"), s.optString("backupUrl")), savedAt)
        }.getOrNull()
    }

    private fun save(state: ScoreState) {
        val s = session ?: return
        val f = file ?: return
        val json = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("session", JSONObject().put("url", s.url).put("backupUrl", s.backupUrl))
            .put("state", state.toJson())
            .toString()
        io.execute {
            runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(json)
                tmp.renameTo(f)
            }
        }
    }

    private fun stateFromJson(o: JSONObject): ScoreState {
        fun team(v: String?) = when (v) {
            "home" -> Team.HOME
            "away" -> Team.AWAY
            else -> null
        }
        return ScoreState(
            homeName = o.getString("homeName"),
            awayName = o.getString("awayName"),
            competition = o.optString("competition"),
            homeScore = o.optInt("homeScore"),
            awayScore = o.optInt("awayScore"),
            homeSets = o.optInt("homeSets"),
            awaySets = o.optInt("awaySets"),
            setNumber = o.optInt("setNumber", 1),
            serving = team(if (o.isNull("serving")) null else o.optString("serving")),
            overlayVisible = o.optBoolean("overlayVisible", true),
            scene = runCatching { Scene.valueOf(o.getString("scene").uppercase()) }.getOrDefault(Scene.MATCH),
            audioMuted = o.optBoolean("audioMuted"),
            partnersVisible = o.optBoolean("partnersVisible"),
            setScores = o.optJSONArray("setScores")?.let { a ->
                (0 until a.length()).map { i -> a.getJSONArray(i).let { it.getInt(0) to it.getInt(1) } }
            } ?: emptyList(),
        )
    }
}
