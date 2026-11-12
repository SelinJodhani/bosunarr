package app.bosunarr.feature.plex

import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceException
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Parsing against Plex Media Server / plex.tv JSON shapes (synthetic data). */
class PlexParsingTest {
    private fun json(s: String): JsonElement = AppJson.parseToJsonElement(s)

    @Test
    fun root() {
        val info = parsePlexRoot(
            json(
                """
                {"MediaContainer": {"size": 25, "allowCameraUpload": true, "friendlyName": "Basement",
                 "machineIdentifier": "0123456789abcdef0123456789abcdef01234567", "myPlex": true,
                 "myPlexSubscription": true, "myPlexUsername": "owner@example.com", "platform": "Linux",
                 "platformVersion": "6.8.0", "transcoderActiveVideoSessions": 1, "version": "1.41.2.9200-c6bbc1b53",
                 "Directory": [{"count": 1, "key": "library", "title": "library"}]}}
                """,
            ),
        )
        assertEquals("Basement", info.name)
        assertEquals("1.41.2.9200-c6bbc1b53", info.version)
        assertEquals("1.41.2.9200", shortPlexVersion(info.version))
        assertEquals("Linux 6.8.0", info.platform)
        assertEquals(true, info.plexPass)
        assertEquals(1, info.transcodes)
    }

    @Test
    fun rootWithoutContainerIsAnError() {
        try {
            parsePlexRoot(json("""{"error": "nope"}"""))
            throw AssertionError("expected an exception")
        } catch (_: ServiceException) {
        }
    }

    @Test
    fun sessions() {
        val list = parsePlexSessions(
            json(
                """
                {"MediaContainer": {"size": 3, "Metadata": [
                  {"type": "episode", "title": "Pilot", "grandparentTitle": "The Show", "parentIndex": 1, "index": 1,
                   "duration": 3600000, "viewOffset": 900000, "ratingKey": "101", "sessionKey": "7",
                   "thumb": "/library/metadata/101/thumb/1700000000", "grandparentThumb": "/library/metadata/99/thumb/1700000000",
                   "Media": [{"videoResolution": "1080", "Part": [{"decision": "transcode"}]}],
                   "User": {"id": "1", "title": "alice"},
                   "Player": {"title": "Living Room", "product": "Plex for Android (TV)", "state": "playing", "local": true},
                   "Session": {"id": "abc123", "bandwidth": 8200, "location": "lan"},
                   "TranscodeSession": {"key": "/transcode/sessions/x", "throttled": false, "progress": 30.5, "speed": 2.4,
                     "videoDecision": "transcode", "audioDecision": "copy", "container": "mkv",
                     "videoCodec": "h264", "audioCodec": "eac3", "sourceVideoCodec": "hevc", "sourceAudioCodec": "eac3",
                     "transcodeHwRequested": true, "transcodeHwDecoding": "vaapi", "transcodeHwEncoding": "vaapi"}},
                  {"type": "movie", "title": "A Movie", "year": 2021, "duration": "7200000", "viewOffset": "60000",
                   "thumb": "/library/metadata/202/thumb/1",
                   "Media": [{"Part": [{"decision": "directplay"}]}],
                   "User": {"title": "bob"},
                   "Player": {"title": "iPhone", "product": "Plex for iOS", "state": "paused"},
                   "Session": {"id": "def456", "location": "wan"}},
                  {"type": "movie", "title": "Remuxed",
                   "Player": {"product": "Plex Web", "title": "Firefox", "state": "buffering"},
                   "TranscodeSession": {"videoDecision": "copy", "audioDecision": "copy", "container": "mp4"}}
                ]}}
                """,
            ),
        )
        assertEquals(3, list.size)
        val ep = list[0]
        assertEquals("The Show", ep.title)
        assertEquals("S01E01 · Pilot", ep.subtitle)
        assertEquals("alice", ep.user)
        assertEquals("Plex for Android (TV) · Living Room", ep.device)
        assertEquals("abc123", ep.sessionId)
        assertEquals(PlexDecision.Transcode, ep.decision)
        assertEquals("vaapi", ep.hw)
        assertEquals("LAN", ep.location)
        assertEquals(8200, ep.bandwidthKbps)
        assertEquals(900.0, ep.positionSec, 0.01)
        assertEquals(3600.0, ep.durationSec, 0.01)
        assertEquals("Video HEVC → H264 · Audio direct · MKV · 2.4×", ep.detail)
        // The series poster, scaled by Plex, with the thumb path URL-encoded.
        assertEquals("/photo/:/transcode?width=240&height=360&minSize=1&upscale=1&url=%2Flibrary%2Fmetadata%2F99%2Fthumb%2F1700000000", ep.poster)

        val movie = list[1]
        assertEquals("A Movie", movie.title)
        assertEquals("2021", movie.subtitle)
        assertEquals(PlexDecision.DirectPlay, movie.decision)
        assertNull(movie.detail)
        assertNull(movie.hw)
        assertTrue(movie.paused)
        assertEquals(60.0, movie.positionSec, 0.01) // numeric strings are accepted

        val remux = list[2]
        assertEquals(PlexDecision.DirectStream, remux.decision)
        assertEquals("Video direct · Audio direct · MP4", remux.detail)
        assertNull(remux.sessionId)
        assertEquals("Someone", remux.user)
    }

    @Test
    fun emptySessions() {
        assertTrue(parsePlexSessions(json("""{"MediaContainer": {"size": 0}}""")).isEmpty())
    }

    @Test
    fun sectionsAndCounts() {
        val sections = parsePlexSections(
            json(
                """
                {"MediaContainer": {"size": 4, "allowSync": false, "title1": "Plex Library", "Directory": [
                  {"allowSync": true, "art": "/:/resources/movie-fanart.jpg", "key": "1", "type": "movie", "title": "Movies",
                   "agent": "tv.plex.agents.movie", "scanner": "Plex Movie", "refreshing": false, "updatedAt": 1700000000},
                  {"key": "2", "type": "show", "title": "TV Shows", "refreshing": true},
                  {"key": "3", "type": "artist", "title": "Music", "refreshing": "0"},
                  {"key": "../evil", "type": "movie", "title": "Bad key"}
                ]}}
                """,
            ),
        )
        assertEquals(listOf("1", "2", "3"), sections.map { it.key })
        assertFalse(sections[0].refreshing)
        assertTrue(sections[1].refreshing)
        assertFalse(sections[2].refreshing)
        assertEquals("show", sections[1].type)

        assertEquals(1234, parsePlexTotal(json("""{"MediaContainer": {"size": 0, "totalSize": 1234, "offset": 0}}""")))
        assertEquals(5, parsePlexTotal(json("""{"MediaContainer": {"size": 5}}""")))
        assertEquals("movies", libraryNoun("movie", 2))
        assertEquals("show", libraryNoun("show", 1))
    }

    @Test
    fun recentlyAdded() {
        val list = parsePlexRecent(
            json(
                """
                {"MediaContainer": {"size": 4, "Metadata": [
                  {"ratingKey": "300", "type": "season", "title": "Season 2", "parentTitle": "The Show", "index": 2,
                   "thumb": "/library/metadata/300/thumb/1", "parentThumb": "/library/metadata/99/thumb/1", "addedAt": 1700000000},
                  {"ratingKey": "301", "type": "movie", "title": "New Movie", "year": 2024, "thumb": "/library/metadata/301/thumb/2", "addedAt": 1699990000},
                  {"ratingKey": "302", "type": "episode", "title": "Finale", "grandparentTitle": "Other Show", "parentIndex": 3, "index": 10,
                   "thumb": "/library/metadata/302/thumb/3", "grandparentThumb": "/library/metadata/50/thumb/3"},
                  {"ratingKey": "301", "type": "movie", "title": "New Movie (dup)"}
                ]}}
                """,
            ),
        )
        assertEquals(3, list.size)
        assertEquals("The Show", list[0].title)
        assertEquals("Season 2", list[0].subtitle)
        assertEquals(Instant.ofEpochSecond(1700000000), list[0].addedAt)
        assertTrue(list[0].poster!!.endsWith("url=%2Flibrary%2Fmetadata%2F300%2Fthumb%2F1"))
        assertEquals("2024", list[1].subtitle)
        assertEquals("Other Show", list[2].title)
        assertEquals("S03E10", list[2].subtitle)
        assertTrue(list[2].poster!!.endsWith("url=%2Flibrary%2Fmetadata%2F50%2Fthumb%2F3"))
    }

    @Test
    fun imagesOnlyForServerPaths() {
        assertNull(plexImage(null))
        assertNull(plexImage("https://elsewhere.example/poster.jpg"))
    }

    @Test
    fun pin() {
        val created = parsePlexPin(
            json(
                """
                {"id": 1234567890, "code": "ABCD", "product": "Bosunarr", "trusted": false, "qr": "https://plex.tv/api/v2/pins/qr/ABCD",
                 "clientIdentifier": "bosunarr-x", "location": {"code": "IN"}, "expiresIn": 900,
                 "createdAt": "2026-11-12T10:00:00Z", "expiresAt": "2026-11-12T10:15:00Z", "authToken": null, "newRegistration": null}
                """,
            ),
        )
        assertEquals(1234567890L, created.id)
        assertEquals("ABCD", created.code)
        assertNull(created.authToken)
        assertEquals(Instant.parse("2026-11-12T10:15:00Z"), created.expiresAt)

        val approved = parsePlexPin(json("""{"id": 1234567890, "code": "ABCD", "authToken": "tok-123"}"""))
        assertEquals("tok-123", approved.authToken)
        assertTrue(plexAuthUrl("bosunarr-x", approved).startsWith("https://app.plex.tv/auth#?clientID=bosunarr-x&code=ABCD&"))
    }

    @Test
    fun resources() {
        val servers = parsePlexResources(
            json(
                """
                [
                 {"name": "Friend's Plex", "product": "Plex Media Server", "provides": "server", "owned": false, "accessToken": "shared-token",
                  "connections": [{"protocol": "https", "address": "203.0.113.5", "port": 32400, "uri": "https://203-0-113-5.abc.plex.direct:32400", "local": false, "relay": false, "IPv6": false}]},
                 {"name": "My phone", "product": "Plex for Android (Mobile)", "provides": "client,player", "owned": true, "connections": []},
                 {"name": "Basement", "product": "Plex Media Server", "provides": "server", "owned": true, "accessToken": "server-token",
                  "connections": [
                    {"protocol": "https", "address": "10.0.0.2", "port": 32400, "uri": "https://10-0-0-2.def.plex.direct:32400", "local": false, "relay": true, "IPv6": false},
                    {"protocol": "https", "address": "fd00::2", "port": 32400, "uri": "https://x.plex.direct:32400", "local": true, "relay": false, "IPv6": true},
                    {"protocol": "https", "address": "192.168.1.10", "port": 32400, "uri": "https://192-168-1-10.def.plex.direct:32400", "local": true, "relay": false, "IPv6": false}
                  ]}
                ]
                """,
            ),
        )
        assertEquals(2, servers.size)
        // Owned first, with its own token and a direct LAN address.
        assertEquals("Basement", servers[0].name)
        assertEquals("server-token", servers[0].token)
        assertEquals("http://192.168.1.10:32400", servers[0].url)
        assertTrue(servers[0].owned)
        // Remote-only shared server: its plex.direct address.
        assertEquals("https://203-0-113-5.abc.plex.direct:32400", servers[1].url)
        assertFalse(servers[1].owned)
    }
}
