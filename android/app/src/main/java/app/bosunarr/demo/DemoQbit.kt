package app.bosunarr.demo

/** qBittorrent Web API v2 (5.x behaviour: 204 login with a session cookie, stop/start). */
internal object DemoQbit {
    private var rid = 0L

    private val categories = mapOf(
        "radarr" to "/data/downloads/movies",
        "tv-sonarr" to "/data/downloads/tv",
        "linux-isos" to "/data/downloads/iso",
    )

    fun handle(w: World, r: DemoRequest): DemoResponse? {
        val t = w.torrents
        val now = System.currentTimeMillis()
        return when (r.path) {
            "/api/v2/auth/login" -> DemoResponse(204, ByteArray(0), "text/plain", mapOf("Set-Cookie" to "SID=demo; HttpOnly; path=/; SameSite=Strict"))
            "/api/v2/app/version" -> DemoResponse.text("v5.1.2")
            "/api/v2/app/webapiVersion" -> DemoResponse.text("2.11.4")
            "/api/v2/sync/maindata" -> DemoResponse.json(obj(
                "rid" to ++rid,
                "full_update" to true,
                "torrents" to t.list.associate { it.hash to torrentJson(it, now) },
                "categories" to categories.mapValues { (k, v) -> obj("name" to k, "savePath" to v) },
                "tags" to listOf("radarr", "sonarr"),
                "server_state" to serverState(t, now),
            ))
            "/api/v2/torrents/info" -> {
                val filter = r.q("filter")
                val list = t.list.filter { x ->
                    when (filter) {
                        "active" -> x.dlspeed(now) > 0 || x.upspeed(now) > 0
                        "downloading" -> x.downloading
                        else -> true
                    }
                }
                DemoResponse.json(arr(list.map { torrentJson(it, now) }))
            }
            "/api/v2/transfer/info" -> DemoResponse.json(obj(
                "dl_info_speed" to t.totalDown(now), "up_info_speed" to t.totalUp(now),
                "dl_info_data" to sessionDown(t, now), "up_info_data" to sessionUp(t, now),
                "dl_rate_limit" to if (t.altSpeed) 5 * 1024 * 1024 else 0, "up_rate_limit" to if (t.altSpeed) 1024 * 1024 else 0,
                "dht_nodes" to 342, "connection_status" to "connected",
            ))
            "/api/v2/transfer/speedLimitsMode" -> DemoResponse.text(if (t.altSpeed) "1" else "0")
            "/api/v2/transfer/setSpeedLimitsMode" -> { t.setAlt(r.form["mode"] == "1"); DemoResponse.empty(200) }
            "/api/v2/transfer/toggleSpeedLimitsMode" -> { t.setAlt(!t.altSpeed); DemoResponse.empty(200) }
            "/api/v2/torrents/categories" -> DemoResponse.json(j(categories.mapValues { (k, v) -> obj("name" to k, "savePath" to v) }))
            "/api/v2/torrents/properties" -> t.byHash(r.q("hash").orEmpty())?.let { DemoResponse.json(properties(it, now)) } ?: DemoResponse.notFound()
            "/api/v2/torrents/trackers" -> t.byHash(r.q("hash").orEmpty())?.let { DemoResponse.json(trackers(it)) } ?: DemoResponse.notFound()
            "/api/v2/torrents/files" -> t.byHash(r.q("hash").orEmpty())?.let { x ->
                val p = x.progress(now)
                DemoResponse.json(arr(x.files.mapIndexed { i, (name, size) ->
                    val prio = x.filePriority[i] ?: if (name.contains("/Sample/")) 0 else 1
                    obj("index" to i, "name" to name, "size" to size, "progress" to if (prio == 0) 0.0 else p,
                        "priority" to prio, "availability" to if (p >= 1) 1.0 else 1.0 + x.seeds / 10.0, "is_seed" to (p >= 1))
                }))
            } ?: DemoResponse.notFound()
            "/api/v2/torrents/stop", "/api/v2/torrents/pause" -> {
                t.select(r.form["hashes"]).forEach { x ->
                    x.rebase(now)
                    x.state = if (x.p0 >= 1) "stoppedUP" else "stoppedDL"
                }
                DemoResponse.empty(200)
            }
            "/api/v2/torrents/start", "/api/v2/torrents/resume" -> {
                t.select(r.form["hashes"]).forEach { x ->
                    if (x.state.startsWith("stopped") || x.state.startsWith("paused")) {
                        x.rebase(now)
                        x.state = if (x.p0 >= 1) (if (x.upRate > 0) "uploading" else "stalledUP") else if (x.rate > 0) "downloading" else "queuedDL"
                    }
                }
                DemoResponse.empty(200)
            }
            "/api/v2/torrents/recheck", "/api/v2/torrents/reannounce" -> DemoResponse.empty(200)
            "/api/v2/torrents/delete" -> {
                val gone = t.select(r.form["hashes"]).toSet()
                t.list.removeAll(gone)
                DemoResponse.empty(200)
            }
            "/api/v2/torrents/setCategory" -> {
                t.select(r.form["hashes"]).forEach { it.category = r.form["category"].orEmpty() }
                DemoResponse.empty(200)
            }
            "/api/v2/torrents/filePrio" -> {
                t.byHash(r.form["hash"].orEmpty())?.let { x ->
                    val prio = r.form["priority"]?.toIntOrNull() ?: 1
                    r.form["id"].orEmpty().split('|').mapNotNull { it.toIntOrNull() }.forEach { x.filePriority[it] = prio }
                }
                DemoResponse.empty(200)
            }
            "/api/v2/torrents/add" -> {
                val paused = r.form["stopped"] == "true" || r.form["paused"] == "true"
                val category = r.form["category"].orEmpty()
                val names = r.form["urls"].orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map(::nameOf) +
                    listOfNotNull(r.form["torrents"]?.takeIf { it.endsWith(".torrent") }?.removeSuffix(".torrent"))
                if (names.isEmpty()) return DemoResponse.text("Fails.")
                names.forEach { n -> t.add(n, ((0.7 + rand(n) * 3.5) * 1024 * 1024 * 1024).toLong(), category, paused = paused) }
                DemoResponse.text("Ok.")
            }
            else -> null
        }
    }

    /** Display name of a magnet (`dn=`) or URL. */
    private fun nameOf(link: String): String {
        val dn = Regex("[?&]dn=([^&]+)").find(link)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        if (!dn.isNullOrBlank()) return dn
        val tail = link.substringAfterLast('/').substringBefore('?').removeSuffix(".torrent")
        return tail.takeIf { it.isNotBlank() && !link.startsWith("magnet:") } ?: "New download ${link.takeLast(8)}"
    }

    private fun sessionDown(t: DemoTorrents, now: Long) = 7_812_000_000L + t.list.sumOf { if (it.downloading) it.downloaded(now) else 0L } / 4
    private fun sessionUp(t: DemoTorrents, now: Long) = 4_402_000_000L + t.list.sumOf { it.uploaded(now) - it.uploaded0 }

    private fun serverState(t: DemoTorrents, now: Long) = obj(
        "dl_info_speed" to t.totalDown(now),
        "up_info_speed" to t.totalUp(now),
        "dl_info_data" to sessionDown(t, now),
        "up_info_data" to sessionUp(t, now),
        "alltime_dl" to 3_912_000_000_000L + sessionDown(t, now),
        "alltime_ul" to 5_284_000_000_000L + sessionUp(t, now),
        "global_ratio" to "1.35",
        "free_space_on_disk" to 3_412_000_000_000L,
        "dht_nodes" to 342,
        "total_peer_connections" to t.list.sumOf { if (it.dlspeed(now) > 0 || it.upspeed(now) > 0) it.seeds / 3 + it.peers else 0 },
        "connection_status" to "connected",
        "dl_rate_limit" to if (t.altSpeed) 5 * 1024 * 1024 else 0,
        "up_rate_limit" to if (t.altSpeed) 1024 * 1024 else 0,
        "use_alt_speed_limits" to t.altSpeed,
        "queueing" to true,
    )

    fun torrentJson(x: DTorrent, now: Long) = obj(
        "hash" to x.hash,
        "name" to x.name,
        "progress" to x.progress(now),
        "dlspeed" to x.dlspeed(now),
        "upspeed" to x.upspeed(now),
        "eta" to x.eta(now),
        "state" to x.state,
        "size" to x.size,
        "total_size" to x.size,
        "downloaded" to x.downloaded(now),
        "uploaded" to x.uploaded(now),
        "ratio" to x.uploaded(now).toDouble() / x.size,
        "category" to x.category,
        "tags" to x.tags,
        "added_on" to x.addedOn,
        "completion_on" to x.completionOn,
        "num_seeds" to if (x.downloading || x.state == "uploading") x.seeds else 0,
        "num_leechs" to if (x.downloading || x.upspeed(now) > 0) x.peers else 0,
        "num_complete" to x.swarmSeeds,
        "num_incomplete" to x.swarmPeers,
        "save_path" to x.savePath,
        "content_path" to "${x.savePath}/${x.name}",
        "amount_left" to (x.size - x.downloaded(now)),
        "tracker" to if (x.state == "queuedDL" || x.state.startsWith("stopped")) "" else x.tracker,
        "magnet_uri" to "magnet:?xt=urn:btih:${x.hash}&dn=${java.net.URLEncoder.encode(x.name, "UTF-8")}",
        "time_active" to (now / 1000 - x.addedOn),
        "seeding_time" to if (x.completionOn > 0) now / 1000 - x.completionOn else 0,
        "availability" to if (x.downloading) 1.0 + x.seeds / 7.0 else -1.0,
        "force_start" to false,
        "seq_dl" to false,
        "dl_limit" to 0,
        "up_limit" to 0,
    )

    private fun properties(x: DTorrent, now: Long) = obj(
        "save_path" to x.savePath,
        "creation_date" to x.addedOn - 86_400 * 3,
        "piece_size" to if (x.size > 4L * 1024 * 1024 * 1024) 8 * 1024 * 1024 else 2 * 1024 * 1024,
        "comment" to if (x.name.endsWith(".iso")) "Debian CD from cdimage.debian.org" else "Free to share: public domain / Creative Commons",
        "total_wasted" to 0,
        "total_uploaded" to x.uploaded(now),
        "total_downloaded" to x.downloaded(now),
        "up_limit" to -1,
        "dl_limit" to -1,
        "time_elapsed" to (now / 1000 - x.addedOn),
        "seeding_time" to if (x.completionOn > 0) now / 1000 - x.completionOn else 0,
        "nb_connections" to x.seeds / 3 + x.peers,
        "nb_connections_limit" to 100,
        "share_ratio" to x.uploaded(now).toDouble() / x.size,
        "addition_date" to x.addedOn,
        "completion_date" to x.completionOn,
        "created_by" to "mktorrent 1.1",
        "dl_speed_avg" to x.rate,
        "up_speed_avg" to x.upRate,
        "last_seen" to now / 1000 - 30,
        "pieces_have" to (x.progress(now) * pieces(x)).toLong(),
        "pieces_num" to pieces(x),
        "reannounce" to 1_342,
        "total_size" to x.size,
    )

    private fun pieces(x: DTorrent): Long = x.size / (if (x.size > 4L * 1024 * 1024 * 1024) 8 * 1024 * 1024 else 2 * 1024 * 1024) + 1

    private fun trackers(x: DTorrent) = arr(listOf(
        obj("url" to "** [DHT] **", "status" to 2, "tier" to -1, "num_peers" to x.seeds / 2, "num_seeds" to -1, "num_leeches" to -1, "num_downloaded" to -1, "msg" to ""),
        obj("url" to "** [PeX] **", "status" to 2, "tier" to -1, "num_peers" to x.peers, "num_seeds" to -1, "num_leeches" to -1, "num_downloaded" to -1, "msg" to ""),
        obj("url" to "** [LSD] **", "status" to 2, "tier" to -1, "num_peers" to 0, "num_seeds" to -1, "num_leeches" to -1, "num_downloaded" to -1, "msg" to ""),
        obj("url" to x.tracker, "status" to 2, "tier" to 0, "num_peers" to x.peers, "num_seeds" to x.swarmSeeds, "num_leeches" to x.swarmPeers, "num_downloaded" to x.swarmSeeds * 9, "msg" to ""),
        obj("url" to "udp://tracker.example.org:6969/announce", "status" to 4, "tier" to 1, "num_peers" to 0, "num_seeds" to 0, "num_leeches" to 0, "num_downloaded" to 0, "msg" to "Connection timed out"),
    ))
}
