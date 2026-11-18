package app.bosunarr.feature.autobrr

import app.bosunarr.net.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing against Autobrr v1.90 JSON shapes (json tags from internal/domain; synthetic data). */
class AutobrrParsingTest {
    private inline fun <reified T> decode(s: String): T = AppJson.decodeFromString(s)

    @Test
    fun config() {
        val c = decode<AbConfig>(
            """{"application": "/usr/local/bin/autobrr", "config_dir": "/config", "database": "sqlite", "host": "0.0.0.0",
               "port": 7474, "log_level": "INFO", "log_path": "", "log_max_size": 50, "log_max_backups": 3, "base_url": "/",
               "check_for_updates": true, "version": "1.90.0", "commit": "abc1234", "date": "2026-11-01T21:20:00Z"}""",
        )
        assertEquals("1.90.0", c.version)
    }

    @Test
    fun statsAndActivity() {
        val st = decode<AbStats>(
            """{"total_count": 1200, "filtered_count": 1150, "filter_rejected_count": 50, "push_approved_count": 300,
               "push_rejected_count": 700, "push_error_count": 4}""",
        )
        assertEquals(AbTotals(1200, 300, 700, 4), st.totals())

        val act = decode<AbActivity>(
            """{"days": 7, "daily": [
               {"date": "2026-11-12", "matched_count": 10, "push_approved_count": 3, "push_rejected_count": 6, "push_error_count": 1},
               {"date": "2026-11-13", "matched_count": 0, "push_approved_count": 0, "push_rejected_count": 0, "push_error_count": 0},
               {"date": "2026-11-14", "matched_count": 5, "push_approved_count": 2, "push_rejected_count": 3, "push_error_count": 0}]}""",
        )
        assertEquals(AbTotals(15, 5, 9, 1), act.totals())
        assertEquals(AbTotals(0, 0, 0, 0), decode<AbActivity>("""{"days": 1, "daily": null}""").totals())
    }

    private val releases = """
        {"data": [
          {"id": 9001, "filter_status": "FILTER_APPROVED", "rejections": [], "indexer": {"id": 3, "name": "TorrentLeech",
           "identifier": "torrentleech", "identifier_external": "TorrentLeech"}, "filter": "TV 1080p", "protocol": "torrent",
           "implementation": "IRC", "timestamp": "2026-11-18T09:12:44Z", "announce_type": "NEW", "type": "episode",
           "info_url": "https://tracker.example/torrent/1", "download_url": "https://tracker.example/dl/1", "group_id": "",
           "torrent_id": "1", "name": "Some.Show.S02E05.1080p.WEB.h264-GROUP", "normalized_hash": "x", "size": 1503238553,
           "title": "Some Show", "sub_title": "", "category": "TV :: Episodes HD", "season": 2, "episode": 5, "year": 0,
           "month": 0, "day": 0, "resolution": "1080p", "source": "WEB", "codec": ["H.264"], "container": "", "hdr": null,
           "group": "GROUP", "proper": false, "repack": false, "website": "", "hybrid": false, "edition": null, "cut": null,
           "media_processing": "", "origin": "", "uploader": "", "record_label": "", "pre_time": "",
           "action_status": [
             {"id": 1, "status": "PUSH_REJECTED", "action": "Send to Sonarr", "action_id": 2, "type": "SONARR", "client": "sonarr",
              "filter": "TV 1080p", "filter_id": 4, "rejections": ["Not an upgrade for existing episode file(s)"],
              "release_id": 9001, "timestamp": "2026-11-18T09:12:45Z"}]},
          {"id": 9000, "filter_status": "FILTER_APPROVED", "rejections": null, "indexer": {"id": 5, "name": "IPTorrents",
           "identifier": "iptorrents", "identifier_external": ""}, "filter": "Movies 2160p", "timestamp": "2026-11-18T08:00:00Z",
           "name": "A.Movie.2026.2160p.UHD.BluRay.x265-GRP", "size": 0,
           "action_status": [
             {"id": 2, "status": "PUSH_ERROR", "action": "qBit", "type": "QBITTORRENT", "client": "qbit", "rejections": null,
              "timestamp": "2026-11-18T08:00:01Z"},
             {"id": 3, "status": "PUSH_APPROVED", "action": "Radarr", "type": "RADARR", "rejections": [], "timestamp": "2026-11-18T08:00:02Z"}]},
          {"id": 8999, "filter_status": "FILTER_APPROVED", "rejections": null, "indexer": {"id": 5, "name": "IPTorrents",
           "identifier": "iptorrents"}, "filter": "Movies", "timestamp": "2026-11-17T08:00:00Z", "name": "Old.Movie",
           "action_status": null}
        ],
        "count": 1532, "next_cursor": 0}
    """

    @Test
    fun releasePage() {
        val page = decode<AbReleasePage>(releases)
        assertEquals(1532L, page.count)
        assertEquals(3, page.data.size)
        val r = page.data[0]
        assertEquals("TorrentLeech", r.indexer.name)
        assertEquals("TV 1080p", r.filter)
        assertEquals(1503238553L, r.size)
        assertEquals("Some.Show.S02E05.1080p.WEB.h264-GROUP", r.name)
        assertEquals(1, r.actions.size)
        assertEquals("SONARR", r.actions[0].type)
        // Go nil slices arrive as null and become empty.
        assertTrue(page.data[1].rejections.isEmpty())
        assertTrue(page.data[2].actions.isEmpty())
    }

    @Test
    fun outcomesAndReasons() {
        val (rejected, mixed, bare) = decode<AbReleasePage>(releases).data
        assertEquals(AbOutcome.Rejected, releaseOutcome(rejected))
        assertEquals(listOf("Send to Sonarr: Not an upgrade for existing episode file(s)"), rejectionReasons(rejected))
        // One action reached Radarr: that's a grab, even though qBittorrent failed.
        assertEquals(AbOutcome.Approved, releaseOutcome(mixed))
        assertEquals(listOf("qBit: error"), rejectionReasons(mixed))
        assertEquals(AbOutcome.NoAction, releaseOutcome(bare))
        assertTrue(rejectionReasons(bare).isEmpty())

        val errored = AbRelease(actions = listOf(AbActionStatus(status = "PUSH_REJECTED"), AbActionStatus(status = "PUSH_ERROR", type = "SABNZBD", rejections = listOf("connection refused"))))
        assertEquals(AbOutcome.Error, releaseOutcome(errored))
        assertEquals(listOf("Rejected", "Error", "Pending", "Approved"), listOf("PUSH_REJECTED", "PUSH_ERROR", "PENDING", "PUSH_APPROVED").map { pushStatusLabel(it) })
        assertEquals(listOf("Action: rejected", "SABnzbd: connection refused"), rejectionReasons(errored))
        assertEquals(AbOutcome.Pending, releaseOutcome(AbRelease(actions = listOf(AbActionStatus(status = "PENDING")))))
        // Legacy filter rejections stored on the release itself.
        assertEquals(listOf("[size] not matching"), rejectionReasons(AbRelease(rejections = listOf(" [size] not matching ", ""))))
    }

    @Test
    fun actionTypes() {
        assertEquals("qBittorrent", actionTypeLabel("QBITTORRENT"))
        assertEquals("Deluge", actionTypeLabel("DELUGE_V2"))
        assertEquals("Whisparr", actionTypeLabel("WHISPARR_V3"))
        assertEquals("Watch folder", actionTypeLabel("WATCH_FOLDER"))
        assertEquals("Something new", actionTypeLabel("SOMETHING_NEW"))
    }

    @Test
    fun goZeroTimeIsNever() {
        assertNull(abInstant("0001-01-01T00:00:00Z"))
        assertNull(abInstant(null))
        assertEquals(1763457164L, abInstant("2025-11-18T09:12:44Z")?.epochSecond)
    }

    @Test
    fun filtersAndCounts() {
        val list = decode<List<AbFilter>>(
            """[{"id": 4, "name": "TV 1080p", "enabled": true, "created_at": "2025-01-01T00:00:00Z", "updated_at": "2026-11-01T00:00:00Z",
                "priority": 10, "smart_episode": true, "actions_count": 2, "actions_enabled_count": 1, "is_auto_updated": false,
                "indexers": [{"id": 3, "name": "TorrentLeech", "identifier": "torrentleech", "enabled": true}]},
               {"id": 5, "name": "Movies 2160p", "enabled": false, "priority": 0, "smart_episode": false, "actions_count": 0,
                "actions_enabled_count": 0, "is_auto_updated": false, "indexers": null}]""",
        )
        assertEquals(2, list.size)
        assertEquals(1, list[0].actionsEnabled)
        assertEquals(1, list[0].indexers.size)
        assertTrue(list[1].indexers.isEmpty())

        val top = decode<AbTop>("""{"days": 7, "top": [{"filter": "TV 1080p", "matched_count": 40, "push_approved_count": 12}]}""")
        assertEquals(40L, filterCount(top, "TV 1080p")?.matched)
        // Short list: a filter that isn't on it matched nothing.
        assertEquals(0L, filterCount(top, "Movies 2160p")?.matched)
        // Full top 10: a missing filter's count is unknown.
        val full = AbTop(7, (1..10).map { AbTopRow(filter = "F$it", matched = 100L - it) })
        assertNull(filterCount(full, "Movies 2160p"))
        assertNull(filterCount(null, "TV 1080p"))
    }

    private val networks = """
        [{"id": 1, "name": "TorrentLeech", "enabled": true, "server": "irc.torrentleech.org", "port": 7021, "tls": true,
          "tls_skip_verify": false, "pass": "<redacted>", "nick": "me_bot", "auth": {"mechanism": "SASL_PLAIN", "account": "me"},
          "invite_command": "", "use_bouncer": false, "bouncer_addr": "", "bot_mode": false, "current_nick": "me_bot",
          "preferred_nick": "me_bot", "use_proxy": false, "proxy_id": 0, "proxy": null,
          "channels": [{"id": 1, "enabled": true, "name": "#tlannounces", "password": "", "detached": false, "state": "Monitoring",
            "monitoring": true, "monitoring_since": "2026-11-18T06:00:00Z", "last_announce": "2026-11-18T09:12:44Z", "connection_errors": null}],
          "connected": true, "connected_since": "2026-11-18T05:59:00Z", "connection_errors": [], "healthy": true},
         {"id": 2, "name": "IPTorrents", "enabled": true, "server": "irc.iptorrents.com", "port": 6697, "tls": true,
          "nick": "me_bot", "channels": [
            {"id": 2, "enabled": true, "name": "#ipt.announce", "state": "InviteFailed", "monitoring": false,
             "monitoring_since": "0001-01-01T00:00:00Z", "last_announce": "0001-01-01T00:00:00Z", "connection_errors": ["invite bot did not respond"]},
            {"id": 3, "enabled": true, "name": "#ipt.chat", "state": "Monitoring", "monitoring": true,
             "monitoring_since": "2026-11-18T06:00:00Z", "last_announce": "0001-01-01T00:00:00Z", "connection_errors": []}],
          "connected": true, "connected_since": "2026-11-18T05:59:00Z", "connection_errors": [], "healthy": false},
         {"id": 3, "name": "Broken", "enabled": true, "server": "irc.broken.example", "port": 6697, "channels": [],
          "connected": false, "connected_since": "0001-01-01T00:00:00Z", "connection_errors": ["NickServ authentication failed"], "healthy": false},
         {"id": 4, "name": "Old", "enabled": false, "server": "irc.old.example", "port": 6667,
          "channels": [{"id": 9, "enabled": true, "name": "#old", "detached": false, "state": "", "monitoring": false}],
          "connected": false, "connected_since": "0001-01-01T00:00:00Z", "connection_errors": [], "healthy": false}]
    """

    @Test
    fun ircHealth() {
        val list = decode<List<AbNetwork>>(networks)
        assertEquals(listOf(AbNetState.Healthy, AbNetState.Degraded, AbNetState.Disconnected, AbNetState.Off), list.map { networkState(it) })
        assertTrue(list[1].channels[0].errors.isNotEmpty())
        assertTrue(list[0].channels[0].errors.isEmpty())
        assertNull(abInstant(list[1].channels[0].lastAnnounce))

        assertNull(networkProblem(list[0]))
        assertEquals("#ipt.announce: invite bot did not respond", networkProblem(list[1]))
        assertEquals("NickServ authentication failed", networkProblem(list[2]))
        assertNull(networkProblem(list[3]))

        assertFalse(channelHasProblem(list[0].channels[0]))
        assertTrue(channelHasProblem(list[1].channels[0]))
        // Monitoring with no announces yet is quiet, not broken.
        assertFalse(channelHasProblem(list[1].channels[1]))

        val sum = ircSummary(list)
        assertEquals(3, sum.enabled)
        assertEquals(1, sum.healthy)
        assertEquals(listOf("IPTorrents", "Broken"), sum.problems.map { it.first })
    }

    @Test
    fun channelLabels() {
        assertEquals("Watching announces", channelStateLabel(AbChannel(state = "Monitoring", enabled = true)))
        assertEquals("Invite bot not found", channelStateLabel(AbChannel(state = "InviteFailedNoSuchNick", enabled = true)))
        assertEquals("Waiting for the invite bot", channelStateLabel(AbChannel(state = "AwaitingInviteBot", enabled = true)))
        // Disabled networks report no state: fall back to the stored flags.
        assertEquals("Off", channelStateLabel(AbChannel(state = "", enabled = false)))
        assertEquals("Not joined", channelStateLabel(AbChannel(state = "", enabled = true)))
        assertEquals("SomethingNew", channelStateLabel(AbChannel(state = "SomethingNew", enabled = true)))
    }

    @Test
    fun feeds() {
        val f = decode<List<AbFeed>>(
            """[{"id": 1, "name": "BTN RSS", "indexer": {"id": 7, "name": "BroadcasTheNet", "identifier": "btn"}, "type": "RSS",
                "enabled": true, "url": "https://feed.example/rss", "interval": 15, "timeout": 60, "max_age": 0, "categories": [],
                "capabilities": null, "api_key": "", "cookie": "", "settings": null, "created_at": "2025-01-01T00:00:00Z",
                "updated_at": "2025-01-01T00:00:00Z", "last_run": "2026-11-18T09:00:00Z", "last_run_data": "ok",
                "next_run": "0001-01-01T00:00:00Z"}]""",
        )
        assertEquals("BTN RSS", f[0].name)
        assertEquals(15, f[0].interval)
        assertTrue(abInstant(f[0].lastRun) != null)
        assertNull(abInstant(f[0].nextRun))
    }
}
