package app.bosunarr.demo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Sonarr v4 / Radarr v6 API (`/api/v3`), backed by the [World]'s library and the simulated torrents. */
internal object DemoArr {
    private class Command(val id: Int, val name: String, val at: Instant, val trigger: String)

    @Suppress("UNCHECKED_CAST")
    private fun commands(w: World, sonarr: Boolean) =
        w.misc.bag.getOrPut(if (sonarr) "cmd:sonarr" else "cmd:radarr") { mutableListOf<Command>() } as MutableList<Command>

    private var commandId = 4100

    fun handle(w: World, r: DemoRequest): DemoResponse? {
        val sonarr = r.service == "sonarr"
        val p = r.path
        val fixed = when (p) {
            "/api/v3/system/status" -> "system-status"
            "/api/v3/health" -> "health"
            "/api/v3/diskspace" -> "diskspace"
            "/api/v3/rootfolder" -> "rootfolder"
            "/api/v3/qualityprofile" -> "qualityprofile"
            "/api/v3/languageprofile" -> if (sonarr) "languageprofile" else null
            else -> null
        }
        if (fixed != null && r.method == "GET") return DemoResponse.json(DemoAssets.json("${r.service}/$fixed.json"))

        return when {
            p == "/api/v3/queue/status" -> queueStatus(w, sonarr)
            p == "/api/v3/queue" -> DemoResponse.json(queuePage(w, sonarr))
            p.startsWith("/api/v3/queue/") && r.method == "DELETE" -> removeQueue(w, p.substringAfterLast('/').toIntOrNull(), r)
            p == "/api/v3/command" && r.method == "POST" -> command(w, r, sonarr)
            p == "/api/v3/command" -> DemoResponse.json(arr(commandList(w, sonarr)))
            p == "/api/v3/wanted/missing" -> DemoResponse.json(obj("page" to 1, "pageSize" to 1, "totalRecords" to missingCount(w, sonarr), "records" to emptyList<Any>()))
            p == "/api/v3/wanted/cutoff" -> DemoResponse.json(obj("page" to 1, "pageSize" to 1, "totalRecords" to if (sonarr) 3 else 2, "records" to emptyList<Any>()))
            p == "/api/v3/history" -> DemoResponse.json(history(w, r, sonarr))
            p == "/api/v3/calendar" -> DemoResponse.json(if (sonarr) episodeCalendar(w, r) else movieCalendar(w, r))
            sonarr -> sonarrRoutes(w, r)
            else -> radarrRoutes(w, r)
        }
    }

    /* ------------------------------------------------------------ images */

    private fun images(service: String, id: Int) = listOf(
        obj("coverType" to "poster", "url" to "/MediaCover/$id/poster.jpg"),
        obj("coverType" to "fanart", "url" to "/MediaCover/$id/fanart.jpg"),
    )

    private fun lookupImages(key: String) = listOf(
        obj("coverType" to "poster", "url" to "/MediaCover/lookup/$key/poster.jpg"),
        obj("coverType" to "fanart", "url" to "/MediaCover/lookup/$key/fanart.jpg"),
    )

    /* ------------------------------------------------------------ movies */

    private fun releaseDates(w: World, lm: LibMovie?, m: DMovie): Triple<String?, String?, String?> {
        val cinema = "${m.released}T00:00:00Z"
        val home = m.homeInDays?.let { w.today().plusDays(it.toLong()) }
        val digital = home?.let { "${it}T00:00:00Z" } ?: if (m.year >= 2000) "${LocalDate.parse(m.released).plusDays(90)}T00:00:00Z" else null
        val physical = if (m.key == "glass-half") digital else if (m.year >= 2000 && home == null) "${LocalDate.parse(m.released).plusDays(200)}T00:00:00Z" else null
        return Triple(cinema, digital, physical)
    }

    private fun profileFor(quality: String, sonarr: Boolean) = when {
        resolutionOf(quality) >= 2160 -> if (sonarr) 7 else 5
        sonarr -> 6
        else -> 4
    }

    fun movieJson(w: World, lm: LibMovie): JsonObject {
        val m = lm.m
        val (cinema, digital, physical) = releaseDates(w, lm, m)
        val folder = "${m.title.replace(":", "")} (${m.year})"
        val size = if (lm.hasFile) m.size else 0L
        val res = resolutionOf(m.quality)
        val file = if (!lm.hasFile) null else obj(
            "id" to lm.id,
            "movieId" to lm.id,
            "relativePath" to "$folder [${m.quality.replace("Bluray", "Bluray")}].mkv",
            "path" to "/data/media/movies/$folder/$folder [${m.quality}].mkv",
            "size" to m.size,
            "dateAdded" to (lm.fileAdded ?: w.started),
            "quality" to obj("quality" to obj("id" to if (res >= 2160) 19 else 7, "name" to m.quality, "source" to if (m.quality.startsWith("Bluray")) "bluray" else "webdl", "resolution" to res), "revision" to obj("version" to 1, "real" to 0, "isRepack" to false)),
            "mediaInfo" to obj(
                "videoCodec" to if (res >= 2160) "x265" else "x264",
                "audioCodec" to if (m.year < 1930) "FLAC" else if (res >= 2160) "EAC3" else "AAC",
                "audioChannels" to if (m.year < 1960) 2.0 else 5.1,
                "resolution" to if (res >= 2160) "3840x2160" else "1920x1080",
                "videoDynamicRangeType" to if (res >= 2160) "HDR10" else "",
                "audioLanguages" to if (m.year < 1929) "" else "English",
                "subtitles" to "English",
                "runTime" to "%d:%02d:00".format(m.runtime / 60, m.runtime % 60),
            ),
            "languages" to listOf(obj("id" to 1, "name" to if (m.year < 1929) "Unknown" else "English")),
            "releaseGroup" to releaseName(m).substringAfterLast('-'),
        )
        return obj(
            "id" to lm.id,
            "title" to m.title,
            "originalTitle" to m.title,
            "sortTitle" to m.title.lowercase().removePrefix("the ").removePrefix("a "),
            "sizeOnDisk" to size,
            "status" to if (lm.upcoming) "inCinemas" else "released",
            "overview" to m.overview,
            "inCinemas" to cinema,
            "digitalRelease" to digital,
            "physicalRelease" to physical,
            "images" to images("radarr", lm.id),
            "website" to "",
            "year" to m.year,
            "studio" to m.studio,
            "path" to "/data/media/movies/$folder",
            "qualityProfileId" to profileFor(m.quality, false),
            "hasFile" to lm.hasFile,
            "movieFileId" to if (lm.hasFile) lm.id else 0,
            "monitored" to lm.monitored,
            "minimumAvailability" to "released",
            "isAvailable" to !lm.upcoming,
            "runtime" to m.runtime,
            "cleanTitle" to m.title.lowercase().filter { it.isLetterOrDigit() },
            "imdbId" to m.imdbId,
            "tmdbId" to m.tmdbId,
            "titleSlug" to "${m.key}-${m.tmdbId}",
            "rootFolderPath" to "/data/media/movies",
            "certification" to m.certification,
            "genres" to m.genres,
            "tags" to emptyList<Int>(),
            "added" to lm.added,
            "ratings" to obj(
                "imdb" to obj("votes" to m.votes, "value" to m.rating, "type" to "user"),
                "tmdb" to obj("votes" to m.votes / 9, "value" to ((m.rating - 0.2) * 10).toInt() / 10.0, "type" to "user"),
                "rottenTomatoes" to m.rt?.let { obj("votes" to 0, "value" to it, "type" to "user") },
            ),
            "movieFile" to file,
            "popularity" to 10 + rand(m.key) * 40,
            "statistics" to obj("movieFileCount" to if (lm.hasFile) 1 else 0, "sizeOnDisk" to size, "releaseGroups" to emptyList<String>()),
        )
    }

    private fun lookupMovieJson(m: DMovie) = obj(
        "id" to 0,
        "title" to m.title,
        "originalTitle" to m.title,
        "sortTitle" to m.title.lowercase(),
        "status" to "released",
        "overview" to m.overview,
        "inCinemas" to "${m.released}T00:00:00Z",
        "images" to lookupImages(m.key),
        "year" to m.year,
        "studio" to m.studio,
        "runtime" to m.runtime,
        "imdbId" to m.imdbId,
        "tmdbId" to m.tmdbId,
        "titleSlug" to "${m.key}-${m.tmdbId}",
        "certification" to m.certification,
        "genres" to m.genres,
        "ratings" to obj("imdb" to obj("votes" to m.votes, "value" to m.rating, "type" to "user")),
        "monitored" to false,
        "hasFile" to false,
        "isAvailable" to true,
        "minimumAvailability" to "released",
        "folder" to "${m.title.replace(":", "")} (${m.year})",
    )

    private fun radarrRoutes(w: World, r: DemoRequest): DemoResponse? {
        val p = r.path
        return when {
            p == "/api/v3/movie" && r.method == "GET" -> DemoResponse.json(arr(w.movies.map { movieJson(w, it) }))
            p == "/api/v3/movie" && r.method == "POST" -> addMovie(w, r)
            p == "/api/v3/movie/lookup" -> {
                val term = r.q("term").orEmpty().trim().lowercase()
                if (term.isEmpty()) return DemoResponse.json(JsonArray(emptyList()))
                val hits = w.cat.movies.filter { it.title.lowercase().contains(term) || term.removePrefix("tmdb:") == it.tmdbId.toString() }
                DemoResponse.json(arr(hits.map { m -> w.movieByKey(m.key)?.let { movieJson(w, it) } ?: lookupMovieJson(m) }))
            }
            p.startsWith("/api/v3/movie/") -> {
                val id = p.substringAfterLast('/').toIntOrNull() ?: return null
                val lm = w.movie(id) ?: return DemoResponse.notFound("Movie with ID $id does not exist")
                when (r.method) {
                    "PUT" -> {
                        (r.json as? JsonObject)?.get("monitored").bool()?.let { lm.monitored = it }
                        DemoResponse.json(movieJson(w, lm), 202)
                    }
                    "DELETE" -> {
                        w.movies.remove(lm)
                        w.torrents.list.filter { it.link == TorrentLink("radarr", id) }.forEach { it.link = null }
                        DemoResponse.empty(200)
                    }
                    else -> DemoResponse.json(movieJson(w, lm))
                }
            }
            else -> null
        }
    }

    private fun addMovie(w: World, r: DemoRequest): DemoResponse {
        val body = r.json as? JsonObject ?: return DemoResponse.json(obj("message" to "Invalid body"), 400)
        val tmdb = body["tmdbId"].int() ?: 0
        val m = w.cat.movies.firstOrNull { it.tmdbId == tmdb } ?: return DemoResponse.json(arr(listOf(obj("propertyName" to "TmdbId", "errorMessage" to "Movie not found"))), 400)
        if (w.movieByKey(m.key) != null) return DemoResponse.json(arr(listOf(obj("propertyName" to "TmdbId", "errorMessage" to "This movie has already been added"))), 400)
        val lm = addToRadarr(w, m, monitored = body["monitored"].bool() ?: true)
        val search = (body["addOptions"] as? JsonObject)?.get("searchForMovie").bool() == true
        if (search) grabMovie(w, lm)
        return DemoResponse.json(movieJson(w, lm), 201)
    }

    fun addToRadarr(w: World, m: DMovie, monitored: Boolean = true): LibMovie {
        w.movieByKey(m.key)?.let { return it }
        val lm = LibMovie((w.movies.maxOfOrNull { it.id } ?: 0) + 1, m, monitored, hasFile = false, added = Instant.now(), fileAdded = null)
        w.movies += lm
        return lm
    }

    /** Pretend an indexer search found a release: it starts downloading. */
    fun grabMovie(w: World, lm: LibMovie) {
        if (lm.hasFile || lm.upcoming) return
        if (w.torrents.list.any { it.link == TorrentLink("radarr", lm.id) && it.progress(System.currentTimeMillis()) < 1 }) return
        val m = lm.m
        val size = if (m.size > 0) m.size else (m.runtime.coerceAtLeast(5) * 52L * 1024 * 1024)
        val name = releaseName(m)
        w.torrents.add(name, size, "radarr", TorrentLink("radarr", lm.id))
        w.addHistory("radarr", "grabbed", movieId = lm.id, source = name, quality = m.quality, data = mapOf("indexer" to indexerFor(m.key), "downloadClientName" to "qBittorrent"))
    }

    /* ------------------------------------------------------------ series */

    private fun stats(w: World, s: LibSeries): JsonObject {
        val now = Instant.now()
        val files = s.episodes.count { it.hasFile }
        val want = s.episodes.count { it.hasFile || (it.monitored && it.air.isBefore(now)) }
        return obj(
            "episodeFileCount" to files,
            "episodeCount" to want,
            "totalEpisodeCount" to s.episodes.size,
            "sizeOnDisk" to files * s.s.episodeSize,
            "seasonCount" to 1,
            "releaseGroups" to listOf("ARCHiVE"),
            "percentOfEpisodes" to if (want == 0) 0.0 else files * 100.0 / want,
            "previousAiring" to s.episodes.lastOrNull { it.air.isBefore(now) }?.air,
            "nextAiring" to s.episodes.firstOrNull { it.air.isAfter(now) }?.air,
        )
    }

    fun seriesJson(w: World, s: LibSeries): JsonObject {
        val now = Instant.now()
        val st = stats(w, s)
        val seasonMon = s.seasonMonitored[1] ?: s.episodes.any { it.monitored }
        return obj(
            "id" to s.id,
            "title" to s.s.title,
            "alternateTitles" to emptyList<Any>(),
            "sortTitle" to s.s.title.lowercase(),
            "status" to s.s.status,
            "ended" to (s.s.status == "ended"),
            "overview" to s.s.overview,
            "previousAiring" to s.episodes.lastOrNull { it.air.isBefore(now) }?.air,
            "nextAiring" to s.episodes.firstOrNull { it.air.isAfter(now) }?.air,
            "network" to s.s.network,
            "airTime" to "21:00",
            "images" to images("sonarr", s.id),
            "originalLanguage" to obj("id" to 1, "name" to "English"),
            "seasons" to listOf(obj("seasonNumber" to 1, "monitored" to seasonMon, "statistics" to st)),
            "year" to s.s.year,
            "path" to "/data/media/tv/${s.s.title} (${s.s.year})",
            "qualityProfileId" to profileFor(s.s.quality, true),
            "languageProfileId" to 1,
            "seasonFolder" to true,
            "monitored" to s.monitored,
            "monitorNewItems" to "all",
            "useSceneNumbering" to false,
            "runtime" to s.s.runtime,
            "tvdbId" to s.s.tvdbId,
            "tvRageId" to 0,
            "tvMazeId" to 0,
            "tmdbId" to s.s.tmdbId,
            "firstAired" to s.episodes.firstOrNull()?.air,
            "lastAired" to s.episodes.lastOrNull()?.air,
            "seriesType" to "standard",
            "cleanTitle" to s.s.title.lowercase().filter { it.isLetterOrDigit() },
            "titleSlug" to s.s.key,
            "rootFolderPath" to "/data/media/tv",
            "certification" to s.s.certification,
            "genres" to s.s.genres,
            "tags" to emptyList<Int>(),
            "added" to s.added,
            "ratings" to obj("votes" to s.s.votes, "value" to s.s.rating),
            "statistics" to st,
        )
    }

    private fun lookupSeriesJson(s: DSeries) = obj(
        "title" to s.title,
        "sortTitle" to s.title.lowercase(),
        "status" to s.status,
        "overview" to s.overview,
        "network" to s.network,
        "images" to lookupImages(s.key),
        "seasons" to listOf(obj("seasonNumber" to 1, "monitored" to true)),
        "year" to s.year,
        "runtime" to s.runtime,
        "tvdbId" to s.tvdbId,
        "tmdbId" to s.tmdbId,
        "titleSlug" to s.key,
        "certification" to s.certification,
        "genres" to s.genres,
        "ratings" to obj("votes" to s.votes, "value" to s.rating),
        "folder" to "${s.title} (${s.year})",
    )

    private fun episodeJson(w: World, e: LibEpisode, s: LibSeries?, withSeries: Boolean = false): JsonObject {
        val date = e.air.atZone(w.zone).toLocalDate()
        return obj(
            "seriesId" to e.seriesId,
            "tvdbId" to (s?.s?.tvdbId ?: 0) * 100 + e.n,
            "episodeFileId" to if (e.hasFile) e.id else 0,
            "seasonNumber" to e.season,
            "episodeNumber" to e.n,
            "title" to e.title,
            "airDate" to date.toString(),
            "airDateUtc" to e.air,
            "runtime" to (s?.s?.runtime ?: 0),
            "overview" to null,
            "hasFile" to e.hasFile,
            "monitored" to e.monitored,
            "finaleType" to e.finale,
            "unverifiedSceneNumbering" to false,
            "id" to e.id,
            "series" to if (withSeries && s != null) seriesJson(w, s) else null,
        )
    }

    private fun sonarrRoutes(w: World, r: DemoRequest): DemoResponse? {
        val p = r.path
        return when {
            p == "/api/v3/series" && r.method == "GET" -> DemoResponse.json(arr(w.series.map { seriesJson(w, it) }))
            p == "/api/v3/series" && r.method == "POST" -> addSeries(w, r)
            p == "/api/v3/series/lookup" -> {
                val term = r.q("term").orEmpty().trim().lowercase()
                if (term.isEmpty()) return DemoResponse.json(JsonArray(emptyList()))
                val hits = w.cat.series.filter { it.title.lowercase().contains(term) || term.removePrefix("tvdb:") == it.tvdbId.toString() }
                DemoResponse.json(arr(hits.map { s -> w.series.firstOrNull { it.s.key == s.key }?.let { seriesJson(w, it) } ?: lookupSeriesJson(s) }))
            }
            p.startsWith("/api/v3/series/") -> {
                val id = p.substringAfterLast('/').toIntOrNull() ?: return null
                val s = w.seriesById(id) ?: return DemoResponse.notFound("Series with ID $id does not exist")
                when (r.method) {
                    "PUT" -> {
                        val o = r.json as? JsonObject
                        o?.get("monitored").bool()?.let { s.monitored = it }
                        (o?.get("seasons") as? JsonArray)?.forEach { el ->
                            val so = el as? JsonObject ?: return@forEach
                            val n = so["seasonNumber"].int() ?: return@forEach
                            val mon = so["monitored"].bool() ?: return@forEach
                            val was = s.seasonMonitored[n] ?: s.episodes.any { it.monitored }
                            if (mon != was) {
                                s.seasonMonitored[n] = mon
                                s.episodes.filter { it.season == n }.forEach { it.monitored = mon }
                            }
                        }
                        DemoResponse.json(seriesJson(w, s), 202)
                    }
                    "DELETE" -> {
                        w.series.remove(s)
                        val ids = s.episodes.map { it.id }.toSet()
                        w.torrents.list.filter { it.link?.service == "sonarr" && it.link?.id in ids }.forEach { it.link = null }
                        DemoResponse.empty(200)
                    }
                    else -> DemoResponse.json(seriesJson(w, s))
                }
            }
            p == "/api/v3/episode" -> {
                val s = r.q("seriesId")?.toIntOrNull()?.let { w.seriesById(it) } ?: return DemoResponse.json(JsonArray(emptyList()))
                DemoResponse.json(arr(s.episodes.map { episodeJson(w, it, s) }))
            }
            p == "/api/v3/episode/monitor" && r.method == "PUT" -> {
                val o = r.json as? JsonObject
                val mon = o?.get("monitored").bool() ?: true
                val ids = (o?.get("episodeIds") as? JsonArray)?.mapNotNull { it.int() }.orEmpty()
                ids.forEach { w.episode(it)?.monitored = mon }
                DemoResponse.json(arr(ids.mapNotNull { id -> w.episode(id)?.let { episodeJson(w, it, w.seriesById(it.seriesId)) } }), 202)
            }
            p == "/api/v3/episodefile" -> {
                val s = r.q("seriesId")?.toIntOrNull()?.let { w.seriesById(it) } ?: return DemoResponse.json(JsonArray(emptyList()))
                val res = resolutionOf(s.s.quality)
                DemoResponse.json(arr(s.episodes.filter { it.hasFile }.map { e ->
                    obj(
                        "seriesId" to s.id, "seasonNumber" to e.season, "id" to e.id,
                        "relativePath" to "Season 01/${s.s.title} (${s.s.year}) - S01E%02d - ${e.title} [${s.s.quality}].mkv".format(e.n),
                        "path" to "/data/media/tv/${s.s.title} (${s.s.year})/Season 01/${s.s.title} - S01E%02d.mkv".format(e.n),
                        "size" to s.s.episodeSize + (rand("sz${e.id}") * 40_000_000).toLong(),
                        "dateAdded" to (e.fileAdded ?: w.started),
                        "quality" to obj("quality" to obj("name" to s.s.quality, "resolution" to res)),
                    )
                }))
            }
            else -> null
        }
    }

    private fun addSeries(w: World, r: DemoRequest): DemoResponse {
        val body = r.json as? JsonObject ?: return DemoResponse.json(obj("message" to "Invalid body"), 400)
        val tvdb = body["tvdbId"].int() ?: 0
        val d = w.cat.series.firstOrNull { it.tvdbId == tvdb } ?: return DemoResponse.json(arr(listOf(obj("propertyName" to "TvdbId", "errorMessage" to "Series not found"))), 400)
        if (w.series.any { it.s.key == d.key }) return DemoResponse.json(arr(listOf(obj("propertyName" to "TvdbId", "errorMessage" to "This series has already been added"))), 400)
        val id = (w.series.maxOfOrNull { it.id } ?: 0) + 1
        val airs = w.airTimes(d)
        val monitor = (body["addOptions"] as? JsonObject)?.get("monitor").str() ?: "all"
        val eps = d.episodes.map { e ->
            LibEpisode(id * 1000 + e.n, id, 1, e.n, e.title, airs.getValue(e.n), hasFile = false, monitored = monitor != "none", finale = e.finale, fileAdded = null)
        }.toMutableList()
        val s = LibSeries(id, d, monitored = monitor != "none", added = Instant.now(), episodes = eps)
        w.series += s
        if ((body["addOptions"] as? JsonObject)?.get("searchForMissingEpisodes").bool() == true) grabEpisodes(w, s, eps)
        return DemoResponse.json(seriesJson(w, s), 201)
    }

    private fun grabEpisodes(w: World, s: LibSeries, eps: List<LibEpisode>) {
        val now = Instant.now()
        val busy = w.torrents.list.filter { it.link?.service == "sonarr" && it.progress(System.currentTimeMillis()) < 1 }.mapNotNull { it.link?.id }.toSet()
        eps.filter { !it.hasFile && it.monitored && it.air.isBefore(now) && it.id !in busy }.take(3).forEach { e ->
            val name = releaseName(s.s, e)
            w.torrents.add(name, s.s.episodeSize, "tv-sonarr", TorrentLink("sonarr", e.id))
            w.addHistory("sonarr", "grabbed", episodeId = e.id, source = name, quality = s.s.quality, data = mapOf("indexer" to "Internet Archive", "downloadClientName" to "qBittorrent"))
        }
    }

    /* ------------------------------------------------------------ calendar */

    private fun range(r: DemoRequest): Pair<Instant, Instant> {
        val start = r.q("start")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now().minus(Duration.ofDays(1))
        val end = r.q("end")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now().plus(Duration.ofDays(14))
        return start to end
    }

    private fun episodeCalendar(w: World, r: DemoRequest): JsonArray {
        val (start, end) = range(r)
        val unmonitored = r.q("unmonitored") == "true"
        val withSeries = r.q("includeSeries") == "true"
        return arr(w.series.flatMap { s ->
            s.episodes.filter { !it.air.isBefore(start) && it.air.isBefore(end) && (unmonitored || (it.monitored && s.monitored)) }
                .map { episodeJson(w, it, s, withSeries) }
        })
    }

    private fun movieCalendar(w: World, r: DemoRequest): JsonArray {
        val (start, end) = range(r)
        val unmonitored = r.q("unmonitored") == "true"
        fun inRange(d: String?) = d?.let { Instant.parse(it) }?.let { !it.isBefore(start) && it.isBefore(end) } == true
        return arr(w.movies.filter { lm ->
            val (c, d, ph) = releaseDates(w, lm, lm.m)
            (unmonitored || lm.monitored) && (inRange(c) || inRange(d) || inRange(ph))
        }.map { movieJson(w, it) })
    }

    /* ------------------------------------------------------------ queue */

    private fun queued(w: World, sonarr: Boolean): List<DTorrent> {
        val now = System.currentTimeMillis()
        val svc = if (sonarr) "sonarr" else "radarr"
        return w.torrents.list.filter { t -> t.link?.service == svc && (t.progress(now) < 1 || t.importBlocked != null) }
    }

    private fun timeLeft(sec: Long): String {
        if (sec >= 8_640_000) return "00:00:00"
        val d = sec / 86_400
        val h = (sec % 86_400) / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return (if (d > 0) "$d." else "") + "%02d:%02d:%02d".format(h, m, s)
    }

    private fun queuePage(w: World, sonarr: Boolean): JsonObject {
        val now = System.currentTimeMillis()
        val records = queued(w, sonarr).mapNotNull { t ->
            val l = t.link ?: return@mapNotNull null
            val p = t.progress(now)
            val status = when {
                t.importBlocked != null || p >= 1 -> "completed"
                t.state == "queuedDL" -> "queued"
                t.state.startsWith("stopped") || t.state.startsWith("paused") -> "paused"
                else -> "downloading"
            }
            val base = mutableListOf<Pair<String, Any?>>(
                "id" to t.queueId,
                "title" to t.name,
                "size" to t.size.toDouble(),
                "sizeleft" to (t.size * (1 - p)),
                "status" to status,
                "timeleft" to if (status == "downloading") timeLeft(t.eta(now)) else null,
                "estimatedCompletionTime" to if (status == "downloading") Instant.ofEpochMilli(now + t.eta(now) * 1000) else null,
                "added" to Instant.ofEpochSecond(t.addedOn),
                "trackedDownloadStatus" to if (t.importBlocked != null) "warning" else "ok",
                "trackedDownloadState" to if (t.importBlocked != null) "importBlocked" else "downloading",
                "statusMessages" to if (t.importBlocked != null) listOf(obj("title" to t.name, "messages" to listOf(t.importBlocked))) else emptyList<Any>(),
                "protocol" to "torrent",
                "downloadClient" to "qBittorrent",
                "downloadClientHasPostImportCategory" to false,
                "downloadId" to t.hash.uppercase(),
                "indexer" to indexerFor(t.name),
                "outputPath" to "${t.savePath}/${t.name}",
            )
            if (sonarr) {
                val e = w.episode(l.id) ?: return@mapNotNull null
                val s = w.seriesById(e.seriesId) ?: return@mapNotNull null
                base += "quality" to obj("quality" to obj("name" to s.s.quality, "resolution" to resolutionOf(s.s.quality)))
                base += "seriesId" to s.id
                base += "episodeId" to e.id
                base += "seasonNumber" to e.season
                base += "series" to obj("id" to s.id, "title" to s.s.title, "year" to s.s.year, "images" to images("sonarr", s.id))
                base += "episode" to obj("id" to e.id, "seasonNumber" to e.season, "episodeNumber" to e.n, "title" to e.title, "airDateUtc" to e.air)
            } else {
                val lm = w.movie(l.id) ?: return@mapNotNull null
                base += "quality" to obj("quality" to obj("name" to lm.m.quality, "resolution" to resolutionOf(lm.m.quality)))
                base += "movieId" to lm.id
                base += "movie" to obj("id" to lm.id, "title" to lm.m.title, "year" to lm.m.year, "images" to images("radarr", lm.id))
            }
            obj(*base.toTypedArray())
        }
        return obj("page" to 1, "pageSize" to 1000, "sortKey" to "timeleft", "sortDirection" to "ascending", "totalRecords" to records.size, "records" to records)
    }

    private fun queueStatus(w: World, sonarr: Boolean): DemoResponse {
        val q = queued(w, sonarr)
        return DemoResponse.json(obj(
            "totalCount" to q.size, "count" to q.size, "unknownCount" to 0,
            "errors" to false, "warnings" to q.any { it.importBlocked != null },
            "unknownErrors" to false, "unknownWarnings" to false,
        ))
    }

    private fun removeQueue(w: World, id: Int?, r: DemoRequest): DemoResponse {
        val t = w.torrents.list.firstOrNull { it.queueId == id && it.link != null } ?: return DemoResponse.notFound()
        if (r.q("removeFromClient") != "false") w.torrents.list.remove(t) else { t.link = null; t.importBlocked = null }
        return DemoResponse.empty(200)
    }

    /* ------------------------------------------------------------ commands */

    private fun commandJson(c: Command): JsonObject {
        val age = Duration.between(c.at, Instant.now()).toMillis()
        val status = when { age < 1_200 -> "queued"; age < 6_000 -> "started"; else -> "completed" }
        return obj(
            "id" to c.id, "name" to c.name, "commandName" to null, "status" to status, "result" to if (status == "completed") "successful" else "unknown",
            "queued" to c.at, "started" to if (status != "queued") c.at.plusMillis(1_200) else null, "ended" to if (status == "completed") c.at.plusMillis(6_000) else null,
            "trigger" to c.trigger, "priority" to "normal",
        )
    }

    private fun commandList(w: World, sonarr: Boolean): List<JsonObject> {
        val commands = commands(w, sonarr)
        if (commands.isEmpty()) {
            val now = Instant.now()
            commands += Command(commandId++, "RefreshMonitoredDownloads", now.minusSeconds(40), "scheduled")
            commands += Command(commandId++, "RssSync", now.minusSeconds(600), "scheduled")
            commands += Command(commandId++, "Housekeeping", now.minusSeconds(3_600), "scheduled")
        }
        return commands.sortedByDescending { it.at }.map(::commandJson)
    }

    private fun command(w: World, r: DemoRequest, sonarr: Boolean): DemoResponse {
        val o = r.json as? JsonObject ?: JsonObject(emptyMap())
        val name = o["name"].str() ?: return DemoResponse.json(obj("message" to "Command name is required"), 400)
        val c = Command(commandId++, name, Instant.now(), "manual")
        commandList(w, sonarr)
        commands(w, sonarr) += c
        when (name) {
            "MoviesSearch" -> (o["movieIds"] as? JsonArray)?.mapNotNull { it.int() }?.forEach { id -> w.movie(id)?.let { grabMovie(w, it) } }
            "MissingMoviesSearch" -> w.movies.filter { it.monitored && !it.hasFile && !it.upcoming }.forEach { grabMovie(w, it) }
            "SeriesSearch", "SeasonSearch" -> o["seriesId"].int()?.let { w.seriesById(it) }?.let { grabEpisodes(w, it, it.episodes) }
            "EpisodeSearch" -> (o["episodeIds"] as? JsonArray)?.mapNotNull { it.int() }?.mapNotNull { w.episode(it) }?.groupBy { it.seriesId }
                ?.forEach { (sid, eps) -> w.seriesById(sid)?.let { grabEpisodes(w, it, eps) } }
            "MissingEpisodeSearch" -> w.series.filter { it.monitored }.forEach { grabEpisodes(w, it, it.episodes) }
        }
        return DemoResponse.json(commandJson(c), 201)
    }

    private fun missingCount(w: World, sonarr: Boolean): Int {
        val now = Instant.now()
        return if (sonarr) w.series.filter { it.monitored }.sumOf { s -> s.episodes.count { it.monitored && !it.hasFile && it.air.isBefore(now) } }
        else w.movies.count { it.monitored && !it.hasFile && !it.upcoming }
    }

    /* ------------------------------------------------------------ history */

    private val SONARR_EVENTS = mapOf(1 to "grabbed", 2 to "seriesFolderImported", 3 to "downloadFolderImported", 4 to "downloadFailed", 5 to "episodeFileDeleted", 6 to "episodeFileRenamed", 7 to "downloadIgnored")
    private val RADARR_EVENTS = mapOf(1 to "grabbed", 3 to "downloadFolderImported", 4 to "downloadFailed", 6 to "movieFileDeleted", 7 to "movieFolderImported", 8 to "movieFileRenamed", 9 to "downloadIgnored")

    private fun history(w: World, r: DemoRequest, sonarr: Boolean): JsonObject {
        val svc = if (sonarr) "sonarr" else "radarr"
        val codes = r.qAll("eventType").mapNotNull { it.toIntOrNull() }
        val wanted = codes.mapNotNull { (if (sonarr) SONARR_EVENTS else RADARR_EVENTS)[it] }.toSet()
        val page = r.q("page")?.toIntOrNull() ?: 1
        val size = r.q("pageSize")?.toIntOrNull() ?: 20
        val withSeries = r.q("includeSeries") == "true"
        val withEpisode = r.q("includeEpisode") == "true"
        val withMovie = r.q("includeMovie") == "true"
        val all = w.history.filter { it.service == svc && (wanted.isEmpty() || it.type in wanted) }
            .filter { h -> if (sonarr) w.episode(h.episodeId ?: -1) != null else w.movie(h.movieId ?: -1) != null }
            .sortedByDescending { it.at }
        val records = all.drop((page - 1) * size).take(size).map { h ->
            val items = mutableListOf<Pair<String, Any?>>(
                "id" to h.id,
                "eventType" to h.type,
                "date" to h.at,
                "sourceTitle" to h.source,
                "quality" to obj("quality" to obj("name" to h.quality, "resolution" to resolutionOf(h.quality))),
                "downloadId" to infoHash(h.source).uppercase(),
                "data" to h.data,
            )
            if (sonarr) {
                val e = w.episode(h.episodeId!!)!!
                val s = w.seriesById(e.seriesId)!!
                items += "seriesId" to s.id
                items += "episodeId" to e.id
                if (withSeries) items += "series" to obj("id" to s.id, "title" to s.s.title, "year" to s.s.year, "images" to images("sonarr", s.id))
                if (withEpisode) items += "episode" to obj("id" to e.id, "seasonNumber" to e.season, "episodeNumber" to e.n, "title" to e.title)
            } else {
                val lm = w.movie(h.movieId!!)!!
                items += "movieId" to lm.id
                if (withMovie) items += "movie" to obj("id" to lm.id, "title" to lm.m.title, "year" to lm.m.year, "images" to images("radarr", lm.id))
            }
            obj(*items.toTypedArray())
        }
        return obj("page" to page, "pageSize" to size, "sortKey" to "date", "sortDirection" to "descending", "totalRecords" to all.size, "records" to records)
    }
}
