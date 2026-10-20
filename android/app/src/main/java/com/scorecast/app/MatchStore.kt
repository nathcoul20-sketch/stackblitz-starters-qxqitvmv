package com.scorecast.app

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Sauvegarde continue du match en cours (fichier privé), pour le reprendre si l'appli se ferme :
 * appel, batterie vide, fausse manipulation, ou Android qui ferme l'appli en arrière-plan.
 *
 * Le chrono est enregistré avec l'heure réelle du téléphone : à la reprise, le temps écoulé pendant
 * la coupure est ajouté (le match a continué sur le terrain).
 */
object MatchStore {

    /** Réglages du live à rouvrir avec le match. */
    data class Session(val clubId: String, val url: String, val backupUrl: String, val q1080: Boolean)

    data class Saved(val state: ScoreState, val session: Session, val savedAt: Long)

    /** Au-delà, une sauvegarde est considérée comme un vieux match oublié. */
    private const val MAX_AGE_MS = 12 * 3600_000L

    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null
    @Volatile private var session: Session? = null

    fun init(context: Context) {
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
        io.execute { f.delete() }
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
            Saved(
                state = stateFromJson(o.getJSONObject("state"), savedAt),
                session = Session(s.getString("clubId"), s.optString("url"), s.optString("backupUrl"), s.optBoolean("q1080")),
                savedAt = savedAt,
            )
        }.getOrNull()
    }

    private fun save(state: ScoreState) {
        val s = session ?: return
        val f = file ?: return
        val json = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("session", JSONObject().put("clubId", s.clubId).put("url", s.url).put("backupUrl", s.backupUrl).put("q1080", s.q1080))
            .put("state", stateToJson(state, SystemClock.elapsedRealtime()))
            .toString()
        io.execute {
            runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(json)
                tmp.renameTo(f)
            }
        }
    }

    // ---- Conversion ----

    private fun stateToJson(s: ScoreState, now: Long): JSONObject = JSONObject().apply {
        put("sport", s.sport.id)
        put("homeName", s.homeName); put("awayName", s.awayName); put("competition", s.competition)
        put("homeColor", s.homeColor); put("homeAccent", s.homeAccent); put("awayColor", s.awayColor)
        put("homeScore", s.homeScore); put("awayScore", s.awayScore)
        put("period", s.period); put("periodMinutes", s.periodMinutes)
        put("clockRunning", s.clockRunning); put("elapsedMs", s.elapsedMs(now))
        put("finished", s.finished)
        put("goals", JSONArray(s.goals.map {
            JSONObject().put("team", it.team.name).put("minute", it.minute).put("scorer", it.scorer)
        }))
        put("homeRed", s.homeRed); put("awayRed", s.awayRed)
        put("penalties", s.penalties); put("homePens", s.homePens); put("awayPens", s.awayPens)
        put("homeSets", s.homeSets); put("awaySets", s.awaySets); put("setNumber", s.setNumber)
        put("serving", s.serving?.name ?: "")
        put("setScores", JSONArray(s.setScores.map { JSONArray(listOf(it.first, it.second)) }))
        put("homeFouls", s.homeFouls); put("awayFouls", s.awayFouls)
        put("homeTimeouts", s.homeTimeouts); put("awayTimeouts", s.awayTimeouts)
        put("suspensions", JSONArray(s.suspensions.map {
            JSONObject().put("team", it.team.name).put("start", it.startGameMs)
        }))
        put("overlayVisible", s.overlayVisible); put("muted", s.muted)
        put("partnersVisible", s.partnersVisible)
        put("scene", s.scene.name)
    }

    private fun stateFromJson(o: JSONObject, savedAt: Long): ScoreState {
        val sport = Sport.fromId(o.getString("sport"))
        val periodMinutes = o.getInt("periodMinutes")
        val running = o.getBoolean("clockRunning")
        var elapsed = o.getLong("elapsedMs")
        if (running) elapsed += (System.currentTimeMillis() - savedAt).coerceAtLeast(0L)
        val base = ScoreState(sport = sport, period = o.getInt("period"), periodMinutes = periodMinutes)
        var stillRunning = running
        if (sport.autoStop && elapsed >= base.periodLengthMs) {
            elapsed = base.periodLengthMs
            stillRunning = false
        }
        val now = SystemClock.elapsedRealtime()
        fun team(name: String) = if (name == "AWAY") Team.AWAY else Team.HOME
        return base.copy(
            homeName = o.getString("homeName"), awayName = o.getString("awayName"),
            competition = o.optString("competition"),
            homeColor = o.getInt("homeColor"), homeAccent = o.getInt("homeAccent"), awayColor = o.getInt("awayColor"),
            homeScore = o.getInt("homeScore"), awayScore = o.getInt("awayScore"),
            clockRunning = stillRunning,
            clockBaseMs = elapsed,
            clockStartedAt = now,
            finished = o.optBoolean("finished"),
            goals = o.optJSONArray("goals")?.let { a ->
                (0 until a.length()).map { i ->
                    val g = a.getJSONObject(i)
                    Goal(team(g.getString("team")), g.optString("minute"), g.optString("scorer"), at = 0L)
                }
            } ?: emptyList(),
            homeRed = o.optInt("homeRed"), awayRed = o.optInt("awayRed"),
            penalties = o.optBoolean("penalties"), homePens = o.optInt("homePens"), awayPens = o.optInt("awayPens"),
            homeSets = o.optInt("homeSets"), awaySets = o.optInt("awaySets"), setNumber = o.optInt("setNumber", 1),
            serving = o.optString("serving").takeIf { it.isNotEmpty() }?.let { team(it) },
            setScores = o.optJSONArray("setScores")?.let { a ->
                (0 until a.length()).map { i -> a.getJSONArray(i).let { it.getInt(0) to it.getInt(1) } }
            } ?: emptyList(),
            homeFouls = o.optInt("homeFouls"), awayFouls = o.optInt("awayFouls"),
            homeTimeouts = o.optInt("homeTimeouts"), awayTimeouts = o.optInt("awayTimeouts"),
            suspensions = o.optJSONArray("suspensions")?.let { a ->
                (0 until a.length()).map { i ->
                    val x = a.getJSONObject(i)
                    Suspension(team(x.getString("team")), x.getLong("start"), at = 0L)
                }
            } ?: emptyList(),
            overlayVisible = o.optBoolean("overlayVisible", true),
            muted = o.optBoolean("muted"),
            partnersVisible = o.optBoolean("partnersVisible"),
            scene = runCatching { Scene.valueOf(o.getString("scene")) }.getOrDefault(Scene.MATCH),
        )
    }
}
