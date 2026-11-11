package app.bosunarr.feature.usenet

import app.bosunarr.data.ServiceConfig
import app.bosunarr.net.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing against the response shapes in the SABnzbd and NZBGet API docs (synthetic data). */
class UsenetParsingTest {
    private val sab = ServiceConfig(id = "s1", type = "sabnzbd", name = "SAB", url = "http://sab")
    private val nzb = ServiceConfig(id = "n1", type = "nzbget", name = "NZBGet", url = "http://nzbget")

    private fun obj(s: String): JsonObject = AppJson.parseToJsonElement(s).jsonObject
    private fun arr(s: String): JsonArray = AppJson.parseToJsonElement(s).jsonArray

    @Test
    fun sabQueue() {
        val snap = Sab.parseQueue(
            sab,
            obj(
                """
                {"queue": {"status": "Downloading", "speedlimit": "9", "speedlimit_abs": "4718592.0", "paused": false,
                 "noofslots_total": 2, "noofslots": 2, "limit": 10, "start": 0, "timeleft": "0:16:44", "speed": "1.3 M",
                 "kbpersec": "1296.02", "size": "1.2 GB", "sizeleft": "1.2 GB", "mb": "1277.65", "mbleft": "1271.58",
                 "diskspace1": "161.16", "diskspace2": "150.00", "diskspacetotal1": "465.21", "version": "4.3.0",
                 "finishaction": null,
                 "slots": [
                  {"status": "Downloading", "index": 0, "password": "", "avg_age": "2895d", "script": "None",
                   "direct_unpack": "10/30", "mb": "1277.65", "mbleft": "1271.59", "mbmissing": "0.0", "size": "1.2 GB",
                   "sizeleft": "1.2 GB", "filename": "Show.S04E11.720p", "labels": [], "priority": "Normal", "cat": "tv",
                   "timeleft": "0:16:44", "percentage": "0", "nzo_id": "SABnzbd_nzo_p86tgx", "unpackopts": "3"},
                  {"status": "Paused", "index": 1, "mb": "100.00", "mbleft": "50.00", "filename": "Movie.2020",
                   "labels": ["DUPLICATE"], "priority": "1", "cat": "*", "timeleft": "0:00:00", "percentage": "50",
                   "nzo_id": "SABnzbd_nzo_ksfai6"}
                 ]}}
                """,
            ),
        )
        assertEquals((1296.02 * 1024).toLong(), snap.server.down)
        assertEquals(4718592L, snap.server.limit)
        assertFalse(snap.server.paused)
        assertEquals(16 * 60 + 44L, snap.server.timeLeft)
        assertEquals((150.0 * 1024 * 1024 * 1024).toLong(), snap.server.freeDisk)
        assertEquals("4.3.0", snap.server.version)
        assertEquals(2, snap.items.size)
        val a = snap.items[0]
        assertEquals("SABnzbd_nzo_p86tgx", a.id)
        assertEquals(NzbState.Downloading, a.state)
        assertEquals("tv", a.category)
        assertEquals(NzbPriority.Normal, a.priority)
        assertEquals((1277.65 * 1024 * 1024).toLong(), a.size)
        assertEquals(1004L, a.eta)
        val b = snap.items[1]
        assertEquals(NzbState.Paused, b.state)
        assertEquals("", b.category)
        assertEquals(NzbPriority.High, b.priority)
        assertEquals(0.5f, b.progress, 0.001f)
        assertNull(b.eta)
        assertEquals(listOf("DUPLICATE"), b.labels)
    }

    @Test
    fun sabHistory() {
        val list = Sab.parseHistory(
            sab,
            obj(
                """
                {"history": {"noofslots": 2, "ppslots": 0, "day_size": "1.9 G", "last_history_update": 1469210913,
                 "slots": [
                  {"nzo_id": "SABnzbd_nzo_a", "name": "Good.Show", "nzb_name": "Good.Show.nzb", "status": "Completed",
                   "fail_message": "", "bytes": 2436906376, "size": "2.3 GB", "category": "tv", "completed": 1469172988,
                   "download_time": 64, "storage": "/complete/Good.Show", "retry": 0},
                  {"nzo_id": "SABnzbd_nzo_b", "name": "Bad.Show", "status": "Failed",
                   "fail_message": "Repair failed, not enough repair blocks", "bytes": 1000, "category": "*",
                   "completed": 1469172000, "download_time": 10, "retry": 1}
                 ]}}
                """,
            ),
        )
        assertEquals(2, list.size)
        assertEquals(NzbOutcome.Success, list[0].outcome)
        assertEquals(2436906376L, list[0].size)
        assertFalse(list[0].canRetry)
        assertEquals(NzbOutcome.Failed, list[1].outcome)
        assertEquals("Repair failed, not enough repair blocks", list[1].reason)
        assertTrue(list[1].canRetry)
        assertEquals("", list[1].category)
    }

    @Test
    fun sabClock() {
        assertEquals(3723L, Sab.clock("1:02:03"))
        assertEquals(86400L + 3723L, Sab.clock("1:01:02:03"))
        assertNull(Sab.clock("0:00:00"))
        assertNull(Sab.clock("unknown"))
    }

    @Test
    fun nzbgetQueue() {
        val status = obj(
            """
            {"RemainingSizeLo": 1048576, "RemainingSizeHi": 0, "RemainingSizeMB": 1, "DownloadRate": 2097152,
             "DownloadLimit": 0, "DownloadPaused": false, "FreeDiskSpaceLo": 0, "FreeDiskSpaceHi": 2,
             "FreeDiskSpaceMB": 8192, "DaySizeLo": 5242880, "DaySizeHi": 0, "ServerStandBy": false}
            """,
        )
        val groups = arr(
            """
            [{"NZBID": 7, "NZBName": "Big.Download", "Kind": "NZB", "Category": "movies", "Status": "DOWNLOADING",
              "FileSizeLo": 3000000000, "FileSizeHi": 1, "FileSizeMB": 6957,
              "RemainingSizeLo": 1048576, "RemainingSizeHi": 0, "PausedSizeLo": 0, "PausedSizeHi": 0,
              "MaxPriority": 100, "Health": 995, "CriticalHealth": 900, "PostStageProgress": 0},
             {"NZBID": 8, "NZBName": "Unpacking.One", "Kind": "NZB", "Category": "", "Status": "UNPACKING",
              "FileSizeLo": 1000, "FileSizeHi": 0, "RemainingSizeLo": 0, "RemainingSizeHi": 0, "PausedSizeLo": 0,
              "PausedSizeHi": 0, "MaxPriority": 0, "Health": 1000, "CriticalHealth": 900, "PostStageProgress": 500,
              "PostInfoText": "Unpacking Unpacking.One"}]
            """,
        )
        val snap = Nzbget.parseQueue(nzb, status, groups)
        assertEquals(2097152L, snap.server.down)
        assertEquals(2L shl 32, snap.server.freeDisk)
        assertEquals(5242880L, snap.server.today)
        assertEquals(1048576L, snap.server.left)
        val a = snap.items[0]
        assertEquals("7", a.id)
        assertEquals((1L shl 32) + 3_000_000_000L, a.size)
        assertEquals(NzbPriority.VeryHigh, a.priority)
        assertEquals(NzbState.Downloading, a.state)
        assertEquals(0L, a.eta) // 1 MiB left at 2 MiB/s
        assertEquals(listOf("HEALTH 99.5%"), a.labels)
        val b = snap.items[1]
        assertEquals(NzbState.Processing, b.state)
        assertEquals("Unpacking Unpacking.One", b.stateLabel)
        assertEquals(0.5f, b.progress, 0.001f)
    }

    @Test
    fun nzbgetHistory() {
        val list = Nzbget.parseHistory(
            nzb,
            arr(
                """
                [{"NZBID": 3, "Kind": "NZB", "Name": "Ok.Thing", "Category": "tv", "FileSizeLo": 500, "FileSizeHi": 0,
                  "HistoryTime": 1700000000, "Status": "SUCCESS/ALL", "DownloadTimeSec": 30, "Health": 1000},
                 {"NZBID": 4, "Kind": "NZB", "Name": "Broken.Thing", "Category": "", "FileSizeLo": 500, "FileSizeHi": 0,
                  "HistoryTime": 1700000100, "Status": "FAILURE/HEALTH", "DownloadTimeSec": 30, "Health": 712},
                 {"NZBID": 5, "Kind": "DUP", "Name": "Hidden", "Status": "SUCCESS/HIDDEN"}]
                """,
            ),
            limit = 10, failedOnly = false,
        )
        assertEquals(2, list.size)
        assertEquals(NzbOutcome.Success, list[0].outcome)
        assertNull(list[0].reason)
        assertEquals(NzbOutcome.Failed, list[1].outcome)
        assertEquals("Too many missing articles (health 71.2%)", list[1].reason)
        assertTrue(list[1].canRetry)
        val failed = Nzbget.parseHistory(nzb, arr("""[{"NZBID": 3, "Kind": "NZB", "Name": "x", "Status": "SUCCESS/ALL"}]"""), 10, failedOnly = true)
        assertTrue(failed.isEmpty())
    }
}
