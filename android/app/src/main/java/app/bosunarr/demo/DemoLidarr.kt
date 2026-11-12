package app.bosunarr.demo

import app.bosunarr.net.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Lidarr v2 API (`/api/v1`) for the demo: a small music library held in memory. The artists are public-domain
 * composers (recordings in the style of the Musopen project's public-domain releases) and Kevin MacLeod, whose
 * music is CC-BY. Album groupings are made up for the demo. No artwork: the app shows its placeholders.
 */
internal object DemoLidarr {
    private class Track(val title: String, val seconds: Int)

    private class Album(
        val id: Int,
        val artistId: Int,
        val title: String,
        val type: String,
        val released: LocalDate,
        var monitored: Boolean,
        val tracks: List<Track>,
        /** How many tracks (from the start) have files. */
        var have: Int,
        val genres: List<String>,
    ) {
        val mbid get() = hexId("lidarr-album-$id-$title").let { "${it.take(8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" }
    }

    private class Artist(
        val id: Int,
        val name: String,
        val sortName: String,
        val type: String,
        val overview: String,
        val genres: List<String>,
        var monitored: Boolean,
        val added: Instant,
        val ended: Boolean,
    ) {
        val mbid get() = hexId("lidarr-artist-$name").let { "${it.take(8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" }
    }

    private class Command(val id: Int, val name: String, val at: Instant)

    private class State(val artists: MutableList<Artist>, val albums: MutableList<Album>) {
        var nextArtist = 40
        var nextAlbum = 400
        var nextCommand = 7100
        val commands = mutableListOf<Command>()
        /** albumId → when the simulated download started (removed from the queue by DELETE). */
        val queue = mutableMapOf<Int, Instant>()
    }

    private fun t(vararg p: Pair<String, Int>) = p.map { Track(it.first, it.second) }

    /** Artists the demo's search knows that aren't in the library yet: name → (type, overview, genres, albums). */
    private val CATALOG = listOf(
        Triple("Scott Joplin", "Person", "American composer and pianist, the \"King of Ragtime\"; his works are in the public domain."),
        Triple("Frédéric Chopin", "Person", "Polish composer and virtuoso pianist of the Romantic period."),
        Triple("Johann Sebastian Bach", "Person", "German composer of the Baroque period."),
    )

    private fun state(w: World): State = w.misc.bag.getOrPut("lidarr") {
        val today = DemoWorld.today()
        val now = Instant.now()
        val artists = mutableListOf(
            Artist(1, "Kevin MacLeod", "MacLeod, Kevin", "Person", "American composer known for thousands of royalty-free pieces released under Creative Commons Attribution, heard in films, games and videos everywhere.", listOf("Soundtrack", "Electronic", "Comedy"), true, now.minus(Duration.ofDays(120)), false),
            Artist(2, "Ludwig van Beethoven", "Beethoven, Ludwig van", "Person", "German composer and pianist whose symphonies and sonatas bridge the Classical and Romantic eras. These recordings are public domain.", listOf("Classical", "Romantic"), true, now.minus(Duration.ofDays(300)), true),
            Artist(3, "Erik Satie", "Satie, Erik", "Person", "French composer and pianist, best known for the Gymnopédies and Gnossiennes.", listOf("Classical", "Minimalism"), true, now.minus(Duration.ofDays(30)), true),
        )
        val albums = mutableListOf(
            Album(11, 1, "Comedy Collection", "Album", LocalDate.of(2014, 3, 1), true, t("Monkeys Spinning Monkeys" to 123, "Sneaky Snitch" to 135, "Fluffing a Duck" to 85, "Scheming Weasel" to 117, "Investigations" to 162, "Carefree" to 207), 6, listOf("Comedy")),
            Album(12, 1, "Calming Collection", "Album", LocalDate.of(2015, 6, 1), true, t("Wallpaper" to 199, "Local Forecast" to 186, "Dreamer" to 251, "Easy Lemon" to 120, "Meditation Impromptu 02" to 230), 3, listOf("Ambient")),
            Album(13, 1, "Cipher", "Single", LocalDate.of(2016, 1, 1), false, t("Cipher" to 194), 0, listOf("Electronic")),
            Album(14, 1, "Incompetech Sessions", "EP", today.plusDays(5), true, t("Session One" to 180, "Session Two" to 200, "Session Three" to 210), 0, listOf("Soundtrack")),
            Album(21, 2, "Symphony No. 5 in C minor, Op. 67", "Album", LocalDate.of(2012, 9, 1), true, t("I. Allegro con brio" to 452, "II. Andante con moto" to 590, "III. Scherzo: Allegro" to 300, "IV. Allegro" to 640), 4, listOf("Classical")),
            Album(22, 2, "Symphony No. 3 in E-flat major, Op. 55 \"Eroica\"", "Album", LocalDate.of(2012, 9, 1), true, t("I. Allegro con brio" to 1080, "II. Marcia funebre: Adagio assai" to 960, "III. Scherzo: Allegro vivace" to 345, "IV. Finale: Allegro molto" to 700), 2, listOf("Classical")),
            Album(23, 2, "Piano Sonatas", "Album", today.minusDays(2), true, t("Sonata No. 14 \"Moonlight\": I. Adagio sostenuto" to 360, "Sonata No. 8 \"Pathétique\": II. Adagio cantabile" to 300, "Für Elise" to 175), 0, listOf("Classical")),
            Album(31, 3, "Gymnopédies", "EP", LocalDate.of(2013, 5, 1), true, t("Gymnopédie No. 1" to 205, "Gymnopédie No. 2" to 160, "Gymnopédie No. 3" to 150), 3, listOf("Classical")),
            Album(32, 3, "Gnossiennes", "Album", LocalDate.of(2013, 5, 1), true, t("Gnossienne No. 1" to 230, "Gnossienne No. 2" to 120, "Gnossienne No. 3" to 160, "Gnossienne No. 4" to 165, "Gnossienne No. 5" to 190, "Gnossienne No. 6" to 105), 0, listOf("Classical")),
        )
        State(artists, albums).also { it.queue[32] = now.minus(Duration.ofSeconds(40)) }
    } as State

    /* ------------------------------------------------------------ JSON */

    private const val TRACK_BYTES_PER_SECOND = 112_000L // ~900 kbps FLAC

    private fun albumSize(a: Album) = a.tracks.take(a.have).sumOf { it.seconds * TRACK_BYTES_PER_SECOND }

    private fun stats(albums: List<Album>, monitoredOnly: Boolean = true) = obj(
        "albumCount" to albums.size,
        "trackFileCount" to albums.sumOf { it.have },
        "trackCount" to albums.filter { !monitoredOnly || it.monitored || it.have > 0 }.sumOf { if (it.monitored) it.tracks.size else it.have },
        "totalTrackCount" to albums.sumOf { it.tracks.size },
        "sizeOnDisk" to albums.sumOf { albumSize(it) },
        "percentOfTracks" to albums.sumOf { it.tracks.size }.let { total -> if (total == 0) 0.0 else albums.sumOf { it.have } * 100.0 / total },
    )

    private fun artistJson(st: State, a: Artist): JsonObject {
        val folder = a.name.replace(Regex("[^\\p{L}\\p{N} .-]"), "")
        return obj(
            "id" to a.id,
            "artistName" to a.name,
            "sortName" to a.sortName,
            "foreignArtistId" to a.mbid,
            "artistType" to a.type,
            "disambiguation" to "",
            "status" to if (a.ended) "ended" else "continuing",
            "ended" to a.ended,
            "overview" to a.overview,
            "images" to emptyList<Any>(),
            "path" to "/data/media/music/$folder",
            "qualityProfileId" to 1,
            "metadataProfileId" to 1,
            "monitored" to a.monitored,
            "monitorNewItems" to "all",
            "rootFolderPath" to "/data/media/music",
            "genres" to a.genres,
            "tags" to emptyList<Int>(),
            "added" to a.added,
            "ratings" to obj("votes" to 0, "value" to 0.0),
            "statistics" to stats(st.albums.filter { it.artistId == a.id }),
        )
    }

    private fun albumJson(st: State, al: Album, includeArtist: Boolean): JsonObject = obj(
        "id" to al.id,
        "title" to al.title,
        "disambiguation" to "",
        "overview" to "",
        "artistId" to al.artistId,
        "foreignAlbumId" to al.mbid,
        "monitored" to al.monitored,
        "anyReleaseOk" to true,
        "profileId" to 1,
        "duration" to al.tracks.sumOf { it.seconds } * 1000L,
        "albumType" to al.type,
        "secondaryTypes" to emptyList<Any>(),
        "mediumCount" to 1,
        "ratings" to obj("votes" to 0, "value" to 0.0),
        "releaseDate" to "${al.released}T00:00:00Z",
        "genres" to al.genres,
        "images" to emptyList<Any>(),
        "statistics" to obj(
            "trackFileCount" to al.have,
            "trackCount" to al.tracks.size,
            "totalTrackCount" to al.tracks.size,
            "sizeOnDisk" to albumSize(al),
            "percentOfTracks" to if (al.tracks.isEmpty()) 0.0 else al.have * 100.0 / al.tracks.size,
        ),
        "artist" to if (includeArtist) st.artists.firstOrNull { it.id == al.artistId }?.let { artistJson(st, it) } else null,
    )

    private fun trackJson(al: Album, i: Int, tr: Track) = obj(
        "id" to al.id * 100 + i + 1,
        "artistId" to al.artistId,
        "albumId" to al.id,
        "trackFileId" to if (i < al.have) al.id * 100 + i + 1 else 0,
        "explicit" to false,
        "absoluteTrackNumber" to i + 1,
        "trackNumber" to "${i + 1}",
        "title" to tr.title,
        "duration" to tr.seconds * 1000L,
        "mediumNumber" to 1,
        "hasFile" to (i < al.have),
    )

    private fun trackFileJson(al: Album, i: Int, tr: Track, added: Instant) = obj(
        "id" to al.id * 100 + i + 1,
        "artistId" to al.artistId,
        "albumId" to al.id,
        "path" to "/data/media/music/${al.title}/%02d - ${tr.title}.flac".format(i + 1),
        "size" to tr.seconds * TRACK_BYTES_PER_SECOND,
        "dateAdded" to added,
        "quality" to obj("quality" to obj("id" to 6, "name" to "FLAC"), "revision" to obj("version" to 1, "real" to 0)),
    )

    /* ------------------------------------------------------------ requests */

    private fun id(p: String) = p.substringAfterLast('/').toIntOrNull()

    fun handle(w: World, r: DemoRequest): DemoResponse? {
        val st = state(w)
        val p = r.path
        val now = Instant.now()
        settleQueue(st, now)
        return when {
            p == "/api/v1/system/status" -> DemoResponse.json(obj("appName" to "Lidarr", "version" to "2.9.6.4552", "branch" to "master", "startTime" to w.started, "osName" to "ubuntu", "osVersion" to "24.04", "isDocker" to true, "databaseType" to "sqLite"))
            p == "/api/v1/health" -> DemoResponse.json(arr(emptyList<Any>()))
            p == "/api/v1/diskspace" -> DemoResponse.json(arr(listOf(obj("path" to "/data", "label" to "data", "freeSpace" to 1_830_000_000_000L, "totalSpace" to 8_000_000_000_000L))))
            p == "/api/v1/rootfolder" -> DemoResponse.json(arr(listOf(obj(
                "id" to 1, "name" to "Music", "path" to "/data/media/music", "accessible" to true, "freeSpace" to 1_830_000_000_000L,
                "defaultQualityProfileId" to 1, "defaultMetadataProfileId" to 1, "defaultMonitorOption" to "all", "defaultNewItemMonitorOption" to "all",
            ))))
            p == "/api/v1/qualityprofile" -> DemoResponse.json(arr(listOf(obj("id" to 1, "name" to "Lossless"), obj("id" to 2, "name" to "High Quality MP3"), obj("id" to 3, "name" to "Any"))))
            p == "/api/v1/metadataprofile" -> DemoResponse.json(arr(listOf(obj("id" to 1, "name" to "Standard"), obj("id" to 2, "name" to "Albums only"))))
            p == "/api/v1/queue/status" -> DemoResponse.json(obj("totalCount" to st.queue.size, "count" to st.queue.size, "errors" to false, "warnings" to false))
            p == "/api/v1/queue" -> DemoResponse.json(queuePage(st, now))
            p.startsWith("/api/v1/queue/") && r.method == "DELETE" -> {
                val qid = id(p)
                st.queue.keys.firstOrNull { 9000 + it == qid }?.let { st.queue.remove(it) }
                DemoResponse.empty(200)
            }
            p == "/api/v1/command" && r.method == "POST" -> command(st, r, now)
            p == "/api/v1/command" -> DemoResponse.json(arr(st.commands.map { commandJson(it, now) }))
            p == "/api/v1/wanted/missing" -> DemoResponse.json(obj("page" to 1, "pageSize" to 1, "totalRecords" to st.albums.count { it.monitored && it.have == 0 && !it.released.isAfter(DemoWorld.today()) }, "records" to emptyList<Any>()))
            p == "/api/v1/wanted/cutoff" -> DemoResponse.json(obj("page" to 1, "pageSize" to 1, "totalRecords" to 0, "records" to emptyList<Any>()))
            p == "/api/v1/history" -> DemoResponse.json(history(st, r, w))
            p == "/api/v1/calendar" -> DemoResponse.json(calendar(st, r))
            p == "/api/v1/artist/lookup" -> DemoResponse.json(lookup(st, r.q("term").orEmpty()))
            p == "/api/v1/artist" && r.method == "POST" -> add(st, r, now)
            p == "/api/v1/artist" -> DemoResponse.json(arr(st.artists.map { artistJson(st, it) }))
            p.startsWith("/api/v1/artist/") -> artistRoutes(st, r)
            p == "/api/v1/album/monitor" && r.method == "PUT" -> {
                val body = runCatching { AppJson.parseToJsonElement(r.body) as JsonObject }.getOrNull() ?: return DemoResponse.notFound("Bad body")
                val ids = (body["albumIds"] as? JsonArray)?.mapNotNull { it.int() }.orEmpty().toSet()
                val to = body["monitored"].bool() ?: true
                st.albums.filter { it.id in ids }.forEach { it.monitored = to }
                DemoResponse.json(arr(st.albums.filter { it.id in ids }.map { albumJson(st, it, false) }), 202)
            }
            p == "/api/v1/album" -> {
                val artistId = r.q("artistId")?.toIntOrNull()
                DemoResponse.json(arr(st.albums.filter { artistId == null || it.artistId == artistId }.map { albumJson(st, it, false) }))
            }
            p.startsWith("/api/v1/album/") -> st.albums.firstOrNull { it.id == id(p) }?.let { DemoResponse.json(albumJson(st, it, true)) } ?: DemoResponse.notFound("Album not found")
            p == "/api/v1/track" -> {
                val al = st.albums.firstOrNull { it.id == r.q("albumId")?.toIntOrNull() } ?: return DemoResponse.json(arr(emptyList<Any>()))
                DemoResponse.json(arr(al.tracks.mapIndexed { i, tr -> trackJson(al, i, tr) }))
            }
            p == "/api/v1/trackfile" -> {
                val al = st.albums.firstOrNull { it.id == r.q("albumId")?.toIntOrNull() } ?: return DemoResponse.json(arr(emptyList<Any>()))
                val added = st.artists.firstOrNull { it.id == al.artistId }?.added ?: w.started
                DemoResponse.json(arr(al.tracks.take(al.have).mapIndexed { i, tr -> trackFileJson(al, i, tr, added) }))
            }
            else -> null
        }
    }

    private fun artistRoutes(st: State, r: DemoRequest): DemoResponse {
        val a = st.artists.firstOrNull { it.id == id(r.path) } ?: return DemoResponse.notFound("Artist not found")
        return when (r.method) {
            "PUT" -> {
                val body = runCatching { AppJson.parseToJsonElement(r.body) as JsonObject }.getOrNull()
                body?.get("monitored").bool()?.let { a.monitored = it }
                DemoResponse.json(artistJson(st, a), 202)
            }
            "DELETE" -> {
                st.artists.remove(a)
                st.albums.removeAll { it.artistId == a.id }
                DemoResponse.empty(200)
            }
            else -> DemoResponse.json(artistJson(st, a))
        }
    }

    /** Downloads finish after two minutes: the album gets its files and leaves the queue. */
    private fun settleQueue(st: State, now: Instant) {
        val done = st.queue.filter { (_, at) -> Duration.between(at, now).seconds >= 120 }.keys
        done.forEach { albumId -> st.albums.firstOrNull { it.id == albumId }?.let { it.have = it.tracks.size }; st.queue.remove(albumId) }
    }

    private fun queuePage(st: State, now: Instant): JsonObject {
        val records = st.queue.mapNotNull { (albumId, at) ->
            val al = st.albums.firstOrNull { it.id == albumId } ?: return@mapNotNull null
            val artist = st.artists.firstOrNull { it.id == al.artistId } ?: return@mapNotNull null
            val size = al.tracks.sumOf { it.seconds * TRACK_BYTES_PER_SECOND }.toDouble()
            val progress = (Duration.between(at, now).seconds / 120.0).coerceIn(0.0, 1.0)
            val left = (120 - Duration.between(at, now).seconds).coerceAtLeast(0)
            obj(
                "id" to 9000 + albumId,
                "artistId" to artist.id,
                "albumId" to al.id,
                "artist" to artistJson(st, artist),
                "album" to albumJson(st, al, false),
                "title" to "${dotted(artist.name)}-${dotted(al.title)}-FLAC-${al.released.year}",
                "size" to size,
                "sizeleft" to size * (1 - progress),
                "status" to "downloading",
                "timeleft" to "00:%02d:%02d".format(left / 60, left % 60),
                "trackedDownloadStatus" to "ok",
                "trackedDownloadState" to "downloading",
                "statusMessages" to emptyList<Any>(),
                "protocol" to "torrent",
                "downloadClient" to "qBittorrent",
                "downloadId" to infoHash("lidarr-${al.id}").uppercase(),
                "quality" to obj("quality" to obj("id" to 6, "name" to "FLAC")),
            )
        }
        return obj("page" to 1, "pageSize" to 1000, "totalRecords" to records.size, "records" to records)
    }

    private fun command(st: State, r: DemoRequest, now: Instant): DemoResponse {
        val body = runCatching { AppJson.parseToJsonElement(r.body) as JsonObject }.getOrNull()
        val name = body?.get("name").str() ?: "Unknown"
        val c = Command(st.nextCommand++, name, now)
        st.commands.add(0, c)
        while (st.commands.size > 20) st.commands.removeAt(st.commands.lastIndex)
        // A search starts a download for the first monitored album that's missing.
        val wanted: List<Album> = when (name) {
            "AlbumSearch" -> (body?.get("albumIds") as? JsonArray)?.mapNotNull { it.int() }.orEmpty().mapNotNull { id -> st.albums.firstOrNull { it.id == id } }
            "ArtistSearch" -> st.albums.filter { it.artistId == body?.get("artistId").int() && it.monitored }
            "MissingAlbumSearch" -> st.albums.filter { it.monitored }
            else -> emptyList()
        }
        wanted.firstOrNull { it.have < it.tracks.size && !it.released.isAfter(DemoWorld.today()) && it.id !in st.queue }?.let { st.queue[it.id] = now }
        return DemoResponse.json(commandJson(c, now), 201)
    }

    private fun commandJson(c: Command, now: Instant): JsonObject {
        val age = Duration.between(c.at, now).toMillis()
        val status = when { age < 1_200 -> "queued"; age < 6_000 -> "started"; else -> "completed" }
        return obj(
            "id" to c.id, "name" to c.name, "status" to status, "trigger" to "manual",
            "queued" to c.at, "started" to if (status != "queued") c.at.plusMillis(1_200) else null, "ended" to if (status == "completed") c.at.plusMillis(6_000) else null,
        )
    }

    private fun calendar(st: State, r: DemoRequest): JsonArray {
        val start = r.q("start")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now().minus(Duration.ofDays(7))
        val end = r.q("end")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now().plus(Duration.ofDays(28))
        val include = r.q("includeArtist") == "true"
        return arr(st.albums.filter { al ->
            val at = Instant.parse("${al.released}T00:00:00Z")
            !at.isBefore(start) && at.isBefore(end)
        }.map { albumJson(st, it, include) })
    }

    private fun history(st: State, r: DemoRequest, w: World): JsonObject {
        val types = r.qAll("eventType").mapNotNull { it.toIntOrNull() }.toSet()
        val records = mutableListOf<JsonObject>()
        var n = 1
        for (al in st.albums.filter { it.have > 0 }.sortedByDescending { it.id }) {
            val artist = st.artists.firstOrNull { it.id == al.artistId } ?: continue
            val at = artist.added.plus(Duration.ofHours(al.id.toLong()))
            val source = "${dotted(artist.name)}-${dotted(al.title)}-FLAC-${al.released.year}"
            for ((type, name, offset) in listOf(Triple(1, "grabbed", 0L), Triple(8, "downloadImported", 25L))) {
                if (types.isNotEmpty() && type !in types) continue
                records += obj(
                    "id" to n++, "eventType" to name, "date" to at.plus(Duration.ofMinutes(offset)), "sourceTitle" to source,
                    "quality" to obj("quality" to obj("id" to 6, "name" to "FLAC")),
                    "data" to if (type == 1) obj("indexer" to "Musopen (Prowlarr)", "downloadClient" to "qBittorrent") else obj("downloadClient" to "qBittorrent"),
                    "artistId" to artist.id, "albumId" to al.id,
                    "artist" to obj("id" to artist.id, "artistName" to artist.name, "images" to emptyList<Any>()),
                    "album" to obj("id" to al.id, "title" to al.title),
                )
            }
        }
        records.sortByDescending { it["date"].str().orEmpty() }
        return obj("page" to 1, "pageSize" to 30, "totalRecords" to records.size, "records" to records.take(30))
    }

    private fun lookup(st: State, term: String): JsonArray {
        val needle = term.trim().lowercase()
        if (needle.isEmpty()) return arr(emptyList<Any>())
        val inLibrary = st.artists.filter { it.name.lowercase().contains(needle) }.map { artistJson(st, it) }
        val fresh = CATALOG.filter { (name) -> name.lowercase().contains(needle) && st.artists.none { it.name == name } }.map { (name, type, overview) ->
            val mbid = hexId("lidarr-artist-$name").let { "${it.take(8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" }
            obj(
                "artistName" to name, "sortName" to name, "foreignArtistId" to mbid, "artistType" to type, "overview" to overview,
                "status" to "ended", "ended" to true, "images" to emptyList<Any>(), "genres" to listOf("Classical"), "monitored" to false,
            )
        }
        return arr(inLibrary + fresh)
    }

    private fun add(st: State, r: DemoRequest, now: Instant): DemoResponse {
        val body = runCatching { AppJson.parseToJsonElement(r.body) as JsonObject }.getOrNull() ?: return DemoResponse.notFound("Bad body")
        val name = body["artistName"].str() ?: return DemoResponse.json(obj("message" to "Artist name is required"), 400)
        if (st.artists.any { it.name == name }) return DemoResponse.json(arr(listOf(obj("propertyName" to "ForeignArtistId", "errorMessage" to "This artist has already been added"))), 400)
        val (_, type, overview) = CATALOG.firstOrNull { it.first == name } ?: Triple(name, "Person", "")
        val monitored = body["monitored"].bool() ?: true
        val a = Artist(st.nextArtist++, name, name, type, overview, listOf("Classical"), monitored, now, true)
        st.artists += a
        // Two albums, nothing on disk yet.
        st.albums += Album(st.nextAlbum++, a.id, "Selected Works", "Album", LocalDate.of(2011, 1, 1), monitored, t("No. 1" to 240, "No. 2" to 200, "No. 3" to 260, "No. 4" to 180), 0, listOf("Classical"))
        st.albums += Album(st.nextAlbum++, a.id, "Miniatures", "EP", LocalDate.of(2014, 1, 1), monitored, t("Prelude" to 120, "Interlude" to 90, "Postlude" to 140), 0, listOf("Classical"))
        val addOptions = body["addOptions"] as? JsonObject
        if (addOptions?.get("searchForMissingAlbums").bool() == true && monitored) st.queue[st.nextAlbum - 2] = now
        return DemoResponse.json(artistJson(st, a), 201)
    }
}
