package app.bosunarr.demo

import app.bosunarr.data.ServiceType
import app.bosunarr.feature.library.ArrProfile
import app.bosunarr.feature.library.LibStatus
import app.bosunarr.feature.music.LidarrAlbum
import app.bosunarr.feature.music.LidarrArtist
import app.bosunarr.feature.music.LidarrMetadataProfile
import app.bosunarr.feature.music.LidarrRootFolder
import app.bosunarr.feature.music.LidarrTrack
import app.bosunarr.feature.music.LidarrTrackFile
import app.bosunarr.feature.music.albumStatus
import app.bosunarr.feature.music.artistStatus
import app.bosunarr.net.ServiceClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The demo Lidarr answers every `/api/v1` call the music screens make, in shapes the app's models decode. */
class DemoLidarrTest {
    private lateinit var client: ServiceClient
    private val s = Demo.services.first { it.serviceType == ServiceType.Lidarr }
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    @Before
    fun setUp() {
        DemoAssets.loader = { path -> File("src/main/assets/demo/$path").takeIf { it.exists() }?.readBytes() }
        DemoWorld.reset()
        client = ServiceClient(OkHttpClient.Builder().addInterceptor(DemoInterceptor().apply { latency = false }).build())
    }

    @Test
    fun library(): Unit = runBlocking {
        assertEquals("2.9.6.4552", client.test(s).version)
        val artists: List<LidarrArtist> = client.get(s, "/api/v1/artist")
        assertEquals(3, artists.size)
        val satie = artists.first { it.artistName == "Erik Satie" }
        assertEquals(LibStatus.Partial, artistStatus(satie))
        val albums: List<LidarrAlbum> = client.get(s, "/api/v1/album?artistId=${satie.id}")
        assertEquals(2, albums.size)
        assertEquals(LibStatus.Downloaded, albumStatus(albums.first { it.title == "Gymnopédies" }))
        val one: LidarrAlbum = client.get(s, "/api/v1/album/${albums[0].id}")
        assertNotNull(one.artist)
        val tracks: List<LidarrTrack> = client.get(s, "/api/v1/track?albumId=${albums.first { it.title == "Gymnopédies" }.id}")
        assertEquals(3, tracks.size)
        assertEquals("1", tracks[0].trackNumber)
        val files: List<LidarrTrackFile> = client.get(s, "/api/v1/trackfile?albumId=${albums.first { it.title == "Gymnopédies" }.id}")
        assertEquals("FLAC", files[0].quality?.quality?.name)
        assertTrue(client.get<List<ArrProfile>>(s, "/api/v1/qualityprofile").isNotEmpty())
        assertTrue(client.get<List<LidarrMetadataProfile>>(s, "/api/v1/metadataprofile").isNotEmpty())
        assertEquals(1, client.get<List<LidarrRootFolder>>(s, "/api/v1/rootfolder")[0].defaultMetadataProfileId)
    }

    @Test
    fun calendarQueueAndActions(): Unit = runBlocking {
        val now = Instant.now()
        val cal: List<LidarrAlbum> = client.get(s, "/api/v1/calendar?start=${enc(now.minus(7, ChronoUnit.DAYS).toString())}&end=${enc(now.plus(28, ChronoUnit.DAYS).toString())}&unmonitored=true&includeArtist=true")
        assertEquals(2, cal.size)
        assertTrue(cal.all { it.artist != null })
        val queue = client.json(s, "/api/v1/queue?page=1&pageSize=1000&includeArtist=true&includeAlbum=true") as JsonObject
        assertEquals(1, (queue["records"] as JsonArray).size)

        val albums: List<LidarrAlbum> = client.get(s, "/api/v1/album")
        val cipher = albums.first { it.title == "Cipher" }
        client.send(s, "/api/v1/album/monitor", "PUT", buildJsonObject { putJsonArray("albumIds") { add(cipher.id) }; put("monitored", true) })
        assertTrue(client.get<LidarrAlbum>(s, "/api/v1/album/${cipher.id}").monitored)
        client.send(s, "/api/v1/command", "POST", buildJsonObject { put("name", "AlbumSearch"); putJsonArray("albumIds") { add(cipher.id) } })
        val q2 = client.json(s, "/api/v1/queue?page=1&pageSize=1000&includeArtist=true&includeAlbum=true") as JsonObject
        assertEquals(2, (q2["records"] as JsonArray).size)

        val found = client.json(s, "/api/v1/artist/lookup?term=joplin") as JsonArray
        assertEquals(1, found.size)
        val body = JsonObject((found[0] as JsonObject) + mapOf(
            "qualityProfileId" to kotlinx.serialization.json.JsonPrimitive(1),
            "metadataProfileId" to kotlinx.serialization.json.JsonPrimitive(1),
            "rootFolderPath" to kotlinx.serialization.json.JsonPrimitive("/data/media/music"),
            "monitored" to kotlinx.serialization.json.JsonPrimitive(true),
            "addOptions" to buildJsonObject { put("monitor", "all"); put("searchForMissingAlbums", false) },
        ))
        val created = client.decode(s, client.send(s, "/api/v1/artist", "POST", body), LidarrArtist.serializer())
        assertTrue(created.id > 0)
        assertEquals(4, client.get<List<LidarrArtist>>(s, "/api/v1/artist").size)
        client.send(s, "/api/v1/artist/${created.id}?deleteFiles=false&addImportListExclusion=false", "DELETE")
        assertEquals(3, client.get<List<LidarrArtist>>(s, "/api/v1/artist").size)
        val history = client.json(s, "/api/v1/history?page=1&pageSize=30&sortKey=date&sortDirection=descending&includeArtist=true&includeAlbum=true") as JsonObject
        assertTrue((history["records"] as JsonArray).size >= 4)
    }
}
