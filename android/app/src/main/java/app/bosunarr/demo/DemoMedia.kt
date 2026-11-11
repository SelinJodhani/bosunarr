package app.bosunarr.demo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the demo Jellyfin holds: everything the arrs have on disk. */
internal object DemoLibrary {
    const val TICKS = 10_000_000L

    fun movieId(key: String) = hexId("jf:$key")
    fun seriesId(key: String) = hexId("jf:$key")
    fun episodeId(seriesKey: String, n: Int) = hexId("jf:ep:$seriesKey:$n")
    fun userId(key: String) = hexId("user:$key")
    fun libraryId(name: String) = hexId("lib:$name")

    /** Jellyfin item id → catalog key whose poster it shows. */
    fun imageKey(w: World, id: String): String? {
        w.cat.movies.firstOrNull { movieId(it.key) == id }?.let { return it.key }
        w.cat.series.firstOrNull { seriesId(it.key) == id }?.let { return it.key }
        return w.cat.series.firstOrNull { s -> s.episodes.any { episodeId(s.key, it.n) == id } }?.key
    }

    fun library(m: DMovie) = if (m.studio.contains("Blender") || m.key == "sita-sings-the-blues") "Movies" else "Classics"

    fun movies(w: World) = w.movies.filter { it.hasFile }
    fun episodes(w: World) = w.series.flatMap { s -> s.episodes.filter { it.hasFile }.map { s to it } }

    fun movieItem(lm: LibMovie) = obj(
        "Name" to lm.m.title, "ServerId" to "b3d1f0c2a5e94f3c8d7e6a1b2c3d4e5f", "Id" to movieId(lm.m.key), "Type" to "Movie",
        "ProductionYear" to lm.m.year, "DateCreated" to (lm.fileAdded ?: lm.added), "RunTimeTicks" to lm.m.runtime * 60 * TICKS,
        "CommunityRating" to lm.m.rating, "OfficialRating" to lm.m.certification, "IsFolder" to false, "MediaType" to "Video",
        "ImageTags" to mapOf("Primary" to hexId("img:" + lm.m.key).take(8)),
        "BackdropImageTags" to listOf(hexId("bd:" + lm.m.key).take(8)),
    )

    fun episodeItem(s: LibSeries, e: LibEpisode) = obj(
        "Name" to e.title, "ServerId" to "b3d1f0c2a5e94f3c8d7e6a1b2c3d4e5f", "Id" to episodeId(s.s.key, e.n), "Type" to "Episode",
        "SeriesName" to s.s.title, "SeriesId" to seriesId(s.s.key), "SeasonName" to "Season 1",
        "ParentIndexNumber" to e.season, "IndexNumber" to e.n, "ProductionYear" to s.s.year,
        "DateCreated" to (e.fileAdded ?: e.air), "RunTimeTicks" to s.s.runtime * 60 * TICKS, "IsFolder" to false, "MediaType" to "Video",
        "ImageTags" to emptyMap<String, String>(), "SeriesPrimaryImageTag" to hexId("img:" + s.s.key).take(8),
    )

    fun seriesItem(s: LibSeries, childCount: Int? = null, created: Instant? = null) = obj(
        "Name" to s.s.title, "ServerId" to "b3d1f0c2a5e94f3c8d7e6a1b2c3d4e5f", "Id" to seriesId(s.s.key), "Type" to "Series",
        "ProductionYear" to s.s.year, "DateCreated" to (created ?: s.added), "IsFolder" to true, "ChildCount" to childCount,
        "ImageTags" to mapOf("Primary" to hexId("img:" + s.s.key).take(8)),
    )

    /** Who is watching what right now; positions advance in real time. */
    fun sessions(w: World): JsonArray {
        val now = Instant.now()
        val elapsed = (now.toEpochMilli() - w.started.toEpochMilli()) / 1000.0
        fun user(key: String, name: String) = arrayOf("UserId" to userId(key), "UserName" to name)
        fun playState(runtimeMin: Int, startFrac: Double, paused: Boolean, method: String): JsonObject {
            val run = runtimeMin * 60.0
            val pos = if (paused) startFrac * run else (startFrac * run + elapsed) % run
            return obj("PositionTicks" to (pos * TICKS).toLong(), "CanSeek" to true, "IsPaused" to paused, "IsMuted" to false, "PlayMethod" to method, "RepeatMode" to "RepeatNone")
        }
        val out = mutableListOf<JsonObject>()
        w.movieByKey("charade")?.takeIf { it.hasFile }?.let { lm ->
            out += obj(*user("maya", "Maya"), "Id" to hexId("session:maya"), "Client" to "Jellyfin Android TV", "DeviceName" to "Living Room TV", "DeviceId" to hexId("dev:tv"),
                "ApplicationVersion" to "0.18.11", "LastActivityDate" to now, "IsActive" to true,
                "NowPlayingItem" to movieItem(lm), "PlayState" to playState(lm.m.runtime, 0.38, false, "DirectPlay"))
        }
        w.series.firstOrNull { it.s.live }?.let { s ->
            s.episodes.lastOrNull { it.hasFile }?.let { e ->
                out += obj(*user("theo", "Theo"), "Id" to hexId("session:theo"), "Client" to "Jellyfin Web", "DeviceName" to "Firefox", "DeviceId" to hexId("dev:ff"),
                    "ApplicationVersion" to "10.11.2", "LastActivityDate" to now, "IsActive" to true,
                    "NowPlayingItem" to episodeItem(s, e), "PlayState" to playState(s.s.runtime, 0.21, false, "Transcode"),
                    "TranscodingInfo" to obj("AudioCodec" to "aac", "VideoCodec" to "h264", "Container" to "ts", "IsVideoDirect" to false, "IsAudioDirect" to true,
                        "Bitrate" to 3_820_000, "Width" to 1280, "Height" to 720, "AudioChannels" to 2, "HardwareAccelerationType" to "vaapi",
                        "TranscodeReasons" to listOf("ContainerBitrateExceedsLimit")))
            }
        }
        w.movieByKey("metropolis")?.takeIf { it.hasFile }?.let { lm ->
            out += obj(*user("nana", "Nana"), "Id" to hexId("session:nana"), "Client" to "Swiftfin", "DeviceName" to "Nana's iPad", "DeviceId" to hexId("dev:ipad"),
                "ApplicationVersion" to "1.3.0", "LastActivityDate" to now.minusSeconds(140), "IsActive" to true,
                "NowPlayingItem" to movieItem(lm), "PlayState" to playState(lm.m.runtime, 0.64, true, "DirectStream"),
                "TranscodingInfo" to obj("AudioCodec" to "aac", "VideoCodec" to "h264", "Container" to "mp4", "IsVideoDirect" to true, "IsAudioDirect" to false, "Bitrate" to 6_400_000))
        }
        out += obj(*user("alex", "Alex"), "Id" to hexId("session:alex"), "Client" to "Jellyfin Web", "DeviceName" to "Chrome", "DeviceId" to hexId("dev:chrome"),
            "ApplicationVersion" to "10.11.2", "LastActivityDate" to now.minusSeconds(240), "IsActive" to true, "PlayState" to obj("CanSeek" to false, "IsPaused" to false))
        return JsonArray(out)
    }
}

/** Jellyfin 10.11 API. */
internal object DemoJellyfin {
    private var refreshUntil = 0L

    private fun users(): List<JsonObject> = (DemoAssets.json("jellyfin/users.json") as JsonArray).map { u ->
        val o = u as JsonObject
        val key = o["key"].str()!!
        obj(
            "Name" to o["Name"], "ServerId" to "b3d1f0c2a5e94f3c8d7e6a1b2c3d4e5f", "Id" to DemoLibrary.userId(key), "HasPassword" to true,
            "HasConfiguredPassword" to true, "LastLoginDate" to o["LastActivityDate"], "LastActivityDate" to o["LastActivityDate"],
            "Policy" to obj("IsAdministrator" to (o["admin"].bool() == true), "IsDisabled" to (o["disabled"].bool() == true), "EnableAllFolders" to true),
        )
    }

    private val LIBS = listOf(Triple("Movies", "movies", "/data/media/movies"), Triple("Classics", "movies", "/data/media/classics"), Triple("Shows", "tvshows", "/data/media/tv"))

    fun handle(w: World, r: DemoRequest): DemoResponse? {
        val p = r.path
        return when {
            p == "/System/Info" || p == "/System/Info/Public" -> DemoResponse.json(DemoAssets.json("jellyfin/system-info.json"))
            p == "/Users" -> DemoResponse.json(arr(users()))
            p == "/Items/Counts" -> DemoResponse.json(obj(
                "MovieCount" to DemoLibrary.movies(w).size, "SeriesCount" to w.series.count { s -> s.episodes.any { it.hasFile } },
                "EpisodeCount" to DemoLibrary.episodes(w).size, "ArtistCount" to 0, "ProgramCount" to 0, "TrailerCount" to 0, "SongCount" to 0,
                "AlbumCount" to 0, "MusicVideoCount" to 0, "BoxSetCount" to 0, "BookCount" to 0, "ItemCount" to DemoLibrary.movies(w).size + DemoLibrary.episodes(w).size,
            ))
            p == "/Library/VirtualFolders" -> {
                val now = System.currentTimeMillis()
                val scanning = now < refreshUntil
                DemoResponse.json(arr(LIBS.map { (name, type, path) ->
                    obj("Name" to name, "Locations" to listOf(path), "CollectionType" to type, "ItemId" to DemoLibrary.libraryId(name),
                        "RefreshStatus" to if (scanning) "Active" else "Idle",
                        "RefreshProgress" to if (scanning) (100.0 - (refreshUntil - now) / 100.0).coerceIn(0.0, 99.0) else null)
                }))
            }
            p == "/Library/Refresh" && r.method == "POST" -> { refreshUntil = System.currentTimeMillis() + 10_000; DemoResponse.empty(204) }
            p == "/Items" -> {
                val parent = r.q("ParentId")
                val lib = LIBS.firstOrNull { DemoLibrary.libraryId(it.first) == parent }?.first
                val count = when (lib) {
                    "Movies", "Classics" -> DemoLibrary.movies(w).count { DemoLibrary.library(it.m) == lib }
                    "Shows" -> w.series.count { s -> s.episodes.any { it.hasFile } }
                    else -> DemoLibrary.movies(w).size
                }
                DemoResponse.json(obj("Items" to emptyList<Any>(), "TotalRecordCount" to count, "StartIndex" to 0))
            }
            p == "/Items/Latest" -> {
                val limit = r.q("Limit")?.toIntOrNull() ?: 16
                val movies = DemoLibrary.movies(w).map { (it.fileAdded ?: it.added) to DemoLibrary.movieItem(it) }
                // GroupItems: several new episodes of one series become one series card.
                val eps = DemoLibrary.episodes(w).filter { (_, e) -> e.fileAdded?.isAfter(w.started.minus(days(60.0))) == true }
                    .groupBy { it.first }.map { (s, list) ->
                        val newest = list.maxBy { it.second.fileAdded!! }
                        val recent = list.filter { it.second.fileAdded!!.isAfter(newest.second.fileAdded!!.minus(days(8.0))) }
                        newest.second.fileAdded!! to if (recent.size > 1) DemoLibrary.seriesItem(s, recent.size, newest.second.fileAdded) else DemoLibrary.episodeItem(s, newest.second)
                    }
                DemoResponse.json(arr((movies + eps).sortedByDescending { it.first }.take(limit).map { it.second }))
            }
            p == "/Sessions" -> DemoResponse.json(DemoLibrary.sessions(w))
            p == "/ScheduledTasks" -> DemoResponse.json(JsonArray(emptyList()))
            else -> null
        }
    }
}

/** Jellystat (watch statistics on top of Jellyfin). */
internal object DemoJellystat {
    private val KEY = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US)
    private val USERS = listOf("Maya" to 1.0, "Theo" to 0.72, "Alex" to 0.55, "Nana" to 0.38, "Sam" to 0.12)

    /** Plays per library per day: busier at weekends, a little noise, a gentle upward trend. */
    private fun day(d: LocalDate, lib: String): Pair<Int, Double> {
        val weekend = d.dayOfWeek.value >= 5
        val base = when (lib) { "Movies" -> 1.6; "Classics" -> 2.2; else -> 3.1 } * (if (weekend) 1.7 else 1.0)
        val trend = 0.75 + 0.25 * (90 - java.time.temporal.ChronoUnit.DAYS.between(d, LocalDate.now())).coerceIn(0, 90) / 90.0
        val n = (base * trend * (0.4 + 1.2 * rand("$d$lib"))).toInt()
        val minutes = n * when (lib) { "Movies" -> 14.0; "Classics" -> 71.0; else -> 24.0 } * (0.7 + 0.5 * rand("m$d$lib"))
        return n to minutes
    }

    private fun mostViewed(w: World, type: String, days: Int): List<JsonObject> {
        val scale = days / 90.0
        return if (type == "Series") w.series.map { s ->
            val plays = (s.episodes.count { it.hasFile } * 6 * scale * (0.8 + rand("v" + s.s.key))).toInt().coerceAtLeast(if (days >= 7) 1 else 0)
            obj("Id" to DemoLibrary.seriesId(s.s.key), "Name" to s.s.title, "Plays" to plays, "total_playback_duration" to plays * s.s.runtime * 60 * 0.9)
        }.filter { it["Plays"].int()!! > 0 }.sortedByDescending { it["Plays"].int() }
        else w.movies.filter { it.hasFile }.map { lm ->
            val plays = (lm.m.views * scale * (0.85 + 0.3 * rand("v" + lm.m.key))).toInt()
            obj("Id" to DemoLibrary.movieId(lm.m.key), "Name" to lm.m.title, "Plays" to plays, "total_playback_duration" to plays * lm.m.runtime * 60 * 0.82)
        }.filter { it["Plays"].int()!! > 0 }.sortedByDescending { it["Plays"].int() }
    }

    private fun history(w: World): List<JsonObject> {
        val items = DemoLibrary.movies(w).map { Triple(it.m.title, null as Pair<LibSeries, LibEpisode>?, it.m.runtime) } +
            DemoLibrary.episodes(w).takeLast(12).map { Triple(it.second.title, it, it.first.s.runtime) }
        val devices = listOf("Jellyfin Android TV" to "Living Room TV", "Jellyfin Web" to "Firefox", "Swiftfin" to "Nana's iPad", "Jellyfin Android" to "Pixel 9", "Findroid" to "Galaxy Tab")
        var at = Instant.now().minusSeconds(1_500)
        return (0 until 25).map { i ->
            val (title, ep, runtime) = items[(rand("h$i") * items.size).toInt().coerceIn(0, items.size - 1)]
            val user = USERS[(rand("u$i") * 4.6).toInt().coerceIn(0, USERS.size - 1)].first
            val dev = devices[(rand("d$i") * devices.size).toInt().coerceIn(0, devices.size - 1)]
            val seconds = runtime * 60.0 * (0.35 + 0.65 * rand("p$i"))
            at = at.minusSeconds((1_800 + rand("t$i") * 30_000).toLong())
            obj(
                "Id" to "${4000 - i}",
                "UserId" to DemoLibrary.userId(user.lowercase()),
                "UserName" to user,
                "NowPlayingItemId" to (ep?.let { DemoLibrary.episodeId(it.first.s.key, it.second.n) } ?: hexId("jf:$title")),
                "NowPlayingItemName" to title,
                "EpisodeId" to ep?.let { DemoLibrary.episodeId(it.first.s.key, it.second.n) },
                "SeasonId" to ep?.let { hexId("season:${it.first.s.key}") },
                "SeriesName" to ep?.first?.s?.title,
                "SeasonNumber" to ep?.second?.season,
                "EpisodeNumber" to ep?.second?.n,
                "Client" to dev.first,
                "DeviceName" to dev.second,
                "PlayMethod" to if (rand("pm$i") < 0.75) "DirectPlay" else "Transcode",
                "PlaybackDuration" to seconds.toLong().toString(),
                "ActivityDateInserted" to at,
            )
        }
    }

    fun handle(w: World, r: DemoRequest): DemoResponse? = when (r.path) {
        "/api/getLibraries" -> DemoResponse.json(arr(listOf("Movies" to "movies", "Classics" to "movies", "Shows" to "tvshows").map { (n, t) ->
            obj("Id" to DemoLibrary.libraryId(n), "Name" to n, "CollectionType" to t, "ServerId" to "b3d1f0c2a5e94f3c8d7e6a1b2c3d4e5f", "archived" to false)
        }))
        "/stats/getViewsOverTime" -> {
            val n = r.q("days")?.toIntOrNull() ?: 30
            val today = LocalDate.now()
            val stats = (n - 1 downTo 0).map { back ->
                val d = today.minusDays(back.toLong())
                val parts = listOf("Movies", "Classics", "Shows").map { lib -> val (c, m) = day(d, lib); lib to obj("Library" to lib, "LibraryID" to DemoLibrary.libraryId(lib), "count" to c.toString(), "duration" to "%.0f".format(m)) }
                obj("Key" to d.format(KEY), *parts.toTypedArray())
            }
            DemoResponse.json(obj("stats" to stats, "libraries" to listOf("Movies", "Classics", "Shows").map { obj("Id" to DemoLibrary.libraryId(it), "Name" to it) }))
        }
        "/stats/getAllUserActivity" -> DemoResponse.json(arr(USERS.mapIndexed { i, (u, weight) ->
            obj("UserId" to DemoLibrary.userId(u.lowercase()), "UserName" to u, "TotalPlays" to (180 * weight).toInt().toString(),
                "TotalWatchTime" to (180 * weight * 3_100).toLong().toString(),
                "LastActivityDate" to Instant.now().minusSeconds(if (u == "Sam") 6 * 86_400L else 60L * (i + 1) * (i + 2)))
        }))
        "/stats/getMostViewedByType" -> {
            val o = r.json as? JsonObject
            DemoResponse.json(arr(mostViewed(w, o?.get("type").str() ?: "Movie", o?.get("days").int() ?: 30)))
        }
        "/stats/getMostActiveUsers" -> {
            val days = (r.json as? JsonObject)?.get("days").int() ?: 30
            DemoResponse.json(arr(USERS.map { (u, weight) ->
                val plays = (weight * days * 1.9).toInt()
                obj("UserId" to DemoLibrary.userId(u.lowercase()), "Name" to u, "Plays" to plays.toString(), "total_playback_duration" to (plays * 2_900L).toString())
            }.filter { it["Plays"].str() != "0" }))
        }
        "/api/getHistory" -> DemoResponse.json(obj("current_page" to 1, "pages" to 12, "size" to 25, "results" to history(w)))
        "/proxy/getSessions" -> DemoResponse.json(DemoLibrary.sessions(w))
        else -> null
    }
}
