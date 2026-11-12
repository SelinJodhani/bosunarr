package app.bosunarr.feature.tautulli

import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceException
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Parsing against the response shapes in the Tautulli API v2 docs (synthetic data). */
class TautulliParsingTest {
    private fun json(s: String): JsonElement = AppJson.parseToJsonElement(s)
    private fun ok(data: String) = json("""{"response": {"result": "success", "message": null, "data": $data}}""")

    @Test
    fun errors() {
        val bad = tautulliError("Tautulli", """{"response": {"result": "error", "message": "Invalid apikey", "data": {}}}""")
        assertEquals(401, bad?.status)
        assertEquals("Tautulli rejected the credentials", bad?.message)
        val other = tautulliError("Tautulli", """{"response": {"result": "error", "message": "Unknown command: nope", "data": {}}}""")
        assertEquals(400, other?.status)
        assertEquals("Tautulli: Unknown command: nope", other?.message)
        assertNull(tautulliError("Tautulli", """{"response": {"result": "success", "message": null, "data": {}}}"""))
        assertNull(tautulliError("Tautulli", "<html>login</html>"))
        try {
            tautulliData(json("""{"response": {"result": "error", "message": "Invalid apikey"}}"""), "Tautulli")
            throw AssertionError("expected an exception")
        } catch (e: ServiceException) {
            assertEquals(401, e.status)
        }
    }

    @Test
    fun info() {
        val i = parseTautulliInfo(
            tautulliData(ok("""{"tautulli_install_type": "docker", "tautulli_version": "v2.14.6", "tautulli_branch": "master"}"""), "T"),
            tautulliData(
                ok(
                    """{"pms_identifier": "abc", "pms_ip": "192.168.1.10", "pms_is_remote": 0, "pms_name": "Basement",
                     "pms_platform": "Linux", "pms_plexpass": 1, "pms_port": 32400, "pms_ssl": 0, "pms_url": "http://192.168.1.10:32400",
                     "pms_url_manual": 0, "pms_version": "1.41.2.9200-c6bbc1b53"}""",
                ),
                "T",
            ),
        )
        assertEquals("2.14.6", i.version)
        assertEquals("Basement", i.pmsName)
        assertEquals("1.41.2.9200", i.pmsVersion)
        assertEquals(true, i.plexPass)
    }

    @Test
    fun overview() {
        val plays = parsePlaysByDate(
            tautulliData(
                ok(
                    """{"categories": ["2026-11-10", "2026-11-11", "2026-11-12"],
                     "series": [{"name": "TV", "data": [2, 0, 3]}, {"name": "Movies", "data": [1, 0, "1"]}, {"name": "Music", "data": [0, 0, 0]}]}""",
                ),
                "T",
            ),
        )
        assertEquals(mapOf(LocalDate.of(2026, 11, 10) to 3.0, LocalDate.of(2026, 11, 11) to 0.0, LocalDate.of(2026, 11, 12) to 4.0), plays)
        val seconds = parsePlaysByDate(
            ok("""{"categories": ["2026-11-10", "2026-11-11", "2026-11-12"], "series": [{"name": "TV", "data": [3600, 0, 7200]}]}""")
                .let { tautulliData(it, "T") },
        )
        val lastSeen = parseLastSeen(
            tautulliData(
                ok(
                    """{"draw": 1, "recordsTotal": 3, "recordsFiltered": 3, "data": [
                     {"user_id": 1, "friendly_name": "alice", "last_seen": ${Instant.parse("2026-11-12T08:00:00Z").epochSecond}},
                     {"user_id": 2, "friendly_name": "bob", "last_seen": ${Instant.parse("2026-11-01T08:00:00Z").epochSecond}},
                     {"user_id": 3, "friendly_name": "never", "last_seen": null}]}""",
                ),
                "T",
            ),
        )
        assertEquals(2, lastSeen.size)
        val o = buildOverview(plays, seconds, lastSeen, ZoneOffset.UTC)
        assertEquals(3, o.daily.size)
        val week = o.periods.getValue(7)
        assertEquals(7, week.plays)
        assertEquals(10800.0, week.seconds, 0.01)
        // Only alice was seen since the first day of the (3-day) slice.
        assertEquals(1, week.activeUsers)
    }

    @Test
    fun homeStats() {
        val top = parseHomeStats(
            tautulliData(
                ok(
                    """[
                     {"stat_id": "top_movies", "stat_type": "total_plays", "stat_title": "Most Watched Movies", "rows": [
                       {"title": "A Movie", "year": 2021, "total_plays": 12, "total_duration": 86400, "users_watched": "", "rating_key": 1},
                       {"title": "B Movie", "total_plays": "3", "total_duration": "5400"}]},
                     {"stat_id": "top_tv", "rows": [{"title": "The Show", "grandparent_title": "The Show", "total_plays": 40, "total_duration": 100000}]},
                     {"stat_id": "top_users", "rows": [{"user": "alice", "friendly_name": "Alice", "total_plays": 52, "total_duration": 190000}]},
                     {"stat_id": "top_platforms", "rows": [{"platform": "Android", "total_plays": 50}]}
                    ]""",
                ),
                "T",
            ),
        )
        assertEquals(listOf("A Movie", "B Movie"), top.movies.map { it.name })
        assertEquals(3, top.movies[1].plays)
        assertEquals(5400.0, top.movies[1].seconds!!, 0.01)
        assertEquals("The Show", top.shows.single().name)
        assertEquals("Alice", top.users.single().name)
        assertEquals("1d 0h", watchTime(86400.0))
    }

    @Test
    fun activity() {
        val a = parseActivity(
            tautulliData(
                ok(
                    """{"lan_bandwidth": 8000, "wan_bandwidth": 4000, "total_bandwidth": 12000,
                     "stream_count": "2", "stream_count_direct_play": 1, "stream_count_direct_stream": 0, "stream_count_transcode": 1,
                     "sessions": [
                      {"session_key": "7", "session_id": "abc", "media_type": "episode", "title": "Pilot", "grandparent_title": "The Show",
                       "parent_media_index": "1", "media_index": "1", "friendly_name": "Alice", "user": "alice", "player": "Living Room",
                       "product": "Plex for Android (TV)", "progress_percent": "25", "state": "playing", "transcode_decision": "transcode",
                       "transcode_hw_decoding": 1, "transcode_hw_encoding": 1, "quality_profile": "4 Mbps 720p"},
                      {"session_key": "8", "media_type": "movie", "title": "A Movie", "year": "2021", "user": "bob", "player": "iPhone",
                       "product": "Plex for iOS", "progress_percent": "90", "state": "paused", "transcode_decision": "direct play",
                       "transcode_hw_decoding": 0, "quality_profile": "Original"}
                     ]}""",
                ),
                "T",
            ),
        )
        assertEquals(2, a.streams)
        assertEquals(1, a.transcodes)
        assertEquals(12000, a.bandwidthKbps)
        val ep = a.sessions[0]
        assertEquals("The Show", ep.title)
        assertEquals("S01E01 · Pilot", ep.subtitle)
        assertEquals("Alice", ep.user)
        assertEquals("Plex for Android (TV) · Living Room", ep.device)
        assertEquals(0.25f, ep.progress, 0.001f)
        assertEquals("Transcode", ep.decision)
        assertTrue(ep.hw)
        assertEquals("4 Mbps 720p", ep.quality)
        val movie = a.sessions[1]
        assertEquals("2021", movie.subtitle)
        assertEquals("Direct play", movie.decision)
        assertFalse(movie.hw)
        assertNull(movie.quality)
        assertEquals("paused", movie.state)
    }

    @Test
    fun history() {
        val list = parseHistory(
            tautulliData(
                ok(
                    """{"draw": 1, "recordsTotal": 1000, "recordsFiltered": 1000, "total_duration": "1 day", "data": [
                     {"reference_id": 10, "row_id": 10, "id": 10, "date": 1700000000, "started": 1700000000, "stopped": 1700003600,
                      "duration": 3500, "play_duration": 3400, "paused_counter": 100, "user_id": 1, "user": "alice", "friendly_name": "Alice",
                      "platform": "Android", "product": "Plex for Android (TV)", "player": "Living Room", "media_type": "episode",
                      "full_title": "The Show - Pilot", "title": "Pilot", "grandparent_title": "The Show", "parent_media_index": 1,
                      "media_index": 1, "percent_complete": 98, "watched_status": 1, "transcode_decision": "direct play"},
                     {"row_id": 11, "date": 1700005000, "duration": 600, "friendly_name": "Bob", "media_type": "track",
                      "title": "Song", "grandparent_title": "Artist", "parent_title": "Album", "player": "Web", "product": "Plexamp"}
                    ]}""",
                ),
                "T",
            ),
        )
        assertEquals(2, list.size)
        assertEquals("The Show", list[0].title)
        assertEquals("S01E01 · Pilot", list[0].subtitle)
        assertEquals(3400.0, list[0].seconds, 0.01)
        assertEquals(98, list[0].percent)
        assertEquals(Instant.ofEpochSecond(1700000000), list[0].at)
        assertEquals("Song", list[1].title)
        assertEquals("Artist · Album", list[1].subtitle)
        assertEquals(600.0, list[1].seconds, 0.01) // older versions: only `duration`
    }
}
