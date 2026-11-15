package app.bosunarr.feature.cleanuparr

import app.bosunarr.net.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanuparrTest {
    private fun o(s: String) = AppJson.parseToJsonElement(s) as JsonObject
    private fun a(s: String) = AppJson.parseToJsonElement(s) as JsonArray

    // Shapes as Cleanuparr 2.11 returns them (taken from a live instance).
    private val queueCleaner = o("""{"enabled":true,"failedImport":{"maxStrikes":3},"downloadingMetadataMaxStrikes":3,"errored":{"maxStrikes":0}}""")
    private val stall = a("""[{"name":"Stalled","enabled":true,"maxStrikes":5},{"name":"Strict","enabled":false,"maxStrikes":2}]""")

    @Test fun limitsComeFromConfigAndTheStrictestEnabledRule() {
        val l = strikeLimits(queueCleaner, stall, a("[]"))
        assertEquals(3, l["FailedImport"])
        assertEquals(3, l["DownloadingMetadata"])
        assertEquals(0, l["Errored"])
        assertEquals(5, l["Stalled"]) // the disabled 2-strike rule doesn't count
        assertNull(l["SlowSpeed"])
    }

    @Test fun badgePicksTheTypeClosestToItsLimit() {
        val item = ClStrikeItem(totalStrikes = 4, strikesByType = mapOf("Stalled" to 2, "DownloadingMetadata" to 2))
        val b = badgeFor(item, strikeLimits(queueCleaner, stall, null))!!
        assertEquals("2 of 3 strikes · Stuck on metadata", b.text)
        assertTrue(b.critical) // one more removes it
    }

    @Test fun badgeWithoutALimitStillCounts() {
        val b = badgeFor(ClStrikeItem(totalStrikes = 1, strikesByType = mapOf("DeadTorrent" to 1)), emptyMap())!!
        assertEquals("1 strike · Dead torrent", b.text)
        assertFalse(b.critical)
    }

    @Test fun removedOrCleanItemsHaveNoBadge() {
        assertNull(badgeFor(ClStrikeItem(totalStrikes = 3, strikesByType = mapOf("Stalled" to 3), isRemoved = true), emptyMap()))
        assertNull(badgeFor(ClStrikeItem(totalStrikes = 0), emptyMap()))
    }

    @Test fun markedForRemovalIsCritical() {
        val b = badgeFor(ClStrikeItem(totalStrikes = 1, strikesByType = mapOf("Stalled" to 1), isMarkedForRemoval = true), mapOf("Stalled" to 5))!!
        assertTrue(b.critical)
    }

    @Test fun labelsReadLikeSentences() {
        assertEquals("Strike · Stuck on metadata", eventLabel("DownloadingMetadataStrike"))
        assertEquals("Removed from queue", eventLabel("QueueItemDeleted"))
        assertEquals("Some new thing", eventLabel("SomeNewThing"))
        assertEquals("Queue cleaner", jobLabel("QueueCleaner"))
        assertEquals("Blocklist sync", jobLabel("BlacklistSynchronizer"))
    }

    @Test fun liveStatsAndStrikeShapesDecode() {
        val stats = AppJson.decodeFromString(ClStats.serializer(), """{"events":{"total":8,"byType":{"DownloadingMetadataStrike":3}},"strikes":{"total":5,"byType":{"DownloadingMetadata":3,"FailedImport":2},"recovered":0},"removals":{"total":1,"byReason":{"DownloadingMetadata":1}},"cleaned":{"total":0,"byReason":{}},"searches":{"total":1,"completed":1,"failed":0,"grabbed":0,"byReason":{"Replacement":1}},"jobs":{"total":3}}""")
        assertEquals(5, stats.strikes.total)
        assertEquals(1, stats.removals.byReason["DownloadingMetadata"])
        val page = AppJson.decodeFromString(ClPage.serializer(ClStrikeItem.serializer()), """{"items":[{"downloadItemId":"01a1","downloadId":"25FB69","title":"X","totalStrikes":3,"strikesByType":{"DownloadingMetadata":3},"latestStrikeAt":"2026-11-15T06:05:00.0221728+00:00","isMarkedForRemoval":false,"isRemoved":true,"strikes":[{"id":"s1","type":"DownloadingMetadata","createdAt":"2026-11-15T06:05:00+00:00","lastDownloadedBytes":null,"isDryRun":false}]}],"page":1,"pageSize":2,"totalCount":1,"totalPages":1}""")
        assertTrue(page.items.single().isRemoved)
    }
}
