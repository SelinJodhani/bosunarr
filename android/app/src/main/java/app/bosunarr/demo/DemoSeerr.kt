package app.bosunarr.demo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant

/** A Jellyseerr request in the demo. */
internal class DReq(
    val id: Int,
    val key: String,
    val type: String,
    var status: Int,
    val is4k: Boolean,
    val userId: Int,
    val createdAt: Instant,
    var updatedAt: Instant,
    val seasons: List<Int> = emptyList(),
    var modifiedBy: Int? = null,
)

internal class DemoSeerrState(w: World) {
    val requests = mutableListOf<DReq>()
    var nextId = 40

    init {
        val now = w.started
        fun req(key: String, type: String, status: Int, user: Int, ago: Duration, is4k: Boolean = false, seasons: List<Int> = emptyList()) {
            requests += DReq(nextId++, key, type, status, is4k, user, now.minus(ago), now.minus(ago).plus(Duration.ofMinutes(if (status == 1) 0 else 20)), seasons, if (status == 1) null else 1)
        }
        req("caminandes", "tv", 2, 2, days(212.0), seasons = listOf(1))
        req("metropolis", "movie", 2, 4, days(53.0))
        req("big-buck-bunny", "movie", 2, 1, days(42.0))
        req("sherlock-holmes-1954", "tv", 2, 2, days(31.0), seasons = listOf(1))
        req("his-girl-friday", "movie", 3, 3, days(12.0), is4k = true)
        req("charge", "movie", 2, 3, days(8.0))
        req("plan-9-from-outer-space", "movie", 2, 3, days(6.3))
        req("coffee-run", "movie", 2, 4, days(4.0))
        req("sprite-fright", "movie", 2, 1, days(2.0))
        req("the-little-shop-of-horrors", "movie", 1, 3, hours(26.0))
        req("sita-sings-the-blues", "movie", 1, 2, hours(3.2))
        requests.reverse()
    }
}

/** Jellyseerr API v1. Poster and backdrop paths are `/<key>.jpg` / `/<key>-bg.jpg`, served by the image proxy. */
internal object DemoSeerr {
    private val GENRES = mapOf(
        "Action" to 28, "Adventure" to 12, "Animation" to 16, "Comedy" to 35, "Crime" to 80, "Drama" to 18, "Family" to 10751,
        "Fantasy" to 14, "Horror" to 27, "Music" to 10402, "Mystery" to 9648, "Romance" to 10749, "Science Fiction" to 878,
        "Thriller" to 53, "War" to 10752,
    )

    /** A catalog title, movie or series, as Seerr sees it. */
    private class Title(val type: String, val key: String, val tmdbId: Int, val name: String, val year: Int, val date: String, val rating: Double, val popularity: Double)

    private fun titles(w: World): List<Title> =
        w.cat.movies.map { Title("movie", it.key, it.tmdbId, it.title, it.year, it.released, it.rating, it.views + 20 * rand("pop" + it.key)) } +
            w.cat.series.map { s -> Title("tv", s.key, s.tmdbId, s.title, s.year, s.episodes.first().aired ?: "${s.year}-10-18", s.rating, 40 + 20 * rand("pop" + s.key)) }

    private fun byTmdb(w: World, type: String, id: Int) = titles(w).firstOrNull { it.type == type && it.tmdbId == id }

    private fun userJson(id: Int): JsonObject? {
        val users = DemoAssets.json("jellyseerr/users.json") as? JsonArray ?: return null
        return users.firstOrNull { (it as? JsonObject)?.get("id").int() == id } as? JsonObject
    }

    /* ------------------------------------------------------------ media status */

    /** (status, status4k) as Seerr numbers. */
    private fun mediaStatus(w: World, t: Title): Pair<Int, Int> {
        val reqs = w.seerr.requests.filter { it.key == t.key }
        val s4k = when {
            reqs.any { it.is4k && it.status == 1 } -> 2
            reqs.any { it.is4k && it.status == 2 } -> 3
            else -> 1
        }
        val hd = if (t.type == "movie") {
            val lm = w.movieByKey(t.key)
            when {
                lm?.hasFile == true -> 5
                lm != null -> 3
                reqs.any { !it.is4k && it.status == 1 } -> 2
                reqs.any { !it.is4k && it.status == 2 } -> 3
                else -> 1
            }
        } else {
            val s = w.series.firstOrNull { it.s.key == t.key }
            when {
                s == null -> if (reqs.any { it.status == 1 }) 2 else 1
                s.episodes.all { it.hasFile } -> 5
                s.episodes.any { it.hasFile } -> 4
                else -> 3
            }
        }
        return hd to s4k
    }

    private fun mediaJson(w: World, t: Title, withRequests: Boolean): JsonObject? {
        val (hd, uhd) = mediaStatus(w, t)
        val reqs = w.seerr.requests.filter { it.key == t.key }
        if (hd == 1 && uhd == 1 && reqs.isEmpty()) return null
        val series = w.series.firstOrNull { it.s.key == t.key }
        return obj(
            "id" to 100 + t.tmdbId % 1000,
            "mediaType" to t.type,
            "tmdbId" to t.tmdbId,
            "tvdbId" to w.catalogSeries(t.key)?.tvdbId,
            "status" to hd,
            "status4k" to uhd,
            "createdAt" to (reqs.minOfOrNull { it.createdAt } ?: w.started.minus(days(90.0))),
            "updatedAt" to w.started,
            "seasons" to if (t.type == "tv") listOf(obj("id" to 1, "seasonNumber" to 1, "status" to hd, "status4k" to 1)) else emptyList<Any>(),
            "requests" to if (withRequests) reqs.map { requestJson(w, it, includeMedia = false) } else emptyList<Any>(),
            "jellyfinMediaId" to if (hd >= 4) hexId("jf:${t.key}") else null,
            "serviceUrl" to null,
            "downloadStatus" to emptyList<Any>(),
            "plays" to (series?.let { 20 } ?: 0),
        )
    }

    private fun resultJson(w: World, t: Title): JsonObject = if (t.type == "movie") obj(
        "id" to t.tmdbId, "mediaType" to "movie", "title" to t.name, "originalTitle" to t.name, "releaseDate" to t.date,
        "posterPath" to "/${t.key}.jpg", "backdropPath" to "/${t.key}-bg.jpg", "voteAverage" to t.rating, "voteCount" to 1000,
        "popularity" to t.popularity, "adult" to false, "video" to false, "originalLanguage" to "en", "overview" to w.catalogMovie(t.key)?.overview,
        "genreIds" to w.catalogMovie(t.key)?.genres?.mapNotNull { GENRES[it] }.orEmpty(),
        "mediaInfo" to mediaJson(w, t, withRequests = false),
    ) else obj(
        "id" to t.tmdbId, "mediaType" to "tv", "name" to t.name, "originalName" to t.name, "firstAirDate" to t.date,
        "posterPath" to "/${t.key}.jpg", "backdropPath" to "/${t.key}-bg.jpg", "voteAverage" to t.rating, "voteCount" to 300,
        "popularity" to t.popularity, "originalLanguage" to "en", "overview" to w.catalogSeries(t.key)?.overview,
        "genreIds" to w.catalogSeries(t.key)?.genres?.mapNotNull { GENRES[it] }.orEmpty(),
        "mediaInfo" to mediaJson(w, t, withRequests = false),
    )

    private fun page(w: World, list: List<Title>, page: Int = 1) = obj(
        "page" to page, "totalPages" to 1, "totalResults" to list.size,
        "results" to if (page == 1) list.map { resultJson(w, it) } else emptyList<Any>(),
    )

    /* ------------------------------------------------------------ requests */

    private fun requestJson(w: World, q: DReq, includeMedia: Boolean = true): JsonObject {
        val t = titles(w).firstOrNull { it.key == q.key }
        return obj(
            "id" to q.id,
            "status" to q.status,
            "createdAt" to q.createdAt,
            "updatedAt" to q.updatedAt,
            "type" to q.type,
            "is4k" to q.is4k,
            "serverId" to 0,
            "profileId" to if (q.type == "movie") 4 else 6,
            "rootFolder" to if (q.type == "movie") "/data/media/movies" else "/data/media/tv",
            "requestedBy" to userJson(q.userId),
            "modifiedBy" to q.modifiedBy?.let { userJson(it) },
            "seasons" to q.seasons.map { obj("id" to q.id * 10 + it, "seasonNumber" to it, "status" to q.status) },
            "seasonCount" to q.seasons.size,
            "media" to if (includeMedia && t != null) mediaJson(w, t, withRequests = false) else null,
        )
    }

    private fun matches(w: World, q: DReq, filter: String): Boolean {
        val t = titles(w).firstOrNull { it.key == q.key } ?: return false
        val (hd, uhd) = mediaStatus(w, t)
        val st = if (q.is4k) uhd else hd
        return when (filter) {
            "pending" -> q.status == 1
            "approved" -> q.status == 2
            "processing" -> q.status == 2 && st == 3
            "available" -> st == 5 || st == 4
            "unavailable" -> q.status == 2 && st != 5
            "failed" -> q.status == 4
            "completed" -> st == 5
            else -> true
        }
    }

    /** Approving (or an admin creating) a request sends it to Radarr, which starts downloading it. */
    private fun fulfil(w: World, q: DReq) {
        if (q.type != "movie" || q.is4k) return
        val m = w.catalogMovie(q.key) ?: return
        val lm = DemoArr.addToRadarr(w, m)
        DemoArr.grabMovie(w, lm)
    }

    private fun count(w: World): JsonObject {
        val r = w.seerr.requests
        return obj(
            "total" to r.size,
            "movie" to r.count { it.type == "movie" },
            "tv" to r.count { it.type == "tv" },
            "pending" to r.count { it.status == 1 },
            "approved" to r.count { it.status == 2 },
            "declined" to r.count { it.status == 3 },
            "processing" to r.count { matches(w, it, "processing") },
            "available" to r.count { matches(w, it, "available") },
            "completed" to r.count { matches(w, it, "completed") },
        )
    }

    /* ------------------------------------------------------------ detail */

    private fun detail(w: World, t: Title): JsonObject {
        val media = mediaJson(w, t, withRequests = true)
        if (t.type == "movie") {
            val m = w.catalogMovie(t.key)!!
            return obj(
                "id" to m.tmdbId, "title" to m.title, "originalTitle" to m.title, "imdbId" to m.imdbId,
                "posterPath" to "/${m.key}.jpg", "backdropPath" to "/${m.key}-bg.jpg", "releaseDate" to m.released,
                "voteAverage" to m.rating, "voteCount" to m.votes / 10, "overview" to m.overview, "tagline" to m.tagline,
                "runtime" to m.runtime, "status" to "Released", "originalLanguage" to "en", "adult" to false,
                "genres" to m.genres.map { obj("id" to GENRES[it], "name" to it) },
                "productionCompanies" to listOf(obj("id" to 1, "name" to m.studio)),
                "credits" to obj(
                    "cast" to m.cast.mapIndexed { i, c -> obj("id" to 5000 + i, "castId" to i, "name" to c.name, "character" to c.character, "order" to i, "profilePath" to null) },
                    "crew" to listOfNotNull(m.director?.let { obj("id" to 4000, "name" to it, "job" to "Director", "department" to "Directing") }),
                ),
                "externalIds" to obj("imdbId" to m.imdbId),
                "mediaInfo" to media,
            )
        }
        val s = w.catalogSeries(t.key)!!
        val airs = w.airTimes(s)
        return obj(
            "id" to s.tmdbId, "name" to s.title, "originalName" to s.title,
            "posterPath" to "/${s.key}.jpg", "backdropPath" to "/${s.key}-bg.jpg",
            "firstAirDate" to t.date, "lastAirDate" to airs.values.maxOrNull()?.atZone(w.zone)?.toLocalDate()?.toString(),
            "voteAverage" to s.rating, "voteCount" to s.votes / 10, "overview" to s.overview,
            "episodeRunTime" to listOf(s.runtime), "status" to if (s.status == "ended") "Ended" else "Returning Series",
            "inProduction" to (s.status != "ended"), "numberOfSeasons" to 1, "numberOfEpisodes" to s.episodes.size,
            "genres" to s.genres.map { obj("id" to GENRES[it], "name" to it) },
            "networks" to listOf(obj("id" to 1, "name" to s.network)),
            "productionCompanies" to listOf(obj("id" to 1, "name" to s.network)),
            "credits" to obj(
                "cast" to s.cast.mapIndexed { i, c -> obj("id" to 6000 + i, "name" to c.name, "character" to c.character, "order" to i, "profilePath" to null) },
                "crew" to emptyList<Any>(),
            ),
            "externalIds" to obj("imdbId" to null),
            "seasons" to listOf(obj("id" to 1, "seasonNumber" to 1, "name" to "Season 1", "episodeCount" to s.episodes.size, "airDate" to t.date)),
            "mediaInfo" to media,
        )
    }

    /* ------------------------------------------------------------ routes */

    fun handle(w: World, r: DemoRequest): DemoResponse? {
        val p = r.path
        val pg = r.q("page")?.toIntOrNull() ?: 1
        val all = titles(w)
        return when {
            p == "/api/v1/settings/about" -> {
                val o = DemoAssets.json("jellyseerr/settings-about.json") as JsonObject
                DemoResponse.json(JsonObject(o + mapOf("totalRequests" to j(w.seerr.requests.size), "totalMediaItems" to j(w.movies.size + w.series.size))))
            }
            p == "/api/v1/status" -> DemoResponse.json(obj("version" to "2.7.3", "commitTag" to "v2.7.3", "updateAvailable" to false, "commitsBehind" to 0))
            p == "/api/v1/auth/me" -> DemoResponse.json(userJson(1)!!)
            p == "/api/v1/request/count" -> DemoResponse.json(count(w))
            p == "/api/v1/request" && r.method == "POST" -> create(w, r)
            p == "/api/v1/request" -> {
                val filter = r.q("filter") ?: "all"
                val take = r.q("take")?.toIntOrNull() ?: 20
                val skip = r.q("skip")?.toIntOrNull() ?: 0
                val list = w.seerr.requests.filter { matches(w, it, filter) }.sortedByDescending { it.createdAt }
                DemoResponse.json(obj(
                    "pageInfo" to obj("pages" to (list.size + take - 1) / take.coerceAtLeast(1), "pageSize" to take, "results" to list.size, "page" to skip / take.coerceAtLeast(1) + 1),
                    "results" to list.drop(skip).take(take).map { requestJson(w, it) },
                ))
            }
            p.startsWith("/api/v1/request/") -> act(w, r)
            p == "/api/v1/discover/trending" -> DemoResponse.json(page(w, all.sortedByDescending { it.popularity }, pg))
            p == "/api/v1/discover/movies" -> DemoResponse.json(page(w, all.filter { it.type == "movie" }.sortedByDescending { it.rating * 10 + it.popularity / 10 }, pg))
            p == "/api/v1/discover/movies/upcoming" -> DemoResponse.json(page(w, all.filter { it.type == "movie" && it.year >= 2015 }.sortedByDescending { it.date }, pg))
            p == "/api/v1/discover/tv" -> DemoResponse.json(page(w, all.filter { it.type == "tv" }.sortedByDescending { it.rating }, pg))
            p == "/api/v1/discover/tv/upcoming" -> DemoResponse.json(page(w, all.filter { it.type == "tv" && w.catalogSeries(it.key)?.live == true }, pg))
            p == "/api/v1/search" -> {
                val q = r.q("query").orEmpty().trim().lowercase()
                val hits = if (q.isEmpty()) emptyList() else all.filter { t ->
                    t.name.lowercase().contains(q) || q.split(' ').filter { it.length > 2 }.let { words -> words.isNotEmpty() && words.all { t.name.lowercase().contains(it) } } ||
                        (w.catalogMovie(t.key)?.cast?.any { it.name.lowercase().contains(q) } == true) ||
                        (w.catalogMovie(t.key)?.director?.lowercase()?.contains(q) == true)
                }
                DemoResponse.json(page(w, hits.sortedByDescending { it.popularity }, pg))
            }
            p == "/api/v1/service/radarr" -> DemoResponse.json(DemoAssets.json("jellyseerr/service-radarr.json"))
            p == "/api/v1/service/sonarr" -> DemoResponse.json(DemoAssets.json("jellyseerr/service-sonarr.json"))
            p.startsWith("/api/v1/service/") -> {
                val arr = if (p.contains("radarr")) "radarr" else "sonarr"
                val server = (DemoAssets.json("jellyseerr/service-$arr.json") as JsonArray).first()
                DemoResponse.json(obj("server" to server, "profiles" to DemoAssets.json("$arr/qualityprofile.json"), "rootFolders" to DemoAssets.json("$arr/rootfolder.json"), "tags" to emptyList<Any>()))
            }
            else -> media(w, r, all)
        }
    }

    private fun media(w: World, r: DemoRequest, all: List<Title>): DemoResponse? {
        val m = Regex("^/api/v1/(movie|tv)/(\\d+)(?:/(\\w+))?$").find(r.path) ?: return null
        val type = m.groupValues[1]
        val id = m.groupValues[2].toInt()
        val t = byTmdb(w, type, id) ?: return DemoResponse.notFound("Unable to retrieve ${if (type == "movie") "movie" else "series"}.")
        return when (m.groupValues[3]) {
            "" -> DemoResponse.json(detail(w, t))
            "recommendations", "similar" -> {
                val salt = m.groupValues[3]
                val others = all.filter { it.key != t.key && (it.type == type || salt == "recommendations") }
                    .sortedBy { rand(salt + t.key + it.key) }.take(if (salt == "similar") 8 else 10)
                DemoResponse.json(page(w, others))
            }
            "ratingscombined" -> {
                val c = w.catalogMovie(t.key) ?: return DemoResponse.notFound()
                DemoResponse.json(obj(
                    "rt" to c.rt?.let { obj("title" to c.title, "year" to c.year, "url" to "", "criticsScore" to it, "criticsRating" to if (it >= 60) "Certified Fresh" else "Rotten", "audienceScore" to c.rtAudience, "audienceRating" to "Upright") },
                    "imdb" to c.imdbId?.let { obj("title" to c.title, "url" to "https://www.imdb.com/title/$it", "criticsScore" to c.rating) },
                ))
            }
            "ratings" -> {
                val c = w.catalogMovie(t.key)
                if (c?.rt == null) DemoResponse.notFound("Rotten Tomatoes ratings not found")
                else DemoResponse.json(obj("title" to c.title, "year" to c.year, "url" to "", "criticsScore" to c.rt, "criticsRating" to "Certified Fresh", "audienceScore" to c.rtAudience, "audienceRating" to "Upright"))
            }
            else -> null
        }
    }

    private fun create(w: World, r: DemoRequest): DemoResponse {
        val o = r.json as? JsonObject ?: return DemoResponse.json(obj("message" to "Invalid request"), 400)
        val type = o["mediaType"].str() ?: "movie"
        val id = o["mediaId"].int() ?: 0
        val is4k = o["is4k"].bool() ?: false
        val t = byTmdb(w, type, id) ?: return DemoResponse.notFound("Media not found")
        if (w.seerr.requests.any { it.key == t.key && it.is4k == is4k && it.status in 1..2 }) return DemoResponse.json(obj("message" to "Request for this media already exists."), 409)
        val seasons = (o["seasons"] as? JsonArray)?.mapNotNull { it.int() } ?: if (type == "tv") listOf(1) else emptyList()
        // The API key is the admin's: Seerr approves admin requests straight away.
        val q = DReq(w.seerr.nextId++, t.key, type, 2, is4k, 1, Instant.now(), Instant.now(), seasons, 1)
        w.seerr.requests.add(0, q)
        fulfil(w, q)
        return DemoResponse.json(requestJson(w, q), 201)
    }

    private fun act(w: World, r: DemoRequest): DemoResponse {
        val m = Regex("^/api/v1/request/(\\d+)(?:/(\\w+))?$").find(r.path) ?: return DemoResponse.notFound()
        val q = w.seerr.requests.firstOrNull { it.id == m.groupValues[1].toInt() } ?: return DemoResponse.notFound("Request not found.")
        when (m.groupValues[2]) {
            "" -> if (r.method == "DELETE") { w.seerr.requests.remove(q); return DemoResponse.empty(204) } else return DemoResponse.json(requestJson(w, q))
            "approve" -> { q.status = 2; q.modifiedBy = 1; fulfil(w, q) }
            "decline" -> { q.status = 3; q.modifiedBy = 1 }
            "retry" -> { q.status = 2; q.modifiedBy = 1; fulfil(w, q) }
            else -> return DemoResponse.notFound()
        }
        q.updatedAt = Instant.now()
        return DemoResponse.json(requestJson(w, q))
    }
}
