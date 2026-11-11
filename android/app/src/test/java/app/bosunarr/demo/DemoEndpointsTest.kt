package app.bosunarr.demo

import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.feature.activity.qbit.TorrentFile
import app.bosunarr.feature.activity.qbit.TorrentProperties
import app.bosunarr.feature.activity.qbit.TorrentTracker
import app.bosunarr.feature.calendar.CalEpisode
import app.bosunarr.feature.dashboard.ArrHealthCheck
import app.bosunarr.feature.dashboard.DiskSpace
import app.bosunarr.feature.dashboard.SeerrCount
import app.bosunarr.feature.discover.ArrServer
import app.bosunarr.feature.discover.ArrServerDetail
import app.bosunarr.feature.discover.CombinedRatings
import app.bosunarr.feature.discover.SeerrDetail
import app.bosunarr.feature.discover.SeerrPage
import app.bosunarr.feature.discover.SeerrRequestPage
import app.bosunarr.feature.discover.SeerrUser
import app.bosunarr.feature.library.ArrEpisode
import app.bosunarr.feature.library.ArrEpisodeFile
import app.bosunarr.feature.library.ArrMovie
import app.bosunarr.feature.library.ArrProfile
import app.bosunarr.feature.library.ArrRootFolder
import app.bosunarr.feature.library.ArrSeries
import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Runs every endpoint the app's features call (see demo/ENDPOINTS.md) through the real [ServiceClient] against the
 * demo interceptor, and decodes the answers with the app's own models where they're public.
 */
class DemoEndpointsTest {
    private lateinit var client: ServiceClient
    private lateinit var http: OkHttpClient
    private val svc = Demo.services.associateBy { it.serviceType!! }
    private fun s(t: ServiceType): ServiceConfig = svc.getValue(t)
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    @Before
    fun setUp() {
        DemoAssets.loader = { path -> File("src/main/assets/demo/$path").takeIf { it.exists() }?.readBytes() }
        DemoWorld.reset()
        http = OkHttpClient.Builder().addInterceptor(DemoInterceptor().apply { latency = false }).build()
        client = ServiceClient(http)
    }

    private fun json(t: ServiceType, path: String) = runBlocking { client.json(s(t), path) }
    private fun arr(t: ServiceType, path: String) = json(t, path) as JsonArray
    private fun objAt(t: ServiceType, path: String) = json(t, path) as JsonObject

    @Test
    fun everyServicePassesItsConnectionTest(): Unit = runBlocking {
        for (s in Demo.services) {
            val r = client.test(s)
            println("${s.name}: ${r.version}")
        }
    }

    @Test
    fun sonarr(): Unit = runBlocking {
        val s = s(ServiceType.Sonarr)
        val series: List<ArrSeries> = client.get(s, "/api/v3/series")
        assertEquals(3, series.size)
        assertTrue(series.all { it.statistics != null && it.images.isNotEmpty() })
        val sherlock = series.first { it.title == "Sherlock Holmes" }
        assertNotNull(sherlock.nextAiring)
        val eps: List<ArrEpisode> = client.get(s, "/api/v3/episode?seriesId=${sherlock.id}")
        assertEquals(26, eps.size)
        val files: List<ArrEpisodeFile> = client.get(s, "/api/v3/episodefile?seriesId=${sherlock.id}")
        assertTrue(files.isNotEmpty())
        val one: ArrSeries = client.get(s, "/api/v3/series/${sherlock.id}")
        assertEquals(sherlock.title, one.title)
        val profiles: List<ArrProfile> = client.get(s, "/api/v3/qualityprofile")
        assertTrue(profiles.isNotEmpty())
        val roots: List<ArrRootFolder> = client.get(s, "/api/v3/rootfolder")
        assertTrue(roots.isNotEmpty())
        arr(ServiceType.Sonarr, "/api/v3/languageprofile")
        val now = Instant.now()
        val cal: List<CalEpisode> = client.get(s, "/api/v3/calendar?start=${enc(now.minus(30, ChronoUnit.DAYS).toString())}&end=${enc(now.plus(28, ChronoUnit.DAYS).toString())}&unmonitored=true&includeSeries=true")
        assertTrue("calendar has upcoming episodes", cal.count { Instant.parse(it.airDateUtc!!) > now } >= 4)
        assertTrue(cal.all { it.series != null })
        val upNext = arr(ServiceType.Sonarr, "/api/v3/calendar?start=${enc(now.toString())}&end=${enc(now.plus(14, ChronoUnit.DAYS).toString())}&includeSeries=true")
        assertTrue(upNext.isNotEmpty())
        val queue = objAt(ServiceType.Sonarr, "/api/v3/queue?page=1&pageSize=1000&includeSeries=true&includeEpisode=true")
        assertTrue((queue["records"] as JsonArray).isNotEmpty())
        val hist = objAt(ServiceType.Sonarr, "/api/v3/history?page=1&pageSize=50&sortKey=date&sortDirection=descending&includeSeries=true&includeEpisode=true&eventType=3&eventType=2")
        assertTrue((hist["records"] as JsonArray).isNotEmpty())
        objAt(ServiceType.Sonarr, "/api/v3/history?page=1&pageSize=30&sortKey=date&sortDirection=descending&includeSeries=true&includeEpisode=true&eventType=4")
        val health: List<ArrHealthCheck> = client.get(s, "/api/v3/health")
        val disks: List<DiskSpace> = client.get(s, "/api/v3/diskspace")
        assertTrue(disks.isNotEmpty()); println("sonarr health ${health.size}")
        objAt(ServiceType.Sonarr, "/api/v3/system/status")
        objAt(ServiceType.Sonarr, "/api/v3/queue/status")
        arr(ServiceType.Sonarr, "/api/v3/command")
        objAt(ServiceType.Sonarr, "/api/v3/wanted/missing?page=1&pageSize=1&monitored=true")
        objAt(ServiceType.Sonarr, "/api/v3/wanted/cutoff?page=1&pageSize=1&monitored=true")
        assertEquals(1, arr(ServiceType.Sonarr, "/api/v3/series/lookup?term=sherlock").size)

        // Writes: monitor toggles, a season, episodes, a search command.
        val full = objAt(ServiceType.Sonarr, "/api/v3/series/${sherlock.id}")
        client.send(s, "/api/v3/series/${sherlock.id}", "PUT", JsonObject(full + ("monitored" to kotlinx.serialization.json.JsonPrimitive(false))))
        assertEquals(false, client.get<ArrSeries>(s, "/api/v3/series/${sherlock.id}").monitored)
        client.send(s, "/api/v3/episode/monitor", "PUT", buildJsonObject { putJsonArray("episodeIds") { add(eps[0].id) }; put("monitored", false) })
        assertEquals(false, client.get<List<ArrEpisode>>(s, "/api/v3/episode?seriesId=${sherlock.id}")[0].monitored)
        val missing = eps.first { !it.hasFile && Instant.parse(it.airDateUtc!!) < now }
        client.send(s, "/api/v3/command", "POST", buildJsonObject { put("name", "EpisodeSearch"); putJsonArray("episodeIds") { add(missing.id) } })
        val q2 = objAt(ServiceType.Sonarr, "/api/v3/queue?page=1&pageSize=1000&includeSeries=true&includeEpisode=true")
        assertEquals((queue["records"] as JsonArray).size + 1, (q2["records"] as JsonArray).size)
    }

    @Test
    fun radarr(): Unit = runBlocking {
        val s = s(ServiceType.Radarr)
        val movies: List<ArrMovie> = client.get(s, "/api/v3/movie")
        assertTrue(movies.size >= 18)
        assertTrue(movies.any { !it.isAvailable })
        val m: ArrMovie = client.get(s, "/api/v3/movie/${movies[0].id}")
        assertNotNull(m.movieFile)
        val now = Instant.now()
        val cal: List<ArrMovie> = client.get(s, "/api/v3/calendar?start=${enc(now.minus(31, ChronoUnit.DAYS).toString())}&end=${enc(now.plus(29, ChronoUnit.DAYS).toString())}&unmonitored=true")
        assertTrue("movie releases in the calendar", cal.size >= 4)
        val queue = objAt(ServiceType.Radarr, "/api/v3/queue?page=1&pageSize=1000&includeMovie=true")
        val records = queue["records"] as JsonArray
        assertTrue(records.size >= 3)
        objAt(ServiceType.Radarr, "/api/v3/history?page=1&pageSize=30&sortKey=date&sortDirection=descending&includeMovie=true")
        objAt(ServiceType.Radarr, "/api/v3/queue/status")
        // Lookup a title that isn't in the library and add it with a search: it shows up in the queue.
        val found = arr(ServiceType.Radarr, "/api/v3/movie/lookup?term=${enc("little shop")}")
        assertEquals(1, found.size)
        val body = JsonObject((found[0] as JsonObject) + mapOf(
            "qualityProfileId" to kotlinx.serialization.json.JsonPrimitive(4),
            "rootFolderPath" to kotlinx.serialization.json.JsonPrimitive("/data/media/movies"),
            "addOptions" to buildJsonObject { put("searchForMovie", true) },
        ))
        val created = AppJson.parseToJsonElement(client.send(s, "/api/v3/movie", "POST", body)) as JsonObject
        assertNotNull(created["id"])
        val q2 = objAt(ServiceType.Radarr, "/api/v3/queue?page=1&pageSize=1000&includeMovie=true")
        assertEquals(records.size + 1, (q2["records"] as JsonArray).size)
        // Remove a queue item.
        val qid = ((q2["records"] as JsonArray)[0] as JsonObject)["id"].int()
        client.send(s, "/api/v3/queue/$qid?removeFromClient=true&blocklist=false", "DELETE")
        client.send(s, "/api/v3/movie/${movies.last().id}?deleteFiles=false&addImportExclusion=false", "DELETE")
        assertEquals(movies.size, client.get<List<ArrMovie>>(s, "/api/v3/movie").size) // +1 added, -1 deleted
    }

    @Test
    fun qbittorrent(): Unit = runBlocking {
        val s = s(ServiceType.QBittorrent)
        val md = objAt(ServiceType.QBittorrent, "/api/v2/sync/maindata?rid=0")
        val torrents = md["torrents"] as JsonObject
        assertTrue(torrents.size >= 10)
        val hash = torrents.keys.first()
        client.get<TorrentProperties>(s, "/api/v2/torrents/properties?hash=$hash")
        assertTrue(client.get<List<TorrentTracker>>(s, "/api/v2/torrents/trackers?hash=$hash").isNotEmpty())
        assertTrue(client.get<List<TorrentFile>>(s, "/api/v2/torrents/files?hash=$hash").isNotEmpty())
        objAt(ServiceType.QBittorrent, "/api/v2/transfer/info")
        assertTrue(arr(ServiceType.QBittorrent, "/api/v2/torrents/info?filter=active").isNotEmpty())
        objAt(ServiceType.QBittorrent, "/api/v2/torrents/categories")
        client.form(s, "/api/v2/torrents/stop", mapOf("hashes" to hash))
        val st = ((objAt(ServiceType.QBittorrent, "/api/v2/sync/maindata?rid=1")["torrents"] as JsonObject)[hash] as JsonObject)["state"].str()
        assertTrue(st!!.startsWith("stopped"))
        client.form(s, "/api/v2/torrents/start", mapOf("hashes" to hash))
        client.form(s, "/api/v2/transfer/setSpeedLimitsMode", mapOf("mode" to "1"))
        assertEquals("1", client.text(s, "/api/v2/transfer/speedLimitsMode"))
        assertEquals("Ok.", client.form(s, "/api/v2/torrents/add", mapOf("urls" to "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Night.of.the.Living.Dead.1968")))
        assertEquals(torrents.size + 1, (objAt(ServiceType.QBittorrent, "/api/v2/sync/maindata?rid=2")["torrents"] as JsonObject).size)
        client.form(s, "/api/v2/torrents/delete", mapOf("hashes" to hash, "deleteFiles" to "false"))
        client.form(s, "/api/v2/torrents/setCategory", mapOf("hashes" to "all", "category" to "radarr"))
        client.form(s, "/api/v2/torrents/filePrio", mapOf("hash" to torrents.keys.last(), "id" to "0", "priority" to "7"))
    }

    @Test
    fun jellyseerr(): Unit = runBlocking {
        val s = s(ServiceType.Jellyseerr)
        for (p in listOf("/api/v1/discover/trending", "/api/v1/discover/movies", "/api/v1/discover/movies/upcoming", "/api/v1/discover/tv", "/api/v1/discover/tv/upcoming")) {
            assertTrue(p, client.get<SeerrPage>(s, "$p?page=1").results.isNotEmpty())
        }
        val search: SeerrPage = client.get(s, "/api/v1/search?query=night&page=1")
        assertTrue(search.results.isNotEmpty())
        val movie: SeerrDetail = client.get(s, "/api/v1/movie/19")
        assertEquals("Metropolis", movie.title)
        assertTrue(movie.credits.cast.isNotEmpty())
        val tv: SeerrDetail = client.get(s, "/api/v1/tv/99100001")
        assertEquals("Sherlock Holmes", tv.name)
        client.get<SeerrPage>(s, "/api/v1/movie/19/recommendations?page=1")
        client.get<SeerrPage>(s, "/api/v1/tv/99100001/similar?page=1")
        assertNotNull(client.get<CombinedRatings>(s, "/api/v1/movie/19/ratingscombined").rt)
        assertTrue(client.get<SeerrUser>(s, "/api/v1/auth/me").permissions and 2L != 0L)
        val count: SeerrCount = client.get(s, "/api/v1/request/count")
        assertEquals(2, count.pending)
        val pending: SeerrRequestPage = client.get(s, "/api/v1/request?take=20&skip=0&filter=pending&sort=added")
        assertEquals(2, pending.results.size)
        client.get<SeerrRequestPage>(s, "/api/v1/request?take=20&skip=0&filter=all&sort=added")
        assertTrue(client.get<List<ArrServer>>(s, "/api/v1/service/radarr").isNotEmpty())
        assertTrue(client.get<ArrServerDetail>(s, "/api/v1/service/radarr/0").profiles.isNotEmpty())
        client.get<ArrServerDetail>(s, "/api/v1/service/sonarr/0")
        objAt(ServiceType.Jellyseerr, "/api/v1/settings/about")
        // Approve the pending requests: they go to Radarr, which starts downloading.
        val before = (objAt(ServiceType.Radarr, "/api/v3/queue?page=1&pageSize=1000&includeMovie=true")["records"] as JsonArray).size
        pending.results.forEach { client.send(s, "/api/v1/request/${it.id}/approve", "POST") }
        assertEquals(0, client.get<SeerrCount>(s, "/api/v1/request/count").pending)
        val after = (objAt(ServiceType.Radarr, "/api/v3/queue?page=1&pageSize=1000&includeMovie=true")["records"] as JsonArray).size
        assertEquals(before + 2, after)
        // A new request (admin: auto-approved).
        client.send(s, "/api/v1/request", "POST", buildJsonObject { put("mediaType", "movie"); put("mediaId", 99000010); put("is4k", false) })
        val all: SeerrRequestPage = client.get(s, "/api/v1/request?take=50&skip=0&filter=all&sort=added")
        client.send(s, "/api/v1/request/${all.results.last().id}/decline", "POST")
        client.send(s, "/api/v1/request/${all.results.last().id}", "DELETE")
    }

    @Test
    fun otherServices(): Unit = runBlocking {
        // Prowlarr
        assertEquals(6, arr(ServiceType.Prowlarr, "/api/v1/indexer").size)
        arr(ServiceType.Prowlarr, "/api/v1/indexerstatus")
        arr(ServiceType.Prowlarr, "/api/v1/health")
        objAt(ServiceType.Prowlarr, "/api/v1/indexerstats?startDate=x&endDate=y")
        assertTrue(arr(ServiceType.Prowlarr, "/api/v1/search?query=metropolis&type=search&limit=100&offset=0&categories=2000").isNotEmpty())
        // Bazarr
        val b = s(ServiceType.Bazarr)
        objAt(ServiceType.Bazarr, "/api/system/status")
        val badges = objAt(ServiceType.Bazarr, "/api/badges")
        assertTrue(badges["episodes"].int()!! > 0 && badges["movies"].int()!! > 0)
        objAt(ServiceType.Bazarr, "/api/system/health")
        objAt(ServiceType.Bazarr, "/api/providers")
        val wanted = objAt(ServiceType.Bazarr, "/api/movies/wanted?start=0&length=50")["data"] as JsonArray
        assertTrue(wanted.isNotEmpty())
        objAt(ServiceType.Bazarr, "/api/episodes/wanted?start=0&length=50")
        assertTrue((objAt(ServiceType.Bazarr, "/api/episodes/history?start=0&length=40")["data"] as JsonArray).isNotEmpty())
        objAt(ServiceType.Bazarr, "/api/movies/history?start=0&length=40")
        val first = wanted[0] as JsonObject
        val req = Request.Builder().url(b.url + "/api/movies/subtitles?radarrid=${first["radarrId"].int()}&language=en&forced=false&hi=false")
            .method("PATCH", ByteArray(0).toRequestBody(null)).build()
        assertEquals(204, http.newCall(req).execute().code)
        client.send(b, "/api/system/tasks?taskid=wanted_search_missing_subtitles_series", "POST")
        client.send(b, "/api/providers?action=reset", "POST")
        // Jellyfin
        val jf = s(ServiceType.Jellyfin)
        objAt(ServiceType.Jellyfin, "/System/Info")
        objAt(ServiceType.Jellyfin, "/Items/Counts")
        val folders = arr(ServiceType.Jellyfin, "/Library/VirtualFolders")
        val lib = (folders[0] as JsonObject)["ItemId"].str()
        assertTrue(objAt(ServiceType.Jellyfin, "/Items?ParentId=$lib&Recursive=true&Limit=0&EnableTotalRecordCount=true&IncludeItemTypes=Movie")["TotalRecordCount"].int()!! > 0)
        val users = arr(ServiceType.Jellyfin, "/Users")
        val uid = (users[0] as JsonObject)["Id"].str()
        val latest = arr(ServiceType.Jellyfin, "/Items/Latest?userId=$uid&Limit=24&Fields=DateCreated&EnableImageTypes=Primary&GroupItems=true")
        assertTrue(latest.size >= 10)
        val sessions = arr(ServiceType.Jellyfin, "/Sessions?activeWithinSeconds=960")
        assertTrue(sessions.count { (it as JsonObject)["NowPlayingItem"] != null } >= 3)
        client.send(jf, "/Library/Refresh", "POST")
        val img = http.newCall(Request.Builder().url(jf.url + "/Items/${((latest[0] as JsonObject)["Id"]).str()}/Images/Primary?maxHeight=300").build()).execute()
        assertEquals(200, img.code)
        // Jellystat
        val js = s(ServiceType.Jellystat)
        arr(ServiceType.Jellystat, "/api/getLibraries")
        assertEquals(90, (objAt(ServiceType.Jellystat, "/stats/getViewsOverTime?days=90")["stats"] as JsonArray).size)
        arr(ServiceType.Jellystat, "/stats/getAllUserActivity")
        assertTrue((AppJson.parseToJsonElement(client.send(js, "/stats/getMostViewedByType", "POST", buildJsonObject { put("days", 30); put("type", "Movie") })) as JsonArray).isNotEmpty())
        assertTrue((AppJson.parseToJsonElement(client.send(js, "/stats/getMostViewedByType", "POST", buildJsonObject { put("days", 7); put("type", "Series") })) as JsonArray).isNotEmpty())
        assertTrue((AppJson.parseToJsonElement(client.send(js, "/stats/getMostActiveUsers", "POST", buildJsonObject { put("days", 30) })) as JsonArray).isNotEmpty())
        assertEquals(25, (objAt(ServiceType.Jellystat, "/api/getHistory?page=1&size=25")["results"] as JsonArray).size)
        arr(ServiceType.Jellystat, "/proxy/getSessions")
        // Wizarr
        val wz = s(ServiceType.Wizarr)
        objAt(ServiceType.Wizarr, "/api/status")
        objAt(ServiceType.Wizarr, "/api/users")
        val inv = (objAt(ServiceType.Wizarr, "/api/invitations")["invitations"] as JsonArray).size
        objAt(ServiceType.Wizarr, "/api/libraries")
        objAt(ServiceType.Wizarr, "/api/servers")
        val created = AppJson.parseToJsonElement(client.send(wz, "/api/invitations", "POST", buildJsonObject {
            putJsonArray("server_ids") { add(1) }; put("expires_in_days", 7); put("duration", "unlimited"); put("unlimited", false)
        })) as JsonObject
        val id = (created["invitation"] as JsonObject)["id"].int()
        assertEquals(inv + 1, (objAt(ServiceType.Wizarr, "/api/invitations")["invitations"] as JsonArray).size)
        client.send(wz, "/api/invitations/$id", "DELETE")
        assertEquals(inv, (objAt(ServiceType.Wizarr, "/api/invitations")["invitations"] as JsonArray).size)
    }

    @Test
    fun imagesAreServed() {
        fun get(t: ServiceType, path: String) = http.newCall(Request.Builder().url(s(t).url + path).build()).execute().use { it.code to it.header("Content-Type") }
        assertEquals(200 to "image/webp", get(ServiceType.Radarr, "/MediaCover/1/poster-500.jpg"))
        assertEquals(200, get(ServiceType.Radarr, "/MediaCover/1/fanart.jpg").first)
        assertEquals(200, get(ServiceType.Sonarr, "/MediaCover/1/poster-250.jpg").first)
        assertEquals(200, get(ServiceType.Radarr, "/MediaCover/lookup/sita-sings-the-blues/poster-250.jpg").first)
        assertEquals(200, get(ServiceType.Jellyseerr, "/imageproxy/tmdb/t/p/w342/metropolis.jpg").first)
        assertEquals(200, get(ServiceType.Jellyseerr, "/imageproxy/tmdb/t/p/w1280/metropolis-bg.jpg").first)
        // Every catalog title has a poster.
        val cat = DemoWorld.get().cat
        (cat.movies.map { it.key } + cat.series.map { it.key }).forEach { k ->
            assertTrue("poster for $k", File("src/main/assets/demo/images/$k-poster.webp").exists())
        }
    }
}
