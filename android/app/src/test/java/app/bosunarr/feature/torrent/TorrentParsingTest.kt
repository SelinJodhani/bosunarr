package app.bosunarr.feature.torrent

import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.activity.qbit.Torrent
import app.bosunarr.feature.activity.qbit.TorrentGroup
import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Parsing against the response shapes in Transmission's RPC spec (docs/rpc-spec.md) and Deluge 2.x's Web UI JSON-RPC
 * (deluge/ui/web/json_api.py, deluge/core/torrent.py status keys). Synthetic data.
 */
class TorrentParsingTest {
    private val tr = ServiceConfig(id = "t1", type = "transmission", name = "Transmission", url = "http://nas:9091")
    private val dg = ServiceConfig(id = "d1", type = "deluge", name = "Deluge", url = "http://nas:8112")

    private fun obj(s: String): JsonObject = AppJson.parseToJsonElement(s).jsonObject

    private fun expectError(block: () -> Unit): ServiceException {
        try {
            block()
        } catch (e: ServiceException) {
            return e
        }
        fail("expected a ServiceException")
        throw IllegalStateException()
    }

    // ------------------------------------------------------------------ Transmission

    @Test
    fun transmissionRpcPath() {
        assertEquals("/transmission/rpc", Transmission.rpcPath("http://nas:9091"))
        assertEquals("/transmission/rpc", Transmission.rpcPath("http://nas:9091/"))
        assertEquals("/rpc", Transmission.rpcPath("https://example.org/transmission"))
        assertEquals("", Transmission.rpcPath("https://example.org/custom/rpc"))
        assertEquals("", Transmission.rpcPath("https://example.org/transmission/rpc/"))
    }

    @Test
    fun transmissionResponse() {
        val a = Transmission.parseResponse("T", """{"arguments": {"version": "4.0.5 (a6fe2a64aa)", "rpc-version": 17}, "result": "success", "tag": 3}""")
        assertEquals("4.0.5 (a6fe2a64aa)", a["version"].jText())
        val e = expectError { Transmission.parseResponse("T", """{"arguments": {}, "result": "invalid or corrupt torrent file", "tag": 4}""") }
        assertEquals("T: invalid or corrupt torrent file", e.message)
        expectError { Transmission.parseResponse("T", "<html><body>409: Conflict</body></html>") }
        expectError { Transmission.parseResponse("T", """{"something": "else"}""") }
    }

    private val trTorrents = obj(
        """
        {"torrents": [
          {"id": 1, "hashString": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "name": "Show.S01E01.1080p", "status": 4,
           "percentDone": 0.25, "rateDownload": 2048000, "rateUpload": 1000, "eta": 300, "totalSize": 4000000000,
           "sizeWhenDone": 2000000000, "leftUntilDone": 1500000000, "downloadedEver": 500000000, "uploadedEver": 10,
           "uploadRatio": 0.0, "error": 0, "errorString": "", "addedDate": 1700000000, "doneDate": 0,
           "labels": ["sonarr", "4k"], "downloadDir": "/downloads/tv", "peersConnected": 12, "peersSendingToUs": 9,
           "peersGettingFromUs": 2, "magnetLink": "magnet:?xt=urn:btih:aaaa", "secondsDownloading": 600,
           "secondsSeeding": 0, "metadataPercentComplete": 1},
          {"id": 2, "hashString": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "name": "Movie.2020", "status": 0,
           "percentDone": 1, "rateDownload": 0, "rateUpload": 0, "eta": -1, "totalSize": 100, "sizeWhenDone": 100,
           "leftUntilDone": 0, "uploadRatio": -1, "error": 0, "labels": [], "doneDate": 1700000500,
           "metadataPercentComplete": 1},
          {"id": 3, "hashString": "cccccccccccccccccccccccccccccccccccccccc", "name": "Linux.iso", "status": 6,
           "percentDone": 1, "rateUpload": 0, "eta": -2, "error": 0, "uploadRatio": 2.5},
          {"id": 4, "hashString": "dddddddddddddddddddddddddddddddddddddddd", "name": "Broken", "status": 0,
           "percentDone": 0.5, "error": 3, "errorString": "No data found! Ensure your drives are connected"},
          {"id": 5, "hashString": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "name": "magnet", "status": 4,
           "percentDone": 0, "rateDownload": 0, "metadataPercentComplete": 0.1},
          {"id": 6, "hashString": "ffffffffffffffffffffffffffffffffffffffff", "name": "Tracker down", "status": 2,
           "percentDone": 0.3, "error": 2, "errorString": "Tracker gave HTTP response code 404 (Not Found)"}
        ]}
        """,
    )

    private fun trList(): Map<Long, Torrent> = HashMap<Long, Torrent>().also { Transmission.mergeTorrents(it, trTorrents, tr) }

    @Test
    fun transmissionTorrents() {
        val m = trList()
        assertEquals(6, m.size)
        val a = m.getValue(1)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", a.hash)
        assertEquals("t1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", a.key)
        assertEquals("downloading", a.state)
        assertEquals(TorrentGroup.Downloading, a.group)
        assertEquals(0.25, a.progress, 1e-9)
        assertEquals(2048000L, a.dlspeed)
        assertEquals(300L, a.eta)
        assertEquals(2000000000L, a.size) // sizeWhenDone (wanted files), like qBittorrent's `size`
        assertEquals(1500000000L, a.amountLeft)
        assertEquals("sonarr", a.category)
        assertEquals(listOf("sonarr", "4k"), a.tags)
        assertEquals(9, a.seeds)
        assertEquals(2, a.peers)
        assertEquals(-1, a.seedsTotal)
        assertEquals("/downloads/tv", a.savePath)
        assertEquals(1700000000L, a.addedOn)

        val b = m.getValue(2)
        assertEquals("stoppedUP", b.state)
        assertTrue(b.isStopped)
        assertEquals(TorrentGroup.Completed, b.group)
        assertEquals(NO_ETA, b.eta)
        assertEquals(0.0, b.ratio, 0.0)
        assertEquals("", b.category)
        assertEquals(1700000500L, b.completedOn)

        assertEquals("stalledUP", m.getValue(3).state)
        assertEquals(TorrentGroup.Seeding, m.getValue(3).group)
        assertEquals(2.5, m.getValue(3).ratio, 1e-9)
        assertEquals("error", m.getValue(4).state)
        assertEquals(TorrentGroup.Errored, m.getValue(4).group)
        assertEquals("metaDL", m.getValue(5).state)
        // A tracker error (2) doesn't make the torrent errored; status 2 is "checking".
        assertEquals("checkingDL", m.getValue(6).state)
    }

    @Test
    fun transmissionRecentlyActive() {
        val m = trList().toMutableMap()
        Transmission.mergeTorrents(
            m,
            obj(
                """
                {"removed": [2, 4],
                 "torrents": [{"id": 1, "hashString": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "name": "Show.S01E01.1080p",
                   "status": 0, "percentDone": 0.3, "rateDownload": 0}]}
                """,
            ),
            tr,
        )
        assertEquals(setOf(1L, 3L, 5L, 6L), m.keys)
        assertEquals("stoppedDL", m.getValue(1).state)
        assertEquals(TorrentGroup.Paused, m.getValue(1).group)
    }

    @Test
    fun transmissionServer() {
        val stats = obj(
            """
            {"activeTorrentCount": 2, "downloadSpeed": 2048000, "uploadSpeed": 1000, "pausedTorrentCount": 1, "torrentCount": 3,
             "cumulative-stats": {"downloadedBytes": 4000, "uploadedBytes": 2000, "filesAdded": 9, "secondsActive": 100, "sessionCount": 5},
             "current-stats": {"downloadedBytes": 400, "uploadedBytes": 200, "filesAdded": 1, "secondsActive": 10, "sessionCount": 1}}
            """,
        )
        val session = obj(
            """
            {"alt-speed-enabled": true, "alt-speed-down": 50, "alt-speed-up": 20, "speed-limit-down": 1000,
             "speed-limit-down-enabled": true, "speed-limit-up": 100, "speed-limit-up-enabled": false,
             "download-dir": "/downloads", "version": "4.0.5 (a6fe2a64aa)", "rpc-version": 17,
             "units": {"speed-units": ["kB/s", "MB/s", "GB/s", "TB/s"], "speed-bytes": 1000, "size-bytes": 1000, "memory-bytes": 1024}}
            """,
        )
        val sv = Transmission.serverOf(stats, session, trList().values.toList(), 123L)
        assertEquals(2048000L, sv.down)
        assertEquals(1000L, sv.up)
        assertEquals(400L, sv.sessionDown)
        assertEquals(2000L, sv.allTimeUp)
        assertEquals(0.5, sv.ratio!!, 1e-9)
        assertEquals(123L, sv.freeSpace)
        assertTrue(sv.altSpeed)
        assertEquals(50_000L, sv.downLimit) // alt limits apply while alt speed is on
        assertEquals(20_000L, sv.upLimit)

        val normal = Transmission.serverOf(stats, JsonObject(session + ("alt-speed-enabled" to AppJson.parseToJsonElement("false"))), emptyList(), null)
        assertFalse(normal.altSpeed)
        assertEquals(1_000_000L, normal.downLimit)
        assertEquals(0L, normal.upLimit) // limit set but not enabled
    }

    @Test
    fun transmissionDetails() {
        val t = obj(
            """
            {"hashString": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "pieceCount": 10, "pieceSize": 1048576,
             "pieces": "/wA=", "comment": "hello", "creator": "mktorrent 1.1", "dateCreated": 1690000000,
             "downloadDir": "/downloads/tv", "corruptEver": 5, "downloadedEver": 600, "uploadedEver": 300,
             "uploadRatio": 0.5, "peersConnected": 4, "peer-limit": 50, "secondsDownloading": 60, "secondsSeeding": 40,
             "addedDate": 1700000000, "doneDate": 0, "totalSize": 10485760,
             "files": [{"bytesCompleted": 1048576, "length": 2097152, "name": "Show/ep1.mkv"},
                       {"bytesCompleted": 0, "length": 100, "name": "Show/sample.mkv"},
                       {"bytesCompleted": 50, "length": 100, "name": "Show/info.nfo"}],
             "fileStats": [{"bytesCompleted": 1048576, "wanted": true, "priority": 0},
                           {"bytesCompleted": 0, "wanted": false, "priority": 0},
                           {"bytesCompleted": 50, "wanted": true, "priority": 1}],
             "trackerStats": [
               {"announce": "https://tracker.example/announce", "announceState": 1, "hasAnnounced": true,
                "lastAnnounceSucceeded": true, "lastAnnounceResult": "Success", "lastAnnouncePeerCount": 30,
                "seederCount": 120, "leecherCount": 7, "downloadCount": 900, "tier": 0, "nextAnnounceTime": 1700001800},
               {"announce": "udp://broken.example:80", "announceState": 1, "hasAnnounced": true,
                "lastAnnounceSucceeded": false, "lastAnnounceResult": "Connection failed", "seederCount": -1,
                "leecherCount": -1, "downloadCount": -1, "tier": 1, "nextAnnounceTime": 1700000600}]}
            """,
        )
        val d = Transmission.detailsOf(t, 1700000000)
        val p = d.properties!!
        assertEquals(8L, p.piecesHave) // 0xFF 0x00: 8 of 10 pieces
        assertEquals(10L, p.piecesNum)
        assertEquals(5L, p.totalWasted)
        assertEquals(100L, p.timeElapsed)
        assertEquals(10L, p.dlSpeedAvg)
        assertEquals(600L, p.reannounce)
        assertEquals("mktorrent 1.1", p.createdBy)
        assertEquals(-1L, p.completionDate)
        assertEquals(2, d.trackers.size)
        assertEquals(2, d.trackers[0].status)
        assertEquals(120, d.trackers[0].seeds)
        assertEquals("", d.trackers[0].msg)
        assertEquals(4, d.trackers[1].status)
        assertEquals("Connection failed", d.trackers[1].msg)
        assertEquals(3, d.files.size)
        assertEquals(0.5, d.files[0].progress, 1e-9)
        assertEquals(1, d.files[0].priority)
        assertEquals(0, d.files[1].priority) // unwanted = skip
        assertEquals(6, d.files[2].priority) // high
        assertEquals(2, d.files[2].index)
    }

    // ------------------------------------------------------------------ Deluge

    @Test
    fun delugeResponse() {
        assertEquals("true", Deluge.parseResponse("D", "auth.login", """{"result": true, "error": null, "id": 1}""").toString())
        assertEquals(401, expectError { Deluge.parseResponse("D", "web.update_ui", """{"result": null, "error": {"message": "Not authenticated", "code": 1}, "id": 2}""") }.status)
        val notConnected = expectError { Deluge.parseResponse("D", "core.pause_torrent", """{"result": null, "error": {"message": "Unknown method", "code": 2}, "id": 3}""") }
        assertEquals(503, notConnected.status)
        assertTrue(notConnected.message!!.contains("isn't connected to a Deluge daemon"))
        assertTrue(expectError { Deluge.parseResponse("D", "label.get_labels", """{"result": null, "error": {"message": "Unknown method", "code": 2}, "id": 4}""") }.message!!.contains("Label plugin"))
        assertEquals(
            "D: Torrent already in session (abc).",
            expectError { Deluge.parseResponse("D", "core.add_torrent_magnet", """{"result": null, "error": {"message": "AddTorrentError: Torrent already in session (abc).", "code": 3}, "id": 5}""") }.message,
        )
        expectError { Deluge.parseResponse("D", "web.connected", "<!DOCTYPE html><html></html>") }
    }

    @Test
    fun delugeUpdateUi() {
        val ui = obj(
            """
            {"connected": true,
             "torrents": {
               "1111111111111111111111111111111111111111": {"name": "Show.S01E01", "hash": "1111111111111111111111111111111111111111",
                 "state": "Downloading", "progress": 42.5, "download_payload_rate": 512000, "upload_payload_rate": 0, "eta": 120,
                 "total_wanted": 1000, "total_size": 2000, "total_done": 425, "total_uploaded": 0, "all_time_download": 430,
                 "ratio": 0.0, "time_added": 1700000000.123, "completed_time": 0, "num_seeds": 5, "total_seeds": 80,
                 "num_peers": 1, "total_peers": 9, "download_location": "/downloads", "tracker_host": "tracker.example",
                 "label": "tv-sonarr", "active_time": 300, "seeding_time": 0, "distributed_copies": 3.25, "is_finished": false},
               "2222222222222222222222222222222222222222": {"name": "Movie", "state": "Paused", "progress": 100.0,
                 "total_wanted": 100, "total_size": 100, "total_done": 100, "ratio": -1, "eta": 0, "label": "",
                 "is_finished": true, "completed_time": 1700000900},
               "3333333333333333333333333333333333333333": {"name": "Seeding", "state": "Seeding", "progress": 100.0,
                 "upload_payload_rate": 2048, "total_size": 5, "is_finished": true},
               "4444444444444444444444444444444444444444": {"name": "magnet", "state": "Downloading", "progress": 0.0,
                 "total_size": 0, "total_wanted": 0},
               "5555555555555555555555555555555555555555": {"name": "err", "state": "Error", "progress": 10.0, "total_size": 9}
             },
             "filters": {"state": [["All", 5], ["Downloading", 2]], "label": [["", 1], ["movies-radarr", 0], ["tv-sonarr", 1]]},
             "stats": {"max_download": -1, "max_upload": 100.0, "max_num_connections": 200, "num_connections": 6,
               "upload_rate": 2048, "download_rate": 512000, "download_protocol_rate": 100, "upload_protocol_rate": 50,
               "dht_nodes": 312, "has_incoming_connections": true, "free_space": 50000000000, "external_ip": "203.0.113.5"}}
            """,
        )
        val snap = Deluge.snapshotOf(dg, ui, obj("""{"total_payload_download": 9000, "total_payload_upload": 800}"""))
        assertEquals(5, snap.torrents.size)
        assertEquals(listOf("movies-radarr", "tv-sonarr"), snap.categories)
        val byName = snap.torrents.associateBy { it.name }
        val a = byName.getValue("Show.S01E01")
        assertEquals("downloading", a.state)
        assertEquals(0.425, a.progress, 1e-9)
        assertEquals(1000L, a.size)
        assertEquals(575L, a.amountLeft)
        assertEquals("tv-sonarr", a.category)
        assertEquals(80, a.seedsTotal)
        assertEquals(1700000000L, a.addedOn)
        assertEquals("tracker.example", a.tracker)
        assertEquals(3.25, a.availability, 1e-9)
        val b = byName.getValue("Movie")
        assertEquals("stoppedUP", b.state)
        assertEquals(TorrentGroup.Completed, b.group)
        assertEquals(NO_ETA, b.eta)
        assertEquals(0.0, b.ratio, 0.0)
        assertEquals("", b.category)
        assertEquals("uploading", byName.getValue("Seeding").state)
        assertEquals("metaDL", byName.getValue("magnet").state)
        assertEquals("error", byName.getValue("err").state)

        val sv = snap.server
        assertEquals(512000L, sv.down)
        assertEquals(2048L, sv.up)
        assertEquals(0L, sv.downLimit) // -1 = unlimited
        assertEquals(102400L, sv.upLimit) // KiB/s
        assertEquals(312L, sv.dhtNodes)
        assertEquals(6L, sv.peers)
        assertEquals("connected", sv.connection)
        assertEquals(50000000000L, sv.freeSpace)
        assertEquals(9000L, sv.sessionDown)
        assertFalse(sv.altSpeed)
        assertNull(sv.ratio)
    }

    @Test
    fun delugeDisconnectedUpdateUi() {
        // Not connected: torrents/filters are null and stats only has the configured limits.
        val snap = Deluge.snapshotOf(dg, obj("""{"connected": false, "torrents": null, "filters": null, "stats": {"max_download": -1, "max_upload": -1, "max_num_connections": 200}}"""), null)
        assertTrue(snap.torrents.isEmpty())
        assertEquals("unknown", snap.server.connection)
    }

    @Test
    fun delugeDetails() {
        val d = Deluge.detailsOf(
            obj(
                """
                {"files": [{"index": 0, "path": "Show/ep1.mkv", "size": 1000, "offset": 0},
                           {"index": 1, "path": "Show/sample.mkv", "size": 10, "offset": 1000},
                           {"index": 2, "path": "Show/extra.mkv", "size": 10, "offset": 1010}],
                 "file_progress": [0.5, 0.0, 1.0], "file_priorities": [4, 0, 7],
                 "trackers": [{"url": "https://tracker.example/announce", "tier": 0, "message": "", "fails": 0, "verified": true},
                              {"url": "udp://backup.example:1337", "tier": 1, "fails": 3, "message": "timed out"}],
                 "tracker_host": "tracker.example", "tracker_status": "Announce OK", "comment": "c", "creator": "qBittorrent v4.6",
                 "num_pieces": 40, "piece_length": 262144, "total_done": 500, "total_uploaded": 50, "all_time_download": 510,
                 "ratio": 0.1, "time_added": 1700000000.5, "completed_time": 0, "active_time": 100, "seeding_time": 0,
                 "next_announce": 1500, "download_location": "/downloads", "total_size": 1020, "max_connections": -1,
                 "num_seeds": 3, "num_peers": 2, "last_seen_complete": 1700000100, "max_download_speed": -1, "max_upload_speed": 50}
                """,
            ),
        )
        val p = d.properties!!
        assertEquals("/downloads", p.savePath)
        assertEquals(510L, p.totalDownloaded)
        assertEquals(5, p.connections)
        assertEquals(0, p.connectionsLimit)
        assertEquals(-1L, p.dlLimit)
        assertEquals(51200L, p.upLimit)
        assertEquals(1500L, p.reannounce)
        assertEquals(-1L, p.piecesNum)
        assertEquals(1700000100L, p.lastSeen)
        assertEquals(2, d.trackers[0].status)
        assertEquals(4, d.trackers[1].status)
        assertEquals("timed out", d.trackers[1].msg)
        assertEquals(listOf(1, 0, 7), d.files.map { it.priority })
        assertEquals(0.5, d.files[0].progress, 1e-9)
    }

    @Test
    fun delugePriorities() {
        assertEquals(0, Deluge.toDelugePriority(0))
        assertEquals(4, Deluge.toDelugePriority(1))
        assertEquals(6, Deluge.toDelugePriority(6))
        assertEquals(7, Deluge.toDelugePriority(7))
        assertEquals(1, Deluge.fromDelugePriority(1)) // low shows as normal
        assertEquals(1, Deluge.fromDelugePriority(4))
        assertEquals(6, Deluge.fromDelugePriority(5))
        assertEquals(7, Deluge.fromDelugePriority(7))
    }
}
