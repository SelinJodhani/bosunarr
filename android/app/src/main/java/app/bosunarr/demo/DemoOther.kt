package app.bosunarr.demo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** Small bits of mutable state for Prowlarr, Bazarr and Wizarr. */
internal class DemoMiscState(@Suppress("unused") private val w: World) {
    /** Scratch space for handlers that keep a list per world (arr commands). */
    val bag = mutableMapOf<String, Any>()

    /** Bazarr: subtitles found during the demo (episode/movie key → languages no longer missing). */
    val subtitlesFound = mutableSetOf<String>()
    val bazarrHistory = mutableListOf<JsonObject>()
    var providersReset = false

    /** Wizarr invitations (loaded from the fixture, then created / deleted in the demo). */
    var invitations: MutableList<JsonObject>? = null
    var nextInvite = 8
}

/* ================================================================ Prowlarr */

internal object DemoProwlarr {
    private val CATS = mapOf(2000 to "Movies", 2040 to "Movies/HD", 2045 to "Movies/UHD", 5000 to "TV", 5040 to "TV/HD", 4000 to "PC", 4050 to "PC/ISO")

    fun handle(w: World, r: DemoRequest): DemoResponse? = when (r.path) {
        "/api/v1/system/status" -> DemoResponse.json(DemoAssets.json("prowlarr/system-status.json"))
        "/api/v1/health" -> DemoResponse.json(DemoAssets.json("prowlarr/health.json"))
        "/api/v1/indexer" -> DemoResponse.json(DemoAssets.json("prowlarr/indexer.json"))
        "/api/v1/indexerstatus" -> DemoResponse.json(DemoAssets.json("prowlarr/indexerstatus.json"))
        "/api/v1/indexerstats" -> DemoResponse.json(DemoAssets.json("prowlarr/indexerstats.json"))
        "/api/v1/indexer/testall" -> DemoResponse.json(arr((1..5).map { id ->
            if (id == 5) obj("id" to id, "isValid" to false, "validationFailures" to listOf(obj("propertyName" to "", "errorMessage" to "Unable to connect to indexer: connection timed out", "severity" to "error")))
            else obj("id" to id, "isValid" to true, "validationFailures" to emptyList<Any>())
        }), 400)
        "/api/v1/search" -> if (r.method == "POST") grab(w, r) else search(w, r)
        else -> null
    }

    private fun search(w: World, r: DemoRequest): DemoResponse {
        val q = r.q("query").orEmpty().trim()
        if (q.isEmpty()) return DemoResponse.json(JsonArray(emptyList()))
        val ql = q.lowercase()
        val cats = r.qAll("categories").mapNotNull { it.toIntOrNull() }
        val hits = mutableListOf<Triple<String, Long, Int>>() // release, size, category
        for (m in w.cat.movies.filter { it.title.lowercase().contains(ql) || ql.contains(it.title.lowercase()) }) {
            val base = "${dotted(m.title)}.${m.year}"
            val minutes = m.runtime.coerceAtLeast(5)
            hits += Triple("$base.1080p.BluRay.FLAC2.0.x264-ARCHiVE", minutes * 60L * 1024 * 1024, 2040)
            hits += Triple("$base.720p.WEB-DL.AAC2.0.H.264-OPENFILM", minutes * 26L * 1024 * 1024, 2040)
            hits += Triple("$base.2160p.Remastered.WEB-DL.DDP5.1.HDR.H.265-RESTORED", minutes * 160L * 1024 * 1024, 2045)
            hits += Triple("$base.480p.DVDRip.XviD-PDCLASSIC", minutes * 9L * 1024 * 1024, 2000)
        }
        for (s in w.cat.series.filter { it.title.lowercase().contains(ql) }) {
            val base = "${dotted(s.title)}.${s.year}"
            hits += Triple("$base.S01.Complete.720p.WEB-DL.AAC2.0.H.264-ARCHiVE", s.episodeSize * s.episodes.size, 5040)
            s.episodes.takeLast(4).forEach { e -> hits += Triple("$base.S01E%02d.${dotted(e.title)}.720p.WEB-DL.AAC2.0.H.264-ARCHiVE".format(e.n), s.episodeSize, 5040) }
        }
        if (ql.contains("debian") || ql.contains("linux") || ql.contains("ubuntu")) {
            hits += Triple("debian-13.2.0-amd64-netinst.iso", 784L * 1024 * 1024, 4050)
            hits += Triple("debian-live-13.2.0-amd64-kde.iso", 3_400L * 1024 * 1024, 4050)
            hits += Triple("ubuntu-24.04.3-desktop-amd64.iso", 6_100L * 1024 * 1024, 4050)
        }
        if (hits.isEmpty()) hits += Triple("${dotted(q)}.Public.Domain.Collection.720p.WEB-DL-ARCHiVE", 2_300L * 1024 * 1024, 2000)
        val indexers = listOf(1 to "Internet Archive", 2 to "Public Domain Torrents", 3 to "Academic Torrents")
        val now = Instant.now()
        @Suppress("UNCHECKED_CAST")
        val names = w.misc.bag.getOrPut("prowlarr:guids") { mutableMapOf<String, String>() } as MutableMap<String, String>
        hits.forEach { (name, _, _) -> names["demo-${infoHash(name).take(16)}"] = name }
        val out = hits.filter { (_, _, cat) -> cats.isEmpty() || cats.any { c -> cat / 1000 == c / 1000 } }.mapIndexed { i, (name, size, cat) ->
            val (iid, iname) = if (cat == 4050) 4 to "Linux Tracker" else indexers[(rand(name) * indexers.size).toInt().coerceIn(0, 2)]
            val ageH = 2 + rand("age$name") * 24 * 400
            obj(
                "guid" to "demo-${infoHash(name).take(16)}",
                "indexerId" to iid, "indexer" to iname, "title" to name, "sortTitle" to name.lowercase(), "size" to size,
                "publishDate" to now.minusSeconds((ageH * 3600).toLong()), "ageHours" to ageH, "age" to (ageH / 24).toInt(),
                "seeders" to (2 + rand("s$name") * 140).toInt(), "leechers" to (rand("l$name") * 18).toInt(),
                "grabs" to (rand("g$name") * 900).toInt(), "protocol" to "torrent",
                "categories" to listOf(obj("id" to cat, "name" to CATS[cat])),
                "infoUrl" to "", "downloadAllowed" to true, "indexerFlags" to emptyList<String>(), "fileName" to "$name.torrent", "sortOrder" to i,
            )
        }
        return DemoResponse.json(arr(out))
    }

    private fun grab(w: World, r: DemoRequest): DemoResponse {
        val guid = (r.json as? JsonObject)?.get("guid").str().orEmpty()
        // The request only carries the guid: look the title up from the search that produced it.
        @Suppress("UNCHECKED_CAST")
        val names = w.misc.bag["prowlarr:guids"] as? Map<String, String>
        val name = names?.get(guid) ?: "Grabbed.Release.${guid.takeLast(6)}"
        val category = when { name.endsWith(".iso") -> ""; Regex("S\\d\\d", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "tv-sonarr"; else -> "radarr" }
        w.torrents.add(name, ((1.2 + rand(name) * 6) * 1024 * 1024 * 1024).toLong(), category)
        return DemoResponse.json(obj("guid" to guid, "approved" to true))
    }
}

/* ================================================================ Bazarr */

internal object DemoBazarr {
    private fun lang(code: String) = when (code) {
        "fr" -> obj("name" to "French", "code2" to "fr", "code3" to "fre", "forced" to false, "hi" to false)
        "de" -> obj("name" to "German", "code2" to "de", "code3" to "ger", "forced" to false, "hi" to false)
        "es" -> obj("name" to "Spanish", "code2" to "es", "code3" to "spa", "forced" to false, "hi" to false)
        "en-hi" -> obj("name" to "English", "code2" to "en", "code3" to "eng", "forced" to false, "hi" to true)
        else -> obj("name" to "English", "code2" to "en", "code3" to "eng", "forced" to false, "hi" to false)
    }

    /** (episode, missing languages) for the live series' recent episodes. */
    private fun wantedEpisodes(w: World): List<Pair<Pair<LibSeries, LibEpisode>, List<String>>> {
        val s = w.series.firstOrNull { it.s.live } ?: return emptyList()
        return s.episodes.filter { it.hasFile }.takeLast(4).mapIndexed { i, e ->
            (s to e) to (if (i % 2 == 0) listOf("fr", "de") else listOf("fr"))
        }.map { (k, langs) -> k to langs.filter { "e:${k.second.id}:$it" !in w.misc.subtitlesFound } }.filter { it.second.isNotEmpty() }
    }

    private fun wantedMovies(w: World): List<Pair<LibMovie, List<String>>> = listOf(
        "metropolis" to listOf("en"), "nosferatu" to listOf("en", "es"), "charade" to listOf("fr"), "his-girl-friday" to listOf("en-hi"), "sintel" to listOf("de"),
    ).mapNotNull { (k, l) -> w.movieByKey(k)?.takeIf { it.hasFile }?.let { it to l.filter { c -> "m:${it.id}:${c.take(2)}" !in w.misc.subtitlesFound } } }
        .filter { it.second.isNotEmpty() }

    private fun seededHistory(w: World, episodes: Boolean): List<JsonObject> {
        val now = Instant.now()
        val providers = listOf("opensubtitlescom", "podnapisi", "embeddedsubtitles")
        return if (episodes) {
            val s = w.series.firstOrNull { it.s.live } ?: return emptyList()
            s.episodes.filter { it.hasFile }.takeLast(10).reversed().mapIndexed { i, e ->
                obj("seriesTitle" to s.s.title, "episode_number" to "1x${e.n}", "episodeTitle" to e.title, "action" to if (i == 3) 3 else 1,
                    "language" to lang(if (i % 3 == 0) "en" else "es"), "provider" to providers[i % 3], "score" to "${88 + (rand("bs$i") * 11).toInt()}%",
                    "timestamp" to (e.fileAdded ?: now).plusSeconds(900).toString(), "sonarrSeriesId" to s.id, "sonarrEpisodeId" to e.id)
            }
        } else {
            w.movies.filter { it.hasFile }.sortedByDescending { it.fileAdded }.take(10).mapIndexed { i, lm ->
                obj("title" to lm.m.title, "action" to if (i == 5) 0 else if (i == 2) 3 else 1, "language" to lang(if (i % 2 == 0) "en" else "fr"),
                    "provider" to providers[(i + 1) % 3], "score" to "${84 + (rand("bm$i") * 15).toInt()}%",
                    "timestamp" to (lm.fileAdded ?: now).plusSeconds(1_200).toString(), "radarrId" to lm.id)
            }
        }
    }

    fun handle(w: World, r: DemoRequest): DemoResponse? {
        val p = r.path
        return when {
            p == "/api/system/status" -> DemoResponse.json(DemoAssets.json("bazarr/system-status.json"))
            p == "/api/system/health" -> DemoResponse.json(if (w.misc.providersReset) obj("data" to emptyList<Any>()) else DemoAssets.json("bazarr/system-health.json"))
            p == "/api/providers" && r.method == "POST" -> { w.misc.providersReset = true; DemoResponse.empty(204) }
            p == "/api/providers" -> {
                val o = DemoAssets.json("bazarr/providers.json") as JsonObject
                if (!w.misc.providersReset) DemoResponse.json(o)
                else DemoResponse.json(obj("data" to (o["data"] as JsonArray).map { e -> JsonObject((e as JsonObject) + mapOf("status" to j("Good"), "retry" to j("-"))) }))
            }
            p == "/api/badges" -> DemoResponse.json(obj(
                "episodes" to wantedEpisodes(w).size, "movies" to wantedMovies(w).size, "providers" to if (w.misc.providersReset) 0 else 1,
                "status" to 0, "sonarr_signalr" to "LIVE", "radarr_signalr" to "LIVE", "announcements" to 0,
            ))
            p == "/api/episodes/wanted" -> {
                val list = wantedEpisodes(w)
                DemoResponse.json(obj("data" to list.map { (k, langs) ->
                    val (s, e) = k
                    obj("seriesTitle" to s.s.title, "episode_number" to "1x${e.n}", "episodeTitle" to e.title, "missing_subtitles" to langs.map(::lang),
                        "sonarrSeriesId" to s.id, "sonarrEpisodeId" to e.id, "sceneName" to releaseName(s.s, e), "tags" to emptyList<String>(), "seriesType" to "standard")
                }, "total" to list.size))
            }
            p == "/api/movies/wanted" -> {
                val list = wantedMovies(w)
                DemoResponse.json(obj("data" to list.map { (lm, langs) ->
                    obj("title" to lm.m.title, "missing_subtitles" to langs.map(::lang), "radarrId" to lm.id, "sceneName" to releaseName(lm.m), "tags" to emptyList<String>())
                }, "total" to list.size))
            }
            p == "/api/episodes/history" || p == "/api/movies/history" -> {
                val episodes = p.startsWith("/api/episodes")
                val added = w.misc.bazarrHistory.filter { (it["episode"].bool() == true) == episodes }
                val list = added.reversed() + seededHistory(w, episodes)
                DemoResponse.json(obj("data" to list, "total" to list.size + 31))
            }
            (p == "/api/episodes/subtitles" || p == "/api/movies/subtitles") && r.method == "PATCH" -> {
                val episodes = p.startsWith("/api/episodes")
                val code = r.q("language") ?: "en"
                val hi = r.q("hi") == "true"
                val entry: JsonObject = if (episodes) {
                    val e = w.episode(r.q("episodeid")?.toIntOrNull() ?: -1) ?: return DemoResponse.notFound()
                    val s = w.seriesById(e.seriesId)!!
                    w.misc.subtitlesFound += "e:${e.id}:$code"
                    obj("episode" to true, "seriesTitle" to s.s.title, "episode_number" to "1x${e.n}", "episodeTitle" to e.title, "action" to 1,
                        "language" to lang(if (hi) "en-hi" else code), "provider" to "opensubtitlescom", "score" to "96%", "timestamp" to Instant.now().toString())
                } else {
                    val lm = w.movie(r.q("radarrid")?.toIntOrNull() ?: -1) ?: return DemoResponse.notFound()
                    w.misc.subtitlesFound += "m:${lm.id}:$code"
                    obj("episode" to false, "title" to lm.m.title, "action" to 1, "language" to lang(if (hi) "en-hi" else code),
                        "provider" to "podnapisi", "score" to "93%", "timestamp" to Instant.now().toString())
                }
                w.misc.bazarrHistory += entry
                DemoResponse.empty(204)
            }
            p == "/api/system/tasks" && r.method == "POST" -> DemoResponse.empty(204)
            p == "/api/system/tasks" -> DemoResponse.json(obj("data" to emptyList<Any>()))
            else -> null
        }
    }
}

/* ================================================================ Wizarr */

internal object DemoWizarr {
    private fun invites(w: World): MutableList<JsonObject> = w.misc.invitations ?: (DemoAssets.json("wizarr/invitations.json") as JsonObject)
        .let { o -> (o["invitations"] as JsonArray).map { it as JsonObject }.toMutableList() }.also { w.misc.invitations = it }

    private val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun handle(w: World, r: DemoRequest): DemoResponse? {
        val p = r.path
        return when {
            p == "/api/status" -> {
                val inv = invites(w)
                val users = ((DemoAssets.json("wizarr/users.json") as JsonObject)["users"] as JsonArray).size
                DemoResponse.json(obj("users" to users, "invites" to inv.size, "pending" to inv.count { it["status"].str() == "pending" }, "expired" to inv.count { it["status"].str() == "expired" }))
            }
            p == "/api/users" -> DemoResponse.json(DemoAssets.json("wizarr/users.json"))
            p == "/api/libraries" -> DemoResponse.json(DemoAssets.json("wizarr/libraries.json"))
            p == "/api/servers" -> DemoResponse.json(DemoAssets.json("wizarr/servers.json"))
            p == "/api/invitations" && r.method == "POST" -> create(w, r)
            p == "/api/invitations" -> DemoResponse.json(obj("invitations" to invites(w), "count" to invites(w).size))
            p.startsWith("/api/invitations/") && r.method == "DELETE" -> {
                val id = p.substringAfterLast('/').toIntOrNull()
                val removed = invites(w).removeAll { it["id"].int() == id }
                if (removed) DemoResponse.json(obj("message" to "Invitation deleted")) else DemoResponse.notFound("Invitation not found")
            }
            else -> null
        }
    }

    private fun create(w: World, r: DemoRequest): DemoResponse {
        val o = r.json as? JsonObject ?: JsonObject(emptyMap())
        val now = Instant.now()
        val code = (0 until 8).joinToString("") { i -> CODE_CHARS[(rand("$now$i") * CODE_CHARS.length).toInt().coerceIn(0, CODE_CHARS.length - 1)].toString() }
        val expiresDays = o["expires_in_days"].int()
        val libs = (o["library_ids"] as? JsonArray)?.mapNotNull { it.int() }.orEmpty()
        val duration: JsonElement = o["duration"] ?: j("unlimited")
        val inv = obj(
            "id" to w.misc.nextInvite++,
            "code" to code,
            "url" to "/j/$code",
            "status" to "pending",
            "created" to naive(now),
            "expires" to expiresDays?.let { naive(now.plusSeconds(it * 86_400L)) },
            "used_at" to null,
            "used_by" to null,
            "duration" to duration,
            "unlimited" to (o["unlimited"].bool() ?: false),
            "specific_libraries" to libs,
            "server_names" to listOf("Living Room"),
        )
        invites(w).add(0, inv)
        return DemoResponse.json(obj("message" to "Invitation created", "invitation" to inv), 201)
    }
}
