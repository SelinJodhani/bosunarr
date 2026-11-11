package app.bosunarr.demo

import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What a torrent was grabbed for: a Radarr movie or a Sonarr episode (drives the arr queues). */
internal data class TorrentLink(val service: String, val id: Int)

/**
 * One simulated torrent. Progress is linear in time from ([p0], [t0]) at [rate] bytes/s; the reported speed wobbles
 * around that rate so the Activity tab looks alive. Pausing/resuming re-bases (p0, t0).
 */
internal class DTorrent(
    val hash: String,
    var name: String,
    val size: Long,
    var category: String,
    var tags: String,
    val addedOn: Long,
    var p0: Double,
    var t0: Long,
    /** Average download rate (bytes/s) while downloading. */
    val rate: Long,
    /** Average upload rate (bytes/s) while active. */
    val upRate: Long,
    var state: String,
    var uploaded0: Long,
    var completionOn: Long,
    val savePath: String,
    val tracker: String,
    val seeds: Int,
    val peers: Int,
    val swarmSeeds: Int,
    val swarmPeers: Int,
    val files: List<Pair<String, Long>>,
    var link: TorrentLink? = null,
    /** Finished in qBittorrent but the arr can't import it (shown as a warning in the queue). */
    var importBlocked: String? = null,
) {
    val queueId: Int = (hash.take(6).toLong(16) % 90_000 + 10_000).toInt()
    val filePriority = mutableMapOf<Int, Int>()

    val downloading: Boolean get() = state == "downloading" || state == "forcedDL"
    val uploadingNow: Boolean get() = state == "uploading" || state == "forcedUP" || (downloading && upRate > 0)

    fun progress(now: Long): Double = if (!downloading) p0 else min(1.0, p0 + rate * DemoTorrents.speedFactor * (now - t0) / 1000.0 / size)

    /** Wobbling speed (same answer for the same second, so parallel calls agree). */
    private fun wobble(now: Long, base: Long, salt: Int): Long {
        val t = now / 1000.0
        val ph = (hash.take(4).toInt(16) % 628) / 100.0 + salt
        val f = 0.78 + 0.16 * sin(t / 6.0 + ph) + 0.08 * sin(t / 1.7 + ph * 2)
        return max(0L, (base * f).toLong())
    }

    fun dlspeed(now: Long): Long = if (downloading && progress(now) < 1) (wobble(now, rate, 0) * DemoTorrents.speedFactor).toLong() else 0
    fun upspeed(now: Long): Long = if (uploadingNow || state == "stalledUP" && seeds > 0) wobble(now, if (state == "stalledUP") upRate / 6 else upRate, 3) else 0

    fun uploaded(now: Long): Long = uploaded0 + if (uploadingNow) upRate * (now - t0) / 1000 else 0
    fun downloaded(now: Long): Long = (size * progress(now)).toLong()

    fun eta(now: Long): Long {
        val sp = dlspeed(now)
        return if (!downloading || sp <= 0) 8_640_000 else ((size - downloaded(now)) / max(1L, (rate * DemoTorrents.speedFactor).toLong()))
    }

    fun rebase(now: Long) {
        uploaded0 = uploaded(now)
        p0 = progress(now)
        t0 = now
    }
}

internal class DemoTorrents(private val w: World) {
    val list = mutableListOf<DTorrent>()
    var altSpeed = false
    var sessionStart = System.currentTimeMillis()

    companion object {
        /** Alternative speed limits slow every download down. */
        @Volatile var speedFactor = 1.0
    }

    init {
        speedFactor = 1.0
        val now = System.currentTimeMillis()
        val mb = 1024L * 1024
        fun movie(key: String) = w.movieByKey(key)

        // Downloads the arrs are waiting for.
        movie("sprite-fright")?.let { m ->
            list += make(releaseName(m.m), m.m.size, "radarr", p0 = 0.31, rate = 6 * mb, up = 380 * 1024, state = "downloading", ageH = 1.2, link = TorrentLink("radarr", m.id), seeds = 42, peers = 9)
        }
        movie("coffee-run")?.let { m ->
            list += make(releaseName(m.m), m.m.size, "radarr", p0 = 0.58, rate = (1.7 * mb).toLong(), up = 120 * 1024, state = "downloading", ageH = 3.5, link = TorrentLink("radarr", m.id), seeds = 11, peers = 4)
        }
        movie("spring")?.let { m ->
            list += make(releaseName(m.m), m.m.size, "radarr", p0 = 0.0, rate = 0, up = 0, state = "queuedDL", ageH = 0.4, link = TorrentLink("radarr", m.id), seeds = 0, peers = 0)
        }
        w.series.firstOrNull { it.s.live }?.let { s ->
            val dl = s.s.episodes.firstOrNull { it.downloading }
            val e = s.episodes.firstOrNull { it.n == dl?.n }
            if (e != null) list += make(releaseName(s.s, e), s.s.episodeSize, "tv-sonarr", p0 = 0.47, rate = (0.75 * mb).toLong(), up = 60 * 1024, state = "downloading", ageH = 0.8, link = TorrentLink("sonarr", e.id), seeds = 7, peers = 3)
            // The latest episode that did arrive is still seeding.
            s.episodes.lastOrNull { it.hasFile }?.let { last ->
                list += make(releaseName(s.s, last), s.s.episodeSize, "tv-sonarr", p0 = 1.0, rate = 0, up = 210 * 1024, state = "uploading", ageH = 60.0, seeds = 3, peers = 6, uploaded = (s.s.episodeSize * 0.62).toLong())
            }
        }
        list += make("debian-13.2.0-amd64-netinst.iso", 784 * mb, "", p0 = 0.18, rate = (0.9 * mb).toLong(), up = 240 * 1024, state = "downloading", ageH = 0.2, seeds = 312, peers = 41, tracker = "http://bttracker.debian.org:6969/announce")
        // Finished but stuck: Radarr can't import it.
        movie("plan-9-from-outer-space")?.let { m ->
            list += make(releaseName(m.m), m.m.size, "radarr", p0 = 1.0, rate = 0, up = 40 * 1024, state = "stalledUP", ageH = 30.0, link = TorrentLink("radarr", m.id), seeds = 2, peers = 0, uploaded = m.m.size / 9, blocked = "No audio tracks detected in the downloaded file")
        }
        // Seeding / completed.
        movie("big-buck-bunny")?.let { list += make(releaseName(it.m), it.m.size, "radarr", 1.0, 0, 1_450 * 1024, "uploading", ageH = 24.0 * 41, seeds = 18, peers = 7, uploaded = it.m.size * 23 / 10) }
        movie("night-of-the-living-dead")?.let { list += make(releaseName(it.m), it.m.size, "radarr", 1.0, 0, 640 * 1024, "uploading", ageH = 24.0 * 5, seeds = 25, peers = 3, uploaded = it.m.size * 7 / 10) }
        movie("sintel")?.let { list += make(releaseName(it.m), it.m.size, "radarr", 1.0, 0, 90 * 1024, "stalledUP", ageH = 24.0 * 23, seeds = 31, peers = 1, uploaded = it.m.size * 14 / 10) }
        movie("tears-of-steel")?.let { list += make(releaseName(it.m), it.m.size, "radarr", 1.0, 0, 0, "stalledUP", ageH = 24.0 * 12, seeds = 9, peers = 0, uploaded = it.m.size * 11 / 10) }
        movie("metropolis")?.let { list += make(releaseName(it.m), it.m.size, "radarr", 1.0, 0, 0, "stoppedUP", ageH = 24.0 * 52, seeds = 6, peers = 0, uploaded = it.m.size * 2) }
        movie("charade")?.let { list += make(releaseName(it.m), it.m.size, "radarr", 1.0, 0, 980 * 1024, "uploading", ageH = 50.0, seeds = 14, peers = 5, uploaded = it.m.size / 3) }
        w.series.firstOrNull { it.s.key == "caminandes" }?.let { s ->
            list += make("Caminandes.2013-2016.Complete.1080p.BluRay.x264-OPENMOVIE", s.s.episodeSize * 3, "tv-sonarr", 1.0, 0, 0, "stoppedUP", ageH = 24.0 * 210, seeds = 4, peers = 0, uploaded = s.s.episodeSize * 7)
        }
        list.sortBy { -it.addedOn }
        sessionStart = now - 3 * 3600_000L
    }

    private fun make(
        name: String, size: Long, category: String, p0: Double, rate: Long, up: Long, state: String, ageH: Double,
        link: TorrentLink? = null, seeds: Int, peers: Int, uploaded: Long = 0, tracker: String? = null, blocked: String? = null,
    ): DTorrent {
        val now = System.currentTimeMillis()
        val added = now - (ageH * 3600_000).toLong()
        val isTv = category.startsWith("tv")
        val folder = when { category == "radarr" -> "/data/downloads/movies"; isTv -> "/data/downloads/tv"; else -> "/data/downloads" }
        val files = when {
            name.endsWith(".iso") -> listOf(name to size)
            name.startsWith("Caminandes") -> listOf(
                "$name/Caminandes.S01E01.Llama.Drama.1080p.mkv" to size / 3,
                "$name/Caminandes.S01E02.Gran.Dillama.1080p.mkv" to size / 3,
                "$name/Caminandes.S01E03.Llamigos.1080p.mkv" to size / 3,
            )
            else -> listOf("$name/$name.mkv" to size - 312_000, "$name/$name.nfo" to 2_048L, "$name/Subs/English.srt" to 64_000L, "$name/Sample/sample.mkv" to 245_952L)
        }
        return DTorrent(
            hash = infoHash(name), name = name, size = size, category = category, tags = if (link != null) link.service else "",
            addedOn = added / 1000, p0 = p0, t0 = now, rate = rate, upRate = up, state = state,
            uploaded0 = uploaded, completionOn = if (p0 >= 1.0) (added + 3_600_000) / 1000 else -1,
            savePath = folder, tracker = tracker ?: if (rand(name) < 0.6) "udp://tracker.opentrackr.org:1337/announce" else "https://ia800300.us.archive.org/bt/announce",
            seeds = seeds, peers = peers, swarmSeeds = seeds * 3 + 4, swarmPeers = peers * 2 + 1,
            files = files, link = link, importBlocked = blocked,
        )
    }

    fun byHash(h: String) = list.firstOrNull { it.hash.equals(h, ignoreCase = true) }

    fun select(hashes: String?): List<DTorrent> =
        if (hashes == null) emptyList() else if (hashes == "all") list.toList() else hashes.split('|').mapNotNull { byHash(it.trim()) }

    /** Finished downloads become seeds and their movie / episode lands in the library. */
    fun tick() {
        val now = System.currentTimeMillis()
        for (t in list) {
            if (t.downloading && t.progress(now) >= 1.0) {
                t.rebase(now)
                t.p0 = 1.0
                t.state = "uploading"
                t.completionOn = now / 1000
                val l = t.link ?: continue
                if (l.service == "radarr") w.movie(l.id)?.let { m ->
                    m.hasFile = true; m.fileAdded = Instant.now()
                    w.addHistory("radarr", "downloadFolderImported", movieId = m.id, source = t.name, quality = m.m.quality, data = mapOf("downloadClientName" to "qBittorrent"))
                } else w.episode(l.id)?.let { e ->
                    e.hasFile = true; e.fileAdded = Instant.now()
                    w.addHistory("sonarr", "downloadFolderImported", episodeId = e.id, source = t.name, quality = w.seriesById(e.seriesId)?.s?.quality ?: "WEBDL-720p", data = mapOf("downloadClientName" to "qBittorrent"))
                }
            }
        }
        // A queued download starts once another one finishes.
        if (list.none { it.downloading && it.link != null && it.progress(now) < 1 }) {
            list.firstOrNull { it.state == "queuedDL" }?.let { it.state = "downloading"; it.t0 = now }
        }
    }

    /** A new download (from Add torrent, a Seerr request or a Prowlarr grab). */
    fun add(name: String, size: Long, category: String, link: TorrentLink? = null, paused: Boolean = false): DTorrent {
        byHash(infoHash(name))?.let { return it }
        val t = make(name, size, category, p0 = 0.0, rate = (2.5 * 1024 * 1024 + rand(name) * 4 * 1024 * 1024).toLong(), up = 150 * 1024,
            state = if (paused) "stoppedDL" else "downloading", ageH = 0.0, link = link, seeds = 12 + (rand(name) * 30).toInt(), peers = 3)
        list.add(0, t)
        return t
    }

    fun setAlt(on: Boolean) {
        val now = System.currentTimeMillis()
        list.forEach { it.rebase(now) }
        altSpeed = on
        speedFactor = if (on) 0.3 else 1.0
    }

    fun totalDown(now: Long) = list.sumOf { it.dlspeed(now) }
    fun totalUp(now: Long) = list.sumOf { it.upspeed(now) }
}
