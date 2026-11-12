package app.bosunarr.feature.tautulli

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Tautulli API v2: GET /api/v2?apikey=…&cmd=… (the key is added as a query parameter by the client).
 * Every answer is {"response": {"result": "success"|"error", "message": …, "data": …}}; a bad key is HTTP 200
 * with result "error" ("Invalid apikey"), turned into an exception by [tautulliError]. Numbers often arrive as
 * strings ("stream_count": "2"), so everything is read leniently.
 *   get_tautulli_info    {tautulli_version}
 *   get_server_info      {pms_name, pms_version, pms_platform, pms_plexpass}
 *   get_plays_by_date    {categories: ["2026-11-01", …], series: [{name, data: [n, …]}]}  (y_axis plays | duration s)
 *   get_users_table      {data: [{friendly_name, last_seen (epoch s)}]}
 *   get_home_stats       [{stat_id: top_movies|top_tv|top_users, rows: [{title|friendly_name, total_plays, total_duration}]}]
 *   get_activity         {stream_count, stream_count_transcode, total_bandwidth, wan_bandwidth, sessions: […]}
 *   get_history          {data: [{date, friendly_name, full_title, grandparent_title, title, …}]}
 *   arnold               "a quote"
 */

/* ================================================================ lenient JSON helpers */

private fun JsonElement?.ts(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
private fun JsonElement?.tn(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()
private fun JsonElement?.tobj(): JsonObject? = this as? JsonObject
private fun JsonElement?.ta(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private val RESULT_ERROR = Regex("\"result\"\\s*:\\s*\"error\"")

/**
 * The error in a Tautulli answer that came back as HTTP 200, or null when it succeeded. Only short answers are
 * parsed: errors are tiny, and a large history payload shouldn't be decoded twice.
 */
fun tautulliError(name: String, text: String): ServiceException? {
    val head = text.trimStart().take(300)
    if (!head.startsWith("{") || !RESULT_ERROR.containsMatchIn(head)) return null
    val msg = runCatching {
        AppJson.parseToJsonElement(text).tobj()?.get("response").tobj()?.get("message").ts()
    }.getOrNull()
    return when {
        msg == null -> ServiceException("$name returned an error", 400)
        msg.contains("apikey", ignoreCase = true) || msg.contains("api key", ignoreCase = true) -> ServiceException("$name rejected the credentials", 401)
        else -> ServiceException("$name: ${msg.take(160)}", 400)
    }
}

/** `response.data` of an answer, or a user-facing error. */
internal fun tautulliData(el: JsonElement, name: String): JsonElement {
    val r = el.tobj()?.get("response").tobj() ?: throw ServiceException("$name gave an unexpected answer")
    if (r["result"].ts() == "error") throw tautulliError(name, el.toString()) ?: ServiceException("$name returned an error", 400)
    return r["data"] ?: throw ServiceException("$name gave an unexpected answer")
}

private suspend fun cmd(s: ServiceConfig, cmd: String, params: String = ""): JsonElement =
    tautulliData(app.client.json(s, "/api/v2?cmd=$cmd" + if (params.isNotEmpty()) "&$params" else ""), s.name)

/* ================================================================ server */

data class TautulliInfo(val version: String?, val pmsName: String?, val pmsVersion: String?, val pmsPlatform: String?, val plexPass: Boolean?)

internal fun parseTautulliInfo(tautulli: JsonElement?, server: JsonElement?): TautulliInfo {
    val t = tautulli.tobj()
    val p = server.tobj()
    return TautulliInfo(
        version = t?.get("tautulli_version").ts()?.removePrefix("v"),
        pmsName = p?.get("pms_name").ts(),
        pmsVersion = p?.get("pms_version").ts()?.substringBefore('-'),
        pmsPlatform = p?.get("pms_platform").ts(),
        plexPass = p?.get("pms_plexpass").ts()?.let { it == "1" || it.equals("true", true) },
    )
}

/* ================================================================ overview */

data class TtDay(val date: LocalDate, val plays: Int, val seconds: Double)

data class TtPeriod(val days: Int, val plays: Int, val seconds: Double, val activeUsers: Int)

data class TtOverview(val periods: Map<Int, TtPeriod>, val daily: List<TtDay>)

val TT_PERIODS = listOf(7, 30, 90)

/** Sum every series (TV, Movies, Music, Live TV) per day. */
internal fun parsePlaysByDate(data: JsonElement): Map<LocalDate, Double> {
    val o = data.tobj() ?: return emptyMap()
    val days = (o["categories"] as? JsonArray)?.map { it.ts() } ?: return emptyMap()
    val out = sortedMapOf<LocalDate, Double>()
    days.forEachIndexed { i, key ->
        val date = key?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return@forEachIndexed
        out[date] = o["series"].ta().sumOf { s -> ((s["data"] as? JsonArray)?.getOrNull(i)).tn() ?: 0.0 }
    }
    return out
}

/** Users' last activity (epoch seconds), from get_users_table. */
internal fun parseLastSeen(data: JsonElement): List<Instant> =
    data.tobj()?.get("data").ta().mapNotNull { u -> u["last_seen"].tn()?.toLong()?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) } }

internal fun buildOverview(plays: Map<LocalDate, Double>, seconds: Map<LocalDate, Double>, lastSeen: List<Instant>, zone: ZoneId = ZoneId.systemDefault()): TtOverview {
    val dates = (plays.keys + seconds.keys).toSortedSet().toList()
    val daily = dates.map { TtDay(it, (plays[it] ?: 0.0).toInt(), seconds[it] ?: 0.0) }
    val periods = TT_PERIODS.associateWith { days ->
        // Every number covers the same span: the last `days` calendar days, today included.
        val slice = daily.takeLast(days)
        val since = (slice.firstOrNull()?.date ?: LocalDate.now(zone)).atStartOfDay(zone).toInstant()
        TtPeriod(days, slice.sumOf { it.plays }, slice.sumOf { it.seconds }, lastSeen.count { it >= since })
    }
    return TtOverview(periods, daily)
}

internal suspend fun loadTtOverview(s: ServiceConfig): TtOverview = coroutineScope {
    val plays = async { parsePlaysByDate(cmd(s, "get_plays_by_date", "time_range=90&y_axis=plays")) }
    val duration = async { runCatching { parsePlaysByDate(cmd(s, "get_plays_by_date", "time_range=90&y_axis=duration")) }.getOrDefault(emptyMap()) }
    val users = async { runCatching { parseLastSeen(cmd(s, "get_users_table", "length=500&order_column=last_seen&order_dir=desc")) }.getOrDefault(emptyList()) }
    buildOverview(plays.await(), duration.await(), users.await())
}

/* ================================================================ top lists */

data class TtTopItem(val name: String, val plays: Int, val seconds: Double?)

data class TtTop(val movies: List<TtTopItem>, val shows: List<TtTopItem>, val users: List<TtTopItem>)

internal fun parseHomeStats(data: JsonElement): TtTop {
    val stats = data.ta().associateBy { it["stat_id"].ts() }
    fun rows(id: String, name: (JsonObject) -> String?) = stats[id]?.get("rows").ta().map {
        TtTopItem(name(it) ?: "Unknown", it["total_plays"].tn()?.toInt() ?: 0, it["total_duration"].tn())
    }.take(5)
    return TtTop(
        movies = rows("top_movies") { it["title"].ts() },
        shows = rows("top_tv") { it["grandparent_title"].ts() ?: it["title"].ts() },
        users = rows("top_users") { it["friendly_name"].ts() ?: it["user"].ts() },
    )
}

internal suspend fun loadTtTop(s: ServiceConfig, days: Int): TtTop =
    parseHomeStats(cmd(s, "get_home_stats", "time_range=$days&stats_type=plays&stats_count=5"))

/* ================================================================ activity + history */

data class TtSession(
    val key: String,
    val user: String,
    val title: String,
    val subtitle: String?,
    val device: String,
    val progress: Float,
    val state: String,
    /** "Direct play", "Direct stream", "Transcode". */
    val decision: String,
    val hw: Boolean,
    val quality: String?,
)

data class TtActivity(val streams: Int, val transcodes: Int, val bandwidthKbps: Int, val wanKbps: Int, val sessions: List<TtSession>)

private fun epCode(season: JsonElement?, episode: JsonElement?): String? {
    val s = season.tn()?.toInt() ?: return null
    val e = episode.tn()?.toInt() ?: return null
    return "S%02dE%02d".format(s, e)
}

private fun decisionLabel(d: String?): String = when (d?.lowercase()) {
    "transcode" -> "Transcode"
    "copy" -> "Direct stream"
    else -> "Direct play"
}

/** Show (or movie/track) title and the episode/album line for a session or history row. */
private fun titles(r: JsonObject): Pair<String, String?> = when (r["media_type"].ts()) {
    "episode" -> (r["grandparent_title"].ts() ?: r["title"].ts() ?: "Unknown") to
        listOfNotNull(epCode(r["parent_media_index"], r["media_index"]), r["title"].ts()).joinToString(" · ").ifBlank { null }
    "track" -> (r["title"].ts() ?: "Unknown") to listOfNotNull(r["grandparent_title"].ts(), r["parent_title"].ts()).joinToString(" · ").ifBlank { null }
    else -> (r["title"].ts() ?: r["full_title"].ts() ?: "Unknown") to r["year"].ts()
}

internal fun parseActivity(data: JsonElement): TtActivity {
    val o = data.tobj() ?: throw ServiceException("Tautulli gave an unexpected answer")
    val sessions = o["sessions"].ta().mapIndexed { i, x ->
        val (title, subtitle) = titles(x)
        val decision = decisionLabel(x["transcode_decision"].ts())
        TtSession(
            key = x["session_key"].ts() ?: x["session_id"].ts() ?: "$i",
            user = x["friendly_name"].ts() ?: x["user"].ts() ?: "Someone",
            title = title,
            subtitle = subtitle,
            device = listOfNotNull(x["product"].ts(), x["player"].ts()).distinct().joinToString(" · "),
            progress = ((x["progress_percent"].tn() ?: 0.0) / 100).toFloat().coerceIn(0f, 1f),
            state = x["state"].ts() ?: "playing",
            decision = decision,
            hw = decision == "Transcode" && (x["transcode_hw_decoding"].tn() == 1.0 || x["transcode_hw_encoding"].tn() == 1.0),
            quality = x["quality_profile"].ts()?.takeIf { !it.equals("Original", true) },
        )
    }
    return TtActivity(
        streams = o["stream_count"].tn()?.toInt() ?: sessions.size,
        transcodes = o["stream_count_transcode"].tn()?.toInt() ?: sessions.count { it.decision == "Transcode" },
        bandwidthKbps = o["total_bandwidth"].tn()?.toInt() ?: 0,
        wanKbps = o["wan_bandwidth"].tn()?.toInt() ?: 0,
        sessions = sessions,
    )
}

internal suspend fun loadTtActivity(s: ServiceConfig): TtActivity = parseActivity(cmd(s, "get_activity"))

data class TtWatched(val key: String, val user: String, val title: String, val subtitle: String?, val device: String, val seconds: Double, val at: Instant?, val percent: Int?)

internal fun parseHistory(data: JsonElement): List<TtWatched> =
    data.tobj()?.get("data").ta().mapIndexed { i, r ->
        val (title, subtitle) = titles(r)
        TtWatched(
            key = "${r["row_id"].ts() ?: r["id"].ts() ?: r["reference_id"].ts()}:$i",
            user = r["friendly_name"].ts() ?: r["user"].ts() ?: "Unknown",
            title = title,
            subtitle = subtitle,
            device = listOfNotNull(r["product"].ts(), r["player"].ts()).distinct().joinToString(" · "),
            // `play_duration` in newer versions; `duration` (already excluding pauses) before.
            seconds = r["play_duration"].tn() ?: r["duration"].tn() ?: 0.0,
            at = (r["date"].tn() ?: r["started"].tn())?.toLong()?.let { Instant.ofEpochSecond(it) },
            percent = r["percent_complete"].tn()?.toInt(),
        )
    }

internal suspend fun loadTtHistory(s: ServiceConfig): List<TtWatched> = parseHistory(cmd(s, "get_history", "length=25"))

internal suspend fun loadTtInfo(s: ServiceConfig): TautulliInfo = coroutineScope {
    val t = async { cmd(s, "get_tautulli_info") }
    val p = async { runCatching { cmd(s, "get_server_info") }.getOrNull() }
    parseTautulliInfo(t.await(), p.await())
}

/** Arnold's wisdom (Tautulli's easter egg). */
internal suspend fun arnold(s: ServiceConfig): String = cmd(s, "arnold").ts() ?: "I'll be back."
