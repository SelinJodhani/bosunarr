package app.bosunarr.feature.services

import app.bosunarr.data.ServiceConfig
import app.bosunarr.net.AppJson
import app.bosunarr.net.isMediaBrowserId
import app.bosunarr.net.mediaBrowserList
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Jellyfin screen's models against Emby's JSON (synthetic data, shaped after Emby's OpenAPI spec:
 * SystemInfo, ItemCounts, QueryResult_VirtualFolderInfo, QueryResult_UserDto, Session.SessionInfo, BaseItemDto).
 */
class EmbyParsingTest {
    private val emby = ServiceConfig(id = "e", type = "emby", name = "Emby", url = "http://h:8096", apiKey = "k")
    private val jellyfin = ServiceConfig(id = "j", type = "jellyfin", name = "Jellyfin", url = "http://h:8096", apiKey = "k")

    @Test
    fun systemInfo() {
        val info = AppJson.decodeFromString(
            JfInfo.serializer(),
            """
            {"SystemUpdateLevel": "Release", "OperatingSystemDisplayName": "Linux", "PackageName": "emby-server-deb_4.9.1.90_amd64.deb",
             "HasPendingRestart": false, "IsShuttingDown": false, "OperatingSystem": "Linux", "SupportsLibraryMonitor": true,
             "WebSocketPortNumber": 8096, "CompletedInstallations": [], "CanSelfRestart": true, "CanSelfUpdate": false,
             "HttpServerPortNumber": 8096, "SupportsHttps": false, "HttpsPortNumber": 8920, "HasUpdateAvailable": true,
             "IsInMaintenanceMode": false, "LocalAddress": "http://192.168.1.10:8096", "LocalAddresses": ["http://192.168.1.10:8096"],
             "RemoteAddresses": [], "ServerName": "Living room", "Version": "4.9.1.90", "Id": "0a1b2c3d4e5f60718293a4b5c6d7e8f9"}
            """,
        )
        assertEquals("Living room", info.serverName)
        assertEquals("4.9.1.90", info.version)
        assertEquals("Linux", info.os)
        assertFalse(info.restartPending)
        assertTrue(info.updateAvailable)
    }

    @Test
    fun itemCounts() {
        val c = AppJson.decodeFromString(
            JfCounts.serializer(),
            """{"MovieCount": 812, "SeriesCount": 94, "EpisodeCount": 4410, "GameCount": 0, "ArtistCount": 120, "ProgramCount": 0,
                "GameSystemCount": 0, "TrailerCount": 3, "SongCount": 9012, "AlbumCount": 702, "MusicVideoCount": 0,
                "BoxSetCount": 12, "BookCount": 5, "ItemCount": 15170}""",
        )
        assertEquals(812, c.movies)
        assertEquals(94, c.series)
        assertEquals(4410, c.episodes)
        assertEquals(702, c.albums)
    }

    @Test
    fun librariesComeAsAPage() {
        val folders = AppJson.decodeFromString(
            mediaBrowserList(JfFolder.serializer()),
            """
            {"Items": [
              {"Name": "Movies", "Locations": ["/data/movies"], "CollectionType": "movies", "ItemId": "3", "Id": "3",
               "Guid": "2f6c9a1e0b8d4c7aa1f3e5d7c9b1a3e5", "RefreshStatus": "Idle"},
              {"Name": "TV", "Locations": ["/data/tv"], "CollectionType": "tvshows", "ItemId": "1527", "Id": "1527",
               "Guid": "7c1d3e5f7a9b4c2d8e0f1a3b5c7d9e1f", "RefreshProgress": 42.5, "RefreshStatus": "Active"},
              {"Name": "Mixed", "Locations": ["/data/home"], "ItemId": "88", "Id": "88"}
             ], "TotalRecordCount": 3}
            """,
        )
        assertEquals(listOf("Movies", "TV", "Mixed"), folders.map { it.name })
        // Emby's item ids are numbers: they must pass the id check that keeps them safe to put in a path.
        assertTrue(folders.all { isMediaBrowserId(it.itemId) })
        assertEquals("Active", folders[1].refreshStatus)
        assertEquals(42.5, folders[1].refreshProgress!!, 0.0)
        assertNull(folders[2].collectionType)
    }

    @Test
    fun usersComeAsAPage() {
        val users = AppJson.decodeFromString(
            mediaBrowserList(JfUser.serializer()),
            """
            {"Items": [
              {"Name": "kid", "ServerId": "0a1b2c3d4e5f60718293a4b5c6d7e8f9", "Id": "9e8d7c6b5a4f30211f2e3d4c5b6a7980",
               "HasPassword": true, "LastActivityDate": "2026-11-17T20:01:44.0000000Z",
               "Policy": {"IsAdministrator": false, "IsHidden": true, "IsDisabled": false}},
              {"Name": "old", "Id": "11111111111111111111111111111111",
               "Policy": {"IsAdministrator": true, "IsHidden": true, "IsDisabled": true}},
              {"Name": "admin", "ServerId": "0a1b2c3d4e5f60718293a4b5c6d7e8f9", "Id": "5d9db6fe9d18455c891cf84f2ab5910b",
               "HasPassword": true, "LastLoginDate": "2026-11-18T08:12:00.0000000Z", "LastActivityDate": "2026-11-18T09:30:12.0000000Z",
               "Policy": {"IsAdministrator": true, "IsHidden": false, "IsDisabled": false}}
             ], "TotalRecordCount": 3}
            """,
        )
        assertEquals(3, users.size)
        val admin = pickAdmin(users)!!
        assertEquals("admin", admin.name)
        assertTrue(isMediaBrowserId(admin.id))
        assertTrue(users[1].policy.disabled)
    }

    @Test
    fun jellyfinUsersStillDecodeFromABareArray() {
        val users = AppJson.decodeFromString(
            mediaBrowserList(JfUser.serializer()),
            """[{"Name": "me", "Id": "f137a2dd21bbc1b99aa5c0f6bf02a805", "Policy": {"IsAdministrator": true, "IsDisabled": false}}]""",
        )
        assertEquals("me", pickAdmin(users)!!.name)
    }

    @Test
    fun transcodingSession() {
        val sessions = AppJson.decodeFromString(
            ListSerializer(JfSession.serializer()),
            """
            [
             {"PlayState": {"CanSeek": true, "IsPaused": false, "IsMuted": false, "RepeatMode": "RepeatNone",
                            "PositionTicks": 9000000000, "PlayMethod": "Transcode", "MediaSourceId": "mediasource_131261"},
              "AdditionalUsers": [], "RemoteEndPoint": "192.168.1.40", "PlayableMediaTypes": ["Audio", "Video"],
              "Id": "4f1c2a7e9b3d4e5f8a6b7c8d9e0f1a2b", "ServerId": "0a1b2c3d4e5f60718293a4b5c6d7e8f9",
              "UserId": "5d9db6fe9d18455c891cf84f2ab5910b", "UserName": "admin", "Client": "Emby for Android",
              "LastActivityDate": "2026-11-18T09:30:12.0000000Z", "DeviceName": "Pixel 9", "DeviceId": "abc123",
              "ApplicationVersion": "3.4.20",
              "NowPlayingItem": {"Name": "Pilot", "ServerId": "0a1b2c3d4e5f60718293a4b5c6d7e8f9", "Id": "131261",
                                 "RunTimeTicks": 27000000000, "IndexNumber": 1, "ParentIndexNumber": 1, "IsFolder": false,
                                 "Type": "Episode", "SeriesName": "Example Show", "SeriesId": "131200",
                                 "SeasonId": "131201", "SeriesPrimaryImageTag": "8f2c1a7d", "ImageTags": {"Primary": "c0ffee11"},
                                 "MediaType": "Video"},
              "TranscodingInfo": {"AudioCodec": "aac", "VideoCodec": "h264", "Container": "ts", "IsVideoDirect": false,
                                  "IsAudioDirect": false, "Bitrate": 8000000, "Framerate": 23.976, "CompletionPercentage": 12.5,
                                  "Width": 1920, "Height": 1080, "AudioChannels": 2,
                                  "TranscodeReasons": ["VideoCodecNotSupported", "AudioCodecNotSupported"],
                                  "VideoDecoderIsHardware": false, "VideoEncoderIsHardware": true, "VideoEncoderHwAccel": "VAAPI"},
              "SupportsRemoteControl": true},
             {"PlayState": {"CanSeek": false, "IsPaused": false, "IsMuted": false},
              "Id": "idle", "UserName": "kid", "Client": "Emby Web", "DeviceName": "Firefox", "SupportsRemoteControl": true}
            ]
            """,
        )
        val rows = sessions.mapNotNull { nowPlaying(emby, it) }
        assertEquals("the idle client is dropped", 1, rows.size)
        val np = rows[0]
        assertEquals("Example Show", np.title)
        assertEquals("S01E01 · Pilot", np.subtitle)
        assertEquals("admin", np.user)
        assertEquals("Emby for Android · Pixel 9", np.device)
        assertEquals(PlayMethod.Transcode, np.method)
        assertEquals(900.0, np.position, 0.0)
        assertEquals(2700.0, np.runtime, 0.0)
        assertEquals(listOf("Video codec not supported", "Audio codec not supported"), np.reasons)
        assertEquals("VAAPI", np.hwAccel)
        assertEquals("Video → H264 · Audio → AAC · TS · 8.0 Mbps", np.detail)
        assertEquals("/emby/Items/131200/Images/Primary?maxHeight=300", np.poster)
    }

    @Test
    fun softwareDecoderIsNotShownAsHardware() {
        val s = AppJson.decodeFromString(
            JfSession.serializer(),
            """{"Id": "s", "UserName": "u", "PlayState": {"PlayMethod": "Transcode"},
                "NowPlayingItem": {"Id": "77", "Name": "Film", "Type": "Movie", "ProductionYear": 2024, "ImageTags": {"Primary": "t"}},
                "TranscodingInfo": {"IsVideoDirect": false, "IsAudioDirect": true, "VideoCodec": "hevc",
                                    "VideoDecoderIsHardware": false, "VideoDecoderHwAccel": "Software", "VideoEncoderIsHardware": false}}""",
        )
        val np = nowPlaying(emby, s)!!
        assertNull(np.hwAccel)
        assertEquals("2024", np.subtitle)
        assertEquals("/emby/Items/77/Images/Primary?maxHeight=300", np.poster)
    }

    @Test
    fun latestItems() {
        val items = AppJson.decodeFromString(
            ListSerializer(JfItem.serializer()),
            """
            [
             {"Name": "Example Show", "ServerId": "0a1b2c3d4e5f60718293a4b5c6d7e8f9", "Id": "131200",
              "DateCreated": "2026-11-17T22:00:00.0000000Z", "ProductionYear": 2025, "IsFolder": true, "Type": "Series",
              "ChildCount": 3, "ImageTags": {"Primary": "8f2c1a7d"}},
             {"Name": "A Film", "Id": "131400", "DateCreated": "2026-11-16T10:00:00.0000000Z", "ProductionYear": 2024,
              "Type": "Movie", "ImageTags": {}},
             {"Name": "Track", "Id": "140001", "Type": "Audio", "AlbumId": "140000", "AlbumPrimaryImageTag": "a1"}
            ]
            """,
        )
        assertEquals(listOf("131200", "131400", "140001"), items.map { it.id })
        assertEquals("/emby/Items/131200/Images/Primary?maxHeight=300", posterOf(emby, items[0]))
        assertNull("no Primary tag", posterOf(emby, items[1]))
        assertEquals("/emby/Items/140000/Images/Primary?maxHeight=300", posterOf(emby, items[2]))
        // The same item on Jellyfin has no prefix.
        assertEquals("/Items/131200/Images/Primary?maxHeight=300", posterOf(jellyfin, items[0]))
    }
}
