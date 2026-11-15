package app.bosunarr.demo

import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Cleanuparr: strikes on real demo torrents (so torrent rows show them), one download it already removed and
 * replaced, its jobs, and one item waiting for the user. State lives in the world bag.
 */
internal object DemoCleanuparr {
    private class Item(
        val id: String, val hash: String, val title: String, val type: String, var strikes: Int,
        val removed: Boolean, val firstAt: Instant, var latestAt: Instant,
    )

    private class State(val items: MutableList<Item>, val events: MutableList<JsonObject>, val attention: MutableList<JsonObject>)

    private fun uuid(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

    private fun state(w: World): State = w.misc.bag.getOrPut("cleanuparr") {
        val now = Instant.now()
        val items = mutableListOf<Item>()
        // A queued download that never got metadata (2 of 3) and a stalled one (1 of 5), both real demo torrents.
        val incomplete = w.torrents.list.filter { it.p0 < 1.0 }
        incomplete.firstOrNull { it.state.startsWith("queued") || it.p0 == 0.0 }?.let { t ->
            items += Item(uuid("cl-meta-${t.hash}"), t.hash, t.name, "DownloadingMetadata", 2, false, now.minus(Duration.ofMinutes(12)), now.minus(Duration.ofMinutes(4)))
        }
        incomplete.lastOrNull { t -> items.none { it.hash == t.hash } }?.let { t ->
            items += Item(uuid("cl-stall-${t.hash}"), t.hash, t.name, "Stalled", 1, false, now.minus(Duration.ofMinutes(9)), now.minus(Duration.ofMinutes(9)))
        }
        val gone = "Night.of.the.Living.Dead.1968.1080p.WEBRip.x264-UNKNOWN"
        items += Item(uuid("cl-gone"), infoHash(gone), gone, "DownloadingMetadata", 3, true, now.minus(Duration.ofHours(27)), now.minus(Duration.ofHours(26)))

        val events = mutableListOf<JsonObject>()
        fun ev(at: Instant, type: String, sev: String, msg: String, title: String?, hash: String?, strikes: Int? = null, extra: Map<String, Any?> = emptyMap()) {
            events += obj(
                *(listOf(
                    "id" to uuid("ev-$type-$at"), "timestamp" to at, "eventType" to type, "severity" to sev, "message" to msg,
                    "itemTitle" to title, "itemHash" to hash, "strikeCount" to strikes, "isDryRun" to false,
                ) + extra.toList()).toTypedArray(),
            )
        }
        for (i in items) {
            val strikeType = i.type + "Strike"
            for (n in 1..i.strikes) {
                val at = i.firstAt.plus(Duration.ofMinutes(5L * (n - 1)))
                ev(at, strikeType, "Important", "Item '${i.title}' has been struck $n times for reason '${i.type}'", i.title, i.hash, n)
            }
            if (i.removed) {
                val at = i.latestAt.plusSeconds(2)
                ev(at, "DownloadMarkedForDeletion", "Important", "Download marked for deletion", i.title, i.hash, extra = mapOf("deleteReason" to i.type))
                ev(at.plusSeconds(1), "QueueItemDeleted", "Important", "Deleting item from queue with reason: ${i.type}", i.title, i.hash, extra = mapOf("deleteReason" to i.type))
                ev(at.plus(Duration.ofMinutes(6)), "SearchTriggered", "Information", "Search triggered for ${i.title}", i.title, null,
                    extra = mapOf("searchReason" to "Replacement", "grabbedItems" to listOf("Night.of.the.Living.Dead.1968.1080p.BluRay.FLAC.2.0.x264-ARCHiVE")))
            }
        }
        ev(now.minus(Duration.ofHours(40)), "DownloadCleaned", "Information", "Cleaned finished download (seeding goal reached)", "Elephants.Dream.2006.1080p.BluRay.x264-OPENMOVIE", null,
            extra = mapOf("cleanReason" to "MaxRatioReached"))
        events.sortByDescending { it["timestamp"].str() }

        val attention = mutableListOf(
            obj(
                "id" to uuid("cl-attention-1"), "timestamp" to now.minus(Duration.ofHours(3)), "severity" to "Warning", "type" to "FailedImport",
                "message" to "Sonarr couldn't import it automatically (no video files matched the episode). Import it by hand in Sonarr, or remove it.",
                "itemTitle" to "Sherlock.Holmes.1954.S01E21.The.Case.of.the.Night.Train.Riddle.720p.WEB-DL.AAC2.0.H.264-ARCHiVE",
                "isResolved" to false, "downloadClientName" to "qBittorrent", "isDryRun" to false,
            ),
        )
        State(items, events, attention)
    } as State

    fun handle(w: World, r: DemoRequest): DemoResponse? {
        val st = state(w)
        val p = r.path
        return when {
            p == "/api/status" -> DemoResponse.json(obj(
                "application" to obj("version" to "2.11.0.0", "startTime" to Instant.now().minus(Duration.ofHours(45))),
                "mediaManagers" to obj("Sonarr" to obj("instanceCount" to 1), "Radarr" to obj("instanceCount" to 1)),
            ))
            p == "/api/v2/stats" -> stats(st, r.q("hours")?.toIntOrNull() ?: 168)
            p == "/api/jobs" -> DemoResponse.json(arr(jobs()))
            p.startsWith("/api/jobs/") && p.endsWith("/trigger") && r.method == "POST" -> DemoResponse.json(obj("message" to "Job triggered"))
            p == "/api/manualevents" -> {
                val wantResolved = r.q("isResolved")?.toBooleanStrictOrNull()
                val items = st.attention.filter { wantResolved == null || it["isResolved"].bool() == wantResolved }
                DemoResponse.json(page(items, 1, items.size.coerceAtLeast(1)))
            }
            p.startsWith("/api/manualevents/") && p.endsWith("/resolve") && r.method == "POST" -> {
                val id = p.removePrefix("/api/manualevents/").removeSuffix("/resolve")
                val i = st.attention.indexOfFirst { it["id"].str() == id }
                if (i >= 0) st.attention[i] = JsonObject(st.attention[i] + ("isResolved" to kotlinx.serialization.json.JsonPrimitive(true)))
                DemoResponse.json(obj())
            }
            p == "/api/events" -> {
                val type = r.q("eventType")
                val list = st.events.filter { type == null || it["eventType"].str() == type }
                DemoResponse.json(page(list, r.q("page")?.toIntOrNull() ?: 1, r.q("pageSize")?.toIntOrNull() ?: 50))
            }
            p == "/api/strikes" -> DemoResponse.json(page(st.items.map(::itemJson), 1, 100))
            p.startsWith("/api/strikes/") && r.method == "DELETE" -> {
                val id = p.removePrefix("/api/strikes/")
                st.items.removeAll { it.id == id }
                DemoResponse.json(obj())
            }
            p == "/api/configuration/queue_cleaner" -> DemoResponse.json(obj(
                "enabled" to true, "failedImport" to obj("maxStrikes" to 3), "downloadingMetadataMaxStrikes" to 3, "errored" to obj("maxStrikes" to 0),
            ))
            p == "/api/queue-rules/stall" -> DemoResponse.json(arr(listOf(obj("name" to "Stalled", "enabled" to true, "maxStrikes" to 5))))
            p == "/api/queue-rules/slow" -> DemoResponse.json(arr(emptyList<Any>()))
            else -> null
        }
    }

    private fun itemJson(i: Item) = obj(
        "downloadItemId" to i.id, "downloadId" to i.hash, "title" to i.title, "totalStrikes" to i.strikes,
        "strikesByType" to obj(i.type to i.strikes), "latestStrikeAt" to i.latestAt, "firstStrikeAt" to i.firstAt,
        "isMarkedForRemoval" to false, "isRemoved" to i.removed, "isReturning" to false, "hasDryRunStrikes" to false,
        "strikes" to (1..i.strikes).map { n -> obj("id" to uuid("${i.id}-$n"), "type" to i.type, "createdAt" to i.firstAt.plus(Duration.ofMinutes(5L * (n - 1))), "isDryRun" to false) },
    )

    private fun page(items: List<JsonObject>, page: Int, size: Int): JsonObject {
        val from = ((page - 1) * size).coerceIn(0, items.size)
        return obj(
            "items" to items.subList(from, (from + size).coerceAtMost(items.size)), "page" to page, "pageSize" to size,
            "totalCount" to items.size, "totalPages" to if (size <= 0) 0 else (items.size + size - 1) / size,
        )
    }

    private fun stats(st: State, hours: Int): DemoResponse {
        val since = Instant.now().minus(Duration.ofHours(hours.toLong()))
        val recent = st.events.filter { (it["timestamp"].str()?.let(Instant::parse) ?: Instant.EPOCH).isAfter(since) }
        fun count(pred: (JsonObject) -> Boolean) = recent.filter(pred)
        val strikes = count { it["eventType"].str().orEmpty().endsWith("Strike") }
        val removals = count { it["eventType"].str() == "QueueItemDeleted" }
        val cleaned = count { it["eventType"].str() == "DownloadCleaned" }
        val searches = count { it["eventType"].str() == "SearchTriggered" }
        fun by(list: List<JsonObject>, key: (JsonObject) -> String?) = list.mapNotNull(key).groupingBy { it }.eachCount()
        return DemoResponse.json(obj(
            "events" to obj("total" to recent.size, "byType" to by(recent) { it["eventType"].str() }),
            "strikes" to obj("total" to strikes.size, "byType" to by(strikes) { it["eventType"].str()?.removeSuffix("Strike") }, "recovered" to 0),
            "removals" to obj("total" to removals.size, "byReason" to by(removals) { it["deleteReason"].str() }),
            "cleaned" to obj("total" to cleaned.size, "byReason" to by(cleaned) { it["cleanReason"].str() }),
            "searches" to obj("total" to searches.size, "completed" to searches.size, "failed" to 0, "grabbed" to searches.size, "byReason" to obj("Replacement" to searches.size)),
        ))
    }

    private fun jobs(): List<JsonObject> {
        val now = Instant.now()
        fun at(min: Long) = now.plus(Duration.ofMinutes(min))
        return listOf(
            obj("name" to "QueueCleaner", "jobType" to "QueueCleaner", "status" to "Scheduled", "schedule" to "0 0/5 * * * ?", "previousRunTime" to at(-3), "nextRunTime" to at(2)),
            obj("name" to "MalwareBlocker", "jobType" to "MalwareBlocker", "status" to "Scheduled", "schedule" to "0/5 * * * * ?", "previousRunTime" to now.minusSeconds(4), "nextRunTime" to now.plusSeconds(1)),
            obj("name" to "DownloadCleaner", "jobType" to "DownloadCleaner", "status" to "Scheduled", "schedule" to "0 0 * * * ?", "previousRunTime" to at(-41), "nextRunTime" to at(19)),
            obj("name" to "Seeker", "jobType" to "Seeker", "status" to "Scheduled", "schedule" to "0 */10 * * * ?", "previousRunTime" to at(-7), "nextRunTime" to at(3)),
            obj("name" to "BlacklistSynchronizer", "jobType" to "BlacklistSynchronizer", "status" to "Not Scheduled"),
        )
    }
}
