package app.bosunarr.feature.maintainerr

import app.bosunarr.net.AppJson
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Maintainerr 3.32 shapes, built from its TypeORM entities and contracts (collection.entities.ts,
 * collection_media.entities.ts, rule-group.entities.ts, exclusion.entities.ts, media-server/types.ts). Synthetic data.
 */
class MaintainerrTest {
    private val utc = ZoneOffset.UTC

    // GET /api/collections: Collection rows + `media` (2 preview rows) + `mediaCount`. bigint columns may arrive quoted.
    private val collectionsJson = """
        [{"id":1,"mediaServerId":"12345","mediaServerType":"plex","libraryId":"1","title":"Old unwatched movies",
          "description":"Not watched in a year","isActive":true,"arrAction":0,"visibleOnRecommended":false,"visibleOnHome":false,
          "deleteAfterDays":30,"manualCollection":false,"manualCollectionName":"","listExclusions":false,"keepInMaintainerrOnly":false,
          "cleanupLeftoverFolders":false,"forceSeerr":false,"forceOmbi":false,"type":"movie","keepLogsForMonths":6,
          "addDate":"2026-01-10","handledMediaAmount":12,"lastDurationInSeconds":41,"tautulliWatchedPercentOverride":null,
          "radarrSettingsId":1,"sonarrSettingsId":null,"sortTitle":null,"mediaServerSort":null,"totalSizeBytes":"84000000000",
          "handledMediaSizeBytes":"120000000000","overlayEnabled":false,"overlayTemplateId":null,"tagInArr":false,
          "media":[{"id":91,"collectionId":1,"mediaServerId":"5001","tmdbId":949,"tvdbId":null,"addDate":"2026-11-01T00:00:00.000Z",
                    "image_path":"https://image.tmdb.org/t/p/w500/heat.jpg","isManual":false,"includedByRule":true,"manualMembershipSource":null}],
          "mediaCount":7},
         {"id":2,"libraryId":"2","title":"Finished shows","isActive":false,"arrAction":3,"deleteAfterDays":null,"type":"show",
          "totalSizeBytes":null,"handledMediaSizeBytes":0,"media":[],"mediaCount":0}]
    """.trimIndent()

    @Test fun collectionsDecode() {
        val list = AppJson.decodeFromString(ListSerializer(MtCollection.serializer()), collectionsJson)
        assertEquals(2, list.size)
        val c = list[0]
        assertEquals("Old unwatched movies", c.title)
        assertEquals(30, c.deleteAfterDays)
        assertEquals(7, c.mediaCount)
        assertEquals(84_000_000_000L, c.totalSizeBytes)
        assertEquals(120_000_000_000L, c.handledMediaSizeBytes)
        assertNull(list[1].deleteAfterDays)
        assertNull(list[1].totalSizeBytes)
        assertTrue(actsOnItems(c))
        assertFalse(actsOnItems(list[1]))
    }

    // GET /api/collections/media/:id/content/1: CollectionMedia rows with `mediaData` (MediaItemWithParent).
    private val mediaPageJson = """
        {"totalSize":3,"items":[
          {"id":91,"collectionId":1,"mediaServerId":"5001","tmdbId":949,"tvdbId":null,"addDate":"2026-11-01T00:00:00.000Z",
           "image_path":"https://image.tmdb.org/t/p/w500/heat.jpg","isManual":false,"includedByRule":true,"sizeBytes":"9800000000",
           "ruleEvaluationFailed":false,
           "mediaData":{"id":"5001","title":"Heat","guid":"plex://movie/abc","type":"movie","addedAt":"2019-03-01T10:00:00.000Z",
             "providerIds":{"tmdb":["949"],"imdb":["tt0113277"]},"mediaSources":[],"library":{"id":"1","title":"Movies"},"year":1995}},
          {"id":92,"collectionId":1,"mediaServerId":"7002","addDate":"2026-11-10T00:00:00.000Z","image_path":null,"isManual":true,
           "mediaData":{"id":"7002","parentId":"7000","grandparentId":"6990","title":"Pilot","parentTitle":"Season 1",
             "grandparentTitle":"The Show","guid":"x","type":"episode","index":1,"parentIndex":1,"addedAt":"2020-01-01T00:00:00.000Z",
             "providerIds":{"tvdb":["349232"]},"mediaSources":[],"library":{"id":"2","title":"TV"},
             "parentItem":{"id":"6990","title":"The Show","type":"show","year":2019,"providerIds":{"tvdb":["81189"]}}}},
          {"id":93,"collectionId":1,"mediaServerId":"7000","addDate":"2026-11-12T00:00:00.000Z","tvdbId":81189,
           "mediaData":{"id":"7000","parentId":"6990","title":"Season 2","parentTitle":"The Show","type":"season","index":2,
             "providerIds":{},"mediaSources":[],"library":{"id":"2","title":"TV"}}}
        ]}
    """.trimIndent()

    private fun page() = AppJson.decodeFromString(MtPage.serializer(MtCollectionMedia.serializer()), mediaPageJson)

    @Test fun mediaPageDecodes() {
        val p = page()
        assertEquals(3, p.totalSize)
        val heat = p.items[0]
        assertEquals("5001", heat.mediaServerId)
        assertEquals(9_800_000_000L, heat.sizeBytes)
        assertEquals(listOf("949"), heat.mediaData?.providerIds?.tmdb)
        assertEquals("https://image.tmdb.org/t/p/w500/heat.jpg", storedPoster(heat))
        assertEquals(true, p.items[1].isManual)
        assertEquals("The Show", p.items[1].mediaData?.parentItem?.title)
    }

    @Test fun titles() {
        val p = page()
        assertEquals("Heat (1995)", mediaTitle(p.items[0].mediaData))
        assertEquals("The Show · S01E01 · Pilot", mediaTitle(p.items[1].mediaData))
        assertEquals("The Show · Season 2", mediaTitle(p.items[2].mediaData))
        assertEquals("abc", mediaTitle(null, "abc"))
    }

    @Test fun posterLookupUsesProviderIdsAndItemIdForEpisodes() {
        val p = page()
        assertEquals("/api/metadata/image/movie?tmdbId=949", posterLookupPath(p.items[0]))
        assertEquals("/api/metadata/image/show?tvdbId=349232&itemId=7002", posterLookupPath(p.items[1]))
        // No provider ids on the season itself: falls back to the row's own tvdbId.
        assertEquals("/api/metadata/image/show?tvdbId=81189&itemId=7000", posterLookupPath(p.items[2]))
        assertNull(posterLookupPath(MtCollectionMedia(mediaServerId = "1")))
        assertNull(storedPoster(MtCollectionMedia(imagePath = "/relative.jpg")))
    }

    private val thirtyDays = MtCollection(id = 1, title = "Old", isActive = true, arrAction = MtAction.DELETE, deleteAfterDays = 30)

    @Test fun daysLeftMatchesMaintainerrsBadge() {
        val item = MtCollectionMedia(addDate = "2026-11-01T00:00:00.000Z")
        // Due 2026-12-01T00:00Z. At noon on 18 Nov that's 12.5 days, shown rounded up.
        assertEquals(13, daysLeft(thirtyDays, item, Instant.parse("2026-11-18T12:00:00Z"), utc))
        assertEquals(1, daysLeft(thirtyDays, item, Instant.parse("2026-11-30T23:00:00Z"), utc))
        assertEquals(0, daysLeft(thirtyDays, item, Instant.parse("2026-12-01T00:00:00Z"), utc))
        assertEquals(-2, daysLeft(thirtyDays, item, Instant.parse("2026-12-03T00:00:00Z"), utc))
        // A date-only addDate (as older rows store it) parses too, as local midnight (noon keeps this zone-proof).
        assertEquals(30, daysLeft(thirtyDays, MtCollectionMedia(addDate = "2026-11-01"), Instant.parse("2026-11-01T12:00:00Z"), utc))
    }

    @Test fun collectionsThatNeverActHaveNoDaysLeft() {
        val item = MtCollectionMedia(addDate = "2026-11-01T00:00:00.000Z")
        val now = Instant.parse("2026-11-18T00:00:00Z")
        assertNull(daysLeft(thirtyDays.copy(isActive = false), item, now, utc))
        assertNull(daysLeft(thirtyDays.copy(arrAction = MtAction.DO_NOTHING), item, now, utc))
        assertNull(daysLeft(thirtyDays.copy(deleteAfterDays = null), item, now, utc))
        assertNull(daysLeft(thirtyDays, MtCollectionMedia(addDate = null), now, utc))
        // A quality-profile change ignores any stored window: it's due now.
        val qp = thirtyDays.copy(arrAction = MtAction.CHANGE_QUALITY_PROFILE)
        assertEquals(0, effectiveWindow(qp))
        assertTrue(daysLeft(qp, item, now, utc)!! <= 0)
    }

    @Test fun labels() {
        assertEquals("Goes on the next run", daysLeftText(0))
        assertEquals("Goes on the next run", daysLeftText(-3))
        assertEquals("1 day left", daysLeftText(1))
        assertEquals("12 days left", daysLeftText(12))
        assertEquals("Deleted after 30 days", timerText(thirtyDays))
        assertEquals("Show deleted after 1 day", timerText(thirtyDays.copy(type = "show", deleteAfterDays = 1)))
        assertEquals("No timer: nothing is handled", timerText(thirtyDays.copy(deleteAfterDays = null)))
        assertEquals("Listed only, nothing happens", timerText(thirtyDays.copy(arrAction = MtAction.DO_NOTHING)))
        assertEquals("Quality profile changed on the next run", timerText(thirtyDays.copy(arrAction = MtAction.CHANGE_QUALITY_PROFILE)))
        assertTrue(deletesFiles(MtAction.DELETE))
        assertFalse(deletesFiles(MtAction.UNMONITOR))
    }

    // GET /api/rules: RuleGroup rows with `rules` and the joined `collection`.
    private val rulesJson = """
        [{"id":4,"name":"Old unwatched movies","description":null,"libraryId":"1","isActive":true,"collectionId":1,"useRules":true,
          "dataType":"movie","ruleHandlerCronSchedule":null,
          "rules":[{"id":10,"ruleJson":"{}","section":0,"ruleGroupId":4,"isActive":true}],"notifications":[],
          "collection":{"id":1,"title":"Old unwatched movies","isActive":true,"arrAction":0,"deleteAfterDays":30,"type":"movie","libraryId":"1"}},
         {"id":5,"name":"Paused","libraryId":"","isActive":false,"collectionId":3,"useRules":true,"rules":[],"collection":null}]
    """.trimIndent()

    @Test fun rulesJoinCollectionsAndKeepManualOnes() {
        val rules = AppJson.decodeFromString(ListSerializer(MtRuleGroup.serializer()), rulesJson)
        val collections = AppJson.decodeFromString(ListSerializer(MtCollection.serializer()), collectionsJson)
        assertEquals(1, rules[0].rules.size)
        val rows = ruleRows(collections, rules)
        // Rule 4 → collection 1; rule 5 has no collection in the list; collection 2 has no rule (manual).
        assertEquals(3, rows.size)
        val first = rows.first()
        assertEquals("Old unwatched movies", first.title)
        assertEquals(7, first.collection?.mediaCount) // the list's row, with its count, wins over the joined one
        assertTrue(first.canRun)
        val paused = rows.first { it.rule?.id == 5 }
        assertFalse(paused.active)
        assertFalse(paused.canRun) // inactive, and no library
        val manual = rows.first { it.rule == null }
        assertEquals("Finished shows", manual.title)
        assertFalse(manual.canRun)
        assertFalse(rows.last().active) // inactive ones sort last
    }

    @Test fun executeStatusDecodes() {
        val st = AppJson.decodeFromString(
            MtExecuteStatus.serializer(),
            """{"processingQueue":true,"executingRuleGroupId":4,"pendingRuleGroupIds":[5],"queue":[5]}""",
        )
        assertTrue(st.processingQueue)
        assertEquals(4, st.executingRuleGroupId)
        assertEquals(listOf(5), st.pendingRuleGroupIds)
    }

    @Test fun versionDecodes() {
        val v = AppJson.decodeFromString(MtVersion.serializer(), """{"status":1,"version":"3.32.0","commitTag":"latest-ad17e05","updateAvailable":false}""")
        assertEquals("3.32.0", v.version)
        assertEquals(1, v.status)
    }

    @Test fun bulkAnswersAreCheckedPerItem() {
        fun r(s: String) = AppJson.decodeFromString(MtBulkResponse.serializer(), s)
        assertNull(bulkFailure(r("""{"results":[{"mediaId":"5001","code":1,"message":"Success"}]}"""), "5001"))
        assertEquals("No rule group", bulkFailure(r("""{"results":[{"mediaId":"5001","code":0,"message":"Failed - no rule group"}]}"""), "5001"))
        assertEquals(
            "Excluded, but not removed from the collection",
            bulkFailure(r("""{"results":[{"mediaId":"5001","code":0,"message":"Excluded, but not removed from the collection"}]}"""), "5001"),
        )
        assertEquals("Maintainerr couldn't do that", bulkFailure(r("""{"results":[{"mediaId":"5001","code":0,"message":"Failed"}]}"""), "5001"))
        assertEquals("Maintainerr didn't say whether it worked", bulkFailure(r("""{"results":[]}"""), "5001"))
    }

    // GET /api/collections/exclusions/:id/content/1: Exclusion rows (ruleGroupId null = every rule) with mediaData.
    @Test fun exclusionsDecode() {
        val p = AppJson.decodeFromString(
            MtPage.serializer(MtExclusion.serializer()),
            """{"totalSize":2,"items":[
                 {"id":8,"mediaServerId":"5001","ruleGroupId":4,"parent":"5001","type":"movie",
                  "mediaData":{"id":"5001","title":"Heat","type":"movie","year":1995,"providerIds":{}}},
                 {"id":9,"mediaServerId":"7000","ruleGroupId":null,"parent":"6990","type":"season"}]}""",
        )
        assertEquals(4, p.items[0].ruleGroupId)
        assertNull(p.items[1].ruleGroupId)
        assertEquals("6990", p.items[1].parent)
        assertEquals("Heat (1995)", mediaTitle(p.items[0].mediaData))
    }

    @Test fun postponeAnswerDecodes() {
        val r = AppJson.decodeFromString(
            MtPostponed.serializer(),
            """{"collectionId":1,"mediaServerId":"5001","addDate":"2026-11-15T00:00:00.000Z","deleteAfterDays":30,"deletionDate":"2026-12-15T00:00:00.000Z"}""",
        )
        assertEquals(27, daysUntil(Instant.parse(r.deletionDate!!), Instant.parse("2026-11-18T00:00:00Z")))
    }

    @Test fun leavingSortsCollectionsAndItemsSoonestFirst() {
        fun item(id: String, d: Int?) = MtLeavingItem(MtCollectionMedia(mediaServerId = id), id, d, null)
        val later = MtLeavingGroup(MtCollection(id = 1, title = "B"), null, listOf(item("a", 20), item("b", 9)), 2)
        val sooner = MtLeavingGroup(MtCollection(id = 2, title = "A"), null, listOf(item("c", null), item("d", 2)), 2)
        val out = leavingOrder(listOf(later, sooner))
        assertEquals(listOf(2, 1), out.map { it.collection.id })
        assertEquals(listOf("d", "c"), out[0].items.map { it.media.mediaServerId })
        assertEquals(listOf("b", "a"), out[1].items.map { it.media.mediaServerId })
    }
}
