package app.bosunarr.feature.tdarr

import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceException
import app.bosunarr.ui.common.bytes
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing against Tdarr server API shapes (synthetic data, shaped like the answers Homepage, Homarr, the Home
 * Assistant integration and tdarr-exporter decode).
 */
class TdarrParsingTest {
    private fun json(s: String): JsonElement = AppJson.parseToJsonElement(s)

    @Test
    fun status() {
        val st = parseTdStatus(json("""{"status": "good", "isProduction": true, "os": "linux", "version": "2.77.01", "buildDate": "2026_05_29T12_20_24z", "uptime": 93784}"""))
        assertEquals("2.77.01", st.version)
        assertEquals("linux", st.os)
        assertEquals(93784L, st.uptimeSeconds)
        assertTrue(st.healthy)
        assertFalse(parseTdStatus(json("""{"status": "bad"}""")).healthy)
    }

    @Test(expected = ServiceException::class)
    fun statusThatIsNotAnObjectIsAnError() {
        parseTdStatus(json("""["nope"]"""))
    }

    @Test
    fun statistics() {
        val st = parseTdStats(
            json(
                """
                {"_id": "statistics", "totalFileCount": 1500, "totalTranscodeCount": 850, "totalHealthCheckCount": 1400,
                 "sizeDiff": 45.75, "tdarrScore": "78.50", "healthCheckScore": "93.33",
                 "table0Count": 5, "table1Count": 10, "table2Count": 600, "table3Count": 15,
                 "table4Count": 20, "table5Count": 1300, "table6Count": 30}
                """,
            ),
        )
        assertEquals(1500, st.files)
        assertEquals(850, st.transcodes)
        assertEquals(1400, st.healthChecks)
        assertEquals(45.75, st.savedGb, 0.001)
        assertEquals(5, st.hold)
        assertEquals(30, st.queue) // table1 + table4
        assertEquals(45, st.errors) // table3 + table6
        assertEquals(600, st.transcodeDone)
        assertEquals(1300, st.healthDone)
    }

    @Test
    fun statisticsPreferViewableCountsAndAcceptStrings() {
        val st = parseTdStats(
            json(
                """
                {"totalFileCount": "200", "sizeDiff": "-1.5", "table1Count": 40, "table1ViewableCount": 12,
                 "table4Count": "3", "table3Count": 2, "table6Count": 0}
                """,
            ),
        )
        assertEquals(200, st.files)
        assertEquals(-1.5, st.savedGb, 0.001)
        assertEquals(12, st.transcodeQueue) // disabled libraries left out, as in Tdarr's own tables
        assertEquals(15, st.queue)
        assertEquals(2, st.errors)
        assertEquals(0, st.hold)
    }

    @Test
    fun nodesWithWorkers() {
        val nodes = parseTdNodes(
            json(
                """
                {"zNode1": {"_id": "zNode1", "nodeName": "Tower", "remoteAddress": "192.168.1.11",
                   "workerLimits": {"transcodecpu": 2, "transcodegpu": 1, "healthcheckcpu": 1, "healthcheckgpu": 0},
                   "nodePaused": false,
                   "workers": {
                     "w-tc": {"_id": "w-tc", "workerType": "transcodegpu", "idle": false, "file": "/media/tv/Show/Season 1/Show S01E01.mkv",
                       "originalfileSizeInGbytes": 4.5, "percentage": 45.75, "fps": 125, "ETA": "0:01:30", "status": "Processing",
                       "job": {"type": "transcode", "start": 1700000000}, "lastPluginDetails": {"source": "community", "number": "3"},
                       "outputFileSizeInGbytes": 2.1, "estSize": 4.2},
                     "w-hc": {"_id": "w-hc", "workerType": "healthcheckcpu", "idle": true, "file": "", "percentage": 0, "fps": 0, "ETA": ""}
                   },
                   "resStats": {"process": {"uptime": 14400}, "os": {"cpuPerc": "85.40", "memUsedGB": "6.2", "memTotalGB": "16.0"}}},
                 "aNode2": {"_id": "aNode2", "nodeName": "Attic", "nodePaused": true, "workers": {}}}
                """,
            ),
        )
        assertEquals(listOf("Attic", "Tower"), nodes.map { it.name })
        val attic = nodes[0]
        assertTrue(attic.paused)
        assertTrue(attic.busy.isEmpty())

        val tower = nodes[1]
        assertFalse(tower.paused)
        assertEquals("192.168.1.11", tower.address)
        assertEquals(85.4, tower.cpuPercent!!, 0.001)
        assertEquals(16.0, tower.memTotalGb!!, 0.001)
        assertEquals(2, tower.workers.size)
        assertEquals(1, tower.busy.size)
        val w = tower.busy.single()
        assertEquals("transcodegpu", w.type)
        assertEquals(45.75, w.percent!!, 0.001)
        assertEquals(125.0, w.fps!!, 0.001)
        assertEquals(90L, w.etaSeconds)
        assertEquals("3", w.step)
        assertEquals("4.5 GB", bytes(w.originalBytes))
        assertEquals("4.2 GB", bytes(w.estimatedBytes))
        assertEquals("Transcode CPU 2 · GPU 1 · Health check CPU 1", limitsLine(tower.limits))
    }

    @Test
    fun nodesNotAnObjectIsAnError() {
        try {
            parseTdNodes(json("""[]"""))
            throw AssertionError("expected an exception")
        } catch (_: ServiceException) {
        }
    }

    @Test
    fun eta() {
        assertEquals(90L, parseEta("0:01:30"))
        assertEquals(3723L, parseEta("1:02:03"))
        assertEquals(90061L, parseEta("1:01:01:01"))
        assertEquals(75L, parseEta("01:15"))
        assertNull(parseEta(""))
        assertNull(parseEta("Calculating..."))
        assertNull(parseEta("0:xx:10"))
        assertNull(parseEta(null))
    }

    @Test
    fun workerLabels() {
        assertEquals("Transcode · GPU", workerTypeLabel("transcodegpu"))
        assertEquals("Health check · CPU", workerTypeLabel("healthcheckcpu"))
        assertEquals("flowworker", workerTypeLabel("flowworker"))
        assertEquals("Worker", workerTypeLabel(""))
    }

    private fun worker(percent: Double? = 45.75, fps: Double? = 125.0, eta: Long? = 90, est: Long? = null, out: Long? = null, orig: Long? = gbToBytes(4.5)) =
        TdWorker("w", "transcodecpu", "/x.mkv", percent, fps, eta, "Processing", null, false, orig, est, out)

    @Test
    fun workerLines() {
        assertEquals("45% · 125 fps · 1m 30s left", workerProgressLine(worker()))
        assertEquals("100%", workerProgressLine(worker(percent = 140.0, fps = 0.0, eta = 0)))
        assertEquals("", workerProgressLine(worker(percent = null, fps = null, eta = null)))
        assertEquals("4.5 GB → about 4.2 GB", workerSizeLine(worker(est = gbToBytes(4.2), out = gbToBytes(2.1))))
        assertEquals("4.5 GB → 2.1 GB so far", workerSizeLine(worker(out = gbToBytes(2.1))))
        assertEquals("4.5 GB", workerSizeLine(worker()))
        assertNull(workerSizeLine(worker(orig = null)))
    }

    @Test
    fun limits() {
        assertEquals("Transcode GPU 1", limitsLine(mapOf("transcodecpu" to 0, "transcodegpu" to 1, "healthcheckcpu" to 0)))
        assertNull(limitsLine(emptyMap()))
    }

    @Test
    fun libraryList() {
        val libs = parseTdLibraries(json("""[{"_id": "L1", "name": "Shows", "folder": "/media/tv", "priority": 0}, {"_id": "L2"}, {"name": "no id"}]"""))
        assertEquals(listOf("L1" to "Shows", "L2" to "L2"), libs)
    }

    @Test
    fun pies() {
        val lib = parseTdPies(
            "L1", "Shows",
            json(
                """
                {"pieStats": {"totalFiles": 1000, "totalTranscodeCount": 700, "sizeDiff": 30.25, "totalHealthCheckCount": 950,
                  "status": {
                    "transcode": [{"name": "Not required", "value": 400}, {"name": "Transcode success", "value": 250},
                                  {"name": "Transcode error", "value": 12}, {"name": "Queued", "value": 8}, {"name": "Hold", "value": 3}],
                    "healthcheck": [{"name": "Success", "value": 880}, {"name": "Error", "value": 18}, {"name": "Queued", "value": 5}]
                  },
                  "video": {"codecs": [{"name": "h264", "value": 370}, {"name": "hevc", "value": 620}, {"name": "av1", "value": 10}],
                            "containers": [{"name": "mkv", "value": 920}], "resolutions": [{"name": "1080p", "value": 700}]},
                  "audio": {"codecs": [], "containers": []}}}
                """,
            ),
        )
        assertEquals("Shows", lib.name)
        assertEquals(1000, lib.files)
        assertTrue(lib.loaded)
        assertEquals(13, lib.queued)
        assertEquals(250, lib.transcoded)
        assertEquals(400, lib.notRequired)
        assertEquals(30, lib.errors)
        assertEquals(listOf("hevc", "h264", "av1"), lib.codecs.map { it.first })
    }

    @Test
    fun piesAcceptCamelCaseHealthCheckAndMissingStatuses() {
        val lib = parseTdPies("L2", "Movies", json("""{"pieStats": {"totalFiles": 5, "status": {"healthCheck": [{"name": "Error", "value": 2}]}, "video": {}}}"""))
        assertEquals(2, lib.errors)
        assertEquals(0, lib.queued)
        assertTrue(lib.codecs.isEmpty())
    }

    @Test
    fun files() {
        val page = parseTdFiles(
            json(
                """
                {"array": [
                  {"_id": "/media/movies/Film (2020)/Film (2020).mkv", "file": "/media/movies/Film (2020)/Film (2020).mkv",
                   "file_size": 2048, "container": "mkv", "video_codec_name": "h264", "video_resolution": "1080p",
                   "HealthCheck": "Success", "TranscodeDecisionMaker": "Queued"},
                  {"_id": "w1", "file": "D:\\Media\\clip.mp4", "file_size": 0.5, "HealthCheck": "Error", "TranscodeDecisionMaker": ""}
                 ], "totalCount": 42}
                """,
            ),
        )
        assertEquals(42, page.total)
        val a = page.items[0]
        assertEquals("Film (2020).mkv", a.name)
        assertEquals("/media/movies/Film (2020)", a.folder)
        assertEquals("2.0 GB", bytes(a.sizeBytes))
        assertEquals("h264", a.codec)
        assertEquals("Queued", a.transcode)
        val b = page.items[1]
        assertEquals("clip.mp4", b.name)
        assertEquals("D:\\Media", b.folder)
        assertEquals("Error", b.health)
        assertNull(b.transcode)
    }

    @Test
    fun savedSpace() {
        // Tdarr reports GB; shown with the same number Tdarr's UI shows.
        assertEquals("45.8 GB saved", savedLabel(45.75))
        assertEquals("1.5 GB larger", savedLabel(-1.5))
    }
}
